package com.mushussubabylon.gitdropuploader

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** GitHub answered, but not with "OK". */
class ApiError(val code: Int, val detail: String) : IOException("HTTP $code: $detail")

/**
 * The few read-only calls the screens need (who am I, which repositories, which branches,
 * which folders). They go through the same [GitHubClient] as the upload engine, so they share
 * its headers, timeouts, retry rules and rate-limit handling.
 */
class GitHubApi(token: String) {

    class RepoInfo(val fullName: String, val defaultBranch: String)

    private val client = GitHubClient(
        token,
        // Screens must stay responsive: give up quickly instead of waiting out a long rate limit.
        EngineConfig(maxRetries = 2, maxRateWaitSec = 3, readTimeoutMs = 30_000),
        null
    )

    fun close() = client.close()

    fun cancel() = client.cancel()

    /** Login name of the token's owner. Throws [ApiError] when GitHub rejects the token. */
    fun user(): String {
        val r = client.get("/user")
        if (!r.ok) throw ApiError(r.code, r.message)
        return try {
            r.json().optString("login", "")
        } catch (e: JSONException) {
            ""
        }
    }

    /** Repositories the token may push to, most recently updated first (at most 500). */
    fun repos(): List<RepoInfo> {
        val out = ArrayList<RepoInfo>()
        var page = 1
        while (page <= MAX_REPO_PAGES) {
            val r = client.get(
                "/user/repos?per_page=100&page=$page&sort=updated&affiliation=owner,collaborator,organization_member"
            )
            if (!r.ok) throw ApiError(r.code, r.message)
            val arr = parseArray(r.text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // Skip only what is known to be read-only; if GitHub sends no permissions block, keep the repository.
                val perms = o.optJSONObject("permissions")
                if (perms != null && !perms.optBoolean("push", false)) continue
                if (o.optBoolean("archived", false)) continue // archived repositories are read-only
                val name = o.optString("full_name", "")
                if (name.isEmpty()) continue
                out.add(RepoInfo(name, o.optString("default_branch", "")))
            }
            if (arr.length() < 100) break
            page++
        }
        return out
    }

    /** Branch names of [repo] (at most 300). Empty for a repository that has no commit yet. */
    fun branches(repo: String): List<String> {
        val out = ArrayList<String>()
        var page = 1
        while (page <= MAX_BRANCH_PAGES) {
            val r = client.get("/repos/$repo/branches?per_page=100&page=$page")
            if (!r.ok) throw ApiError(r.code, r.message)
            val arr = parseArray(r.text)
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i)?.optString("name", "") ?: ""
                if (n.isNotEmpty()) out.add(n)
            }
            if (arr.length() < 100) break
            page++
        }
        return out
    }

    /** Sub-folders of [path] ("" = repository root) on [branch], sorted by name. */
    fun folders(repo: String, branch: String, path: String): List<String> {
        val enc = GitHubClient.encodePath(branch)
        val api = if (path.isEmpty()) "/repos/$repo/contents?ref=$enc"
        else "/repos/$repo/contents/${GitHubClient.encodePath(path)}?ref=$enc"
        val r = client.get(api)
        if (r.code == 404) return emptyList() // nothing there (yet)
        if (!r.ok) throw ApiError(r.code, r.message)
        val arr = parseArray(r.text)
        val dirs = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") == "dir") dirs.add(o.optString("name"))
        }
        dirs.sortBy { it.lowercase() }
        return dirs
    }

    /** Commit id the branch points to right now, or null when it cannot be read. */
    fun branchHead(repo: String, branch: String): String? {
        val r = client.get("/repos/$repo/git/ref/heads/${GitHubClient.encodePath(branch)}")
        if (!r.ok) return null
        return try {
            r.json().getJSONObject("object").getString("sha")
        } catch (e: JSONException) {
            null
        }
    }

    /** Creates [name] as a new branch that starts at the current head of [fromBranch]. */
    fun createBranch(repo: String, fromBranch: String, name: String) {
        val head = client.get("/repos/$repo/git/ref/heads/${GitHubClient.encodePath(fromBranch)}")
        if (!head.ok) throw ApiError(head.code, head.message)
        val sha = try {
            head.json().getJSONObject("object").getString("sha")
        } catch (e: JSONException) {
            throw ApiError(200, "Unexpected answer from GitHub")
        }
        val body = JSONObject().put("ref", "refs/heads/$name").put("sha", sha)
        // No automatic retry for a write whose outcome is unclear: the person can simply try again.
        val r = client.post("/repos/$repo/git/refs", jsonBody(body.toString()), CallOptions(retryTransient = false))
        if (!r.ok) throw ApiError(r.code, r.message)
    }

    private fun parseArray(text: String): JSONArray =
        try {
            JSONArray(text)
        } catch (e: JSONException) {
            throw ApiError(200, "Unexpected answer from GitHub")
        }

    private companion object {
        const val MAX_REPO_PAGES = 5
        const val MAX_BRANCH_PAGES = 3
    }
}

/** Checks branch names the way git does (close enough to catch typos before GitHub answers with an error). */
object BranchNames {
    fun isValid(name: String): Boolean {
        if (name.isEmpty() || name.length > 200) return false
        if (name == "@" || name.startsWith("/") || name.endsWith("/") || name.startsWith("-") || name.startsWith(".")) return false
        if (name.endsWith(".") || name.endsWith(".lock")) return false
        if (name.contains("..") || name.contains("//") || name.contains("@{") || name.contains("/.")) return false
        for (c in name) {
            if (c.code <= 32 || c.code == 127) return false // control characters and spaces
            if (c in "~^:?*[\\") return false
        }
        return true
    }
}
