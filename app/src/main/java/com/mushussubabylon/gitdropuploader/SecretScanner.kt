package com.mushussubabylon.gitdropuploader

import kotlin.math.ln

/** What a [SecretFinding] points to. The screen turns it into a localized label. */
enum class SecretKind { TOKEN, PRIVATE_KEY, PASSWORD, KEY_FILE, ENV_FILE, CREDENTIALS_FILE }

/**
 * One reason to look twice at a file before it goes to GitHub.
 *
 * It never contains the suspected secret itself, so it is safe to show and to keep in memory.
 *
 * @property provider the service a token belongs to ("GitHub", "AWS" ...), or "" when not known
 * @property line 1-based line of the match, or 0 when the file NAME is what looked suspicious
 */
class SecretFinding(val kind: SecretKind, val provider: String, val line: Int)

/**
 * Looks for passwords, keys and other credentials in files that are about to be uploaded.
 * Everything happens on the phone; nothing is sent anywhere.
 *
 * This is a safety net built on well-known patterns, not a guarantee: it can miss a secret and it
 * can flag harmless text. That is why the review screen lets the user decide what to upload.
 *
 * No Android classes are used here, so the scanner can be unit tested on a normal JVM.
 */
class SecretScanner {

    // ------------------------------------------------------------------ file names

    /** Findings based only on where the file is and what it is called (".env", "release.keystore" ...). */
    fun scanName(path: String): List<SecretFinding> {
        val segments = path.replace('\\', '/').lowercase().split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return emptyList()
        val name = segments[segments.size - 1]
        val dirs = segments.subList(0, segments.size - 1)
        if (isTemplate(name)) return emptyList() // ".env.example" and friends are meant to be shared

        val ext = name.substringAfterLast('.', "")
        val kind: SecretKind? = when {
            name == ".env" || name.startsWith(".env.") || name.endsWith(".env") -> SecretKind.ENV_FILE
            name in KEY_FILE_NAMES || ext in KEY_FILE_EXTENSIONS -> SecretKind.KEY_FILE
            name in CREDENTIAL_NAMES -> SecretKind.CREDENTIALS_FILE
            name.startsWith("client_secret") && ext == "json" -> SecretKind.CREDENTIALS_FILE
            (name.startsWith("service-account") || name.startsWith("serviceaccount") || name.contains("-adminsdk-")) &&
                ext == "json" -> SecretKind.CREDENTIALS_FILE
            (name.startsWith("secrets.") || name.startsWith("secret.")) && ext in SECRET_FILE_EXTENSIONS ->
                SecretKind.CREDENTIALS_FILE
            name.startsWith("terraform.tfstate") -> SecretKind.CREDENTIALS_FILE
            dirs.any { it == ".ssh" || it == ".aws" || it == ".gnupg" } && !isPublicSshFile(name) -> SecretKind.KEY_FILE
            else -> null
        }
        return if (kind == null) emptyList() else listOf(SecretFinding(kind, "", 0))
    }

    /** False for file types that never hold readable text (images, archives, binaries ...). */
    fun looksTextual(path: String): Boolean = extensionOf(path) !in BINARY_EXTENSIONS

    // ------------------------------------------------------------------ file content

    /** Findings inside the file's content. Binary data (it contains NUL bytes) is not scanned. */
    fun scanContent(path: String, data: ByteArray): List<SecretFinding> {
        if (data.isEmpty() || isBinary(data)) return emptyList()
        val text = String(data, Charsets.ISO_8859_1)
        val lowerText: String by lazy(LazyThreadSafetyMode.NONE) { text.lowercase() }
        val configLike = isConfigLike(path)
        val out = ArrayList<SecretFinding>()
        val seen = HashSet<String>()

        for (rule in RULES) {
            if (rule.configOnly && !configLike) continue
            val haystack: String = if (rule.lower) lowerText else text
            if (!rule.mayMatch(haystack)) continue
            var tries = 0
            for (m in rule.regex.findAll(text)) {
                tries++
                if (tries > MAX_MATCHES_PER_RULE) break
                val value: String = if (rule.group == 0) m.value else (m.groups[rule.group]?.value ?: continue)
                val accept = rule.accept
                if (accept != null && !accept(value)) continue
                val line = lineOf(text, m.range.first)
                if (seen.add(rule.kind.name + "|" + rule.provider + "|" + line)) {
                    out.add(SecretFinding(rule.kind, rule.provider, line))
                }
                if (out.size >= MAX_FINDINGS) return out
            }
        }
        return out
    }

    // ------------------------------------------------------------------ internals

    internal class Rule(
        val provider: String,
        val kind: SecretKind,
        /** Cheap pre-check: the text must contain at least one of these before the regex runs. */
        val hints: Array<String>,
        /** True when [hints] are lower case and are matched against a lower-cased copy of the text. */
        val lower: Boolean,
        /** Only applied to settings-style files (.env, .properties, .yml ...). */
        val configOnly: Boolean,
        val regex: Regex,
        /** Regex group holding the suspected secret value (0 = the whole match). */
        val group: Int,
        /** Extra check on the value, to cut false alarms (placeholders, sample keys ...). */
        val accept: ((String) -> Boolean)?
    ) {
        fun mayMatch(haystack: String): Boolean {
            for (h in hints) if (haystack.contains(h)) return true
            return false
        }
    }

    private fun isBinary(data: ByteArray): Boolean {
        val n = minOf(data.size, 8000)
        for (i in 0 until n) if (data[i].toInt() == 0) return true
        return false
    }

    private fun lineOf(text: String, index: Int): Int {
        var line = 1
        var i = 0
        val end = minOf(index, text.length)
        while (i < end) {
            if (text[i] == '\n') line++
            i++
        }
        return line
    }

    private fun isTemplate(name: String): Boolean {
        for (s in TEMPLATE_SUFFIXES) if (name.endsWith(s)) return true
        return false
    }

    private fun isPublicSshFile(name: String): Boolean =
        name.endsWith(".pub") || name.startsWith("known_hosts") || name == "config" || name == "authorized_keys"

    private fun isConfigLike(path: String): Boolean {
        val name = fileNameOf(path).lowercase()
        if (name == ".env" || name.startsWith(".env.") || name.endsWith(".env")) return true
        return extensionOf(path) in CONFIG_EXTENSIONS
    }

    companion object {
        private const val MAX_FINDINGS = 6
        private const val MAX_MATCHES_PER_RULE = 40
        private const val MIN_ENTROPY = 2.5
        private val LN2 = ln(2.0)

        fun fileNameOf(path: String): String = path.replace('\\', '/').substringAfterLast('/')

        fun extensionOf(path: String): String {
            val name = fileNameOf(path)
            val dot = name.lastIndexOf('.')
            return if (dot < 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
        }

        private val TEMPLATE_SUFFIXES = listOf(".example", ".sample", ".template", ".dist", ".default", ".defaults", ".tpl", ".tmpl")

        private val KEY_FILE_NAMES = setOf("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "id_ecdsa_sk", "id_ed25519_sk")
        private val KEY_FILE_EXTENSIONS = setOf("jks", "keystore", "p12", "pfx", "ppk", "p8", "pkcs12", "kdbx", "jceks", "bks", "key")
        private val CREDENTIAL_NAMES = setOf(
            "keystore.properties", "key.properties", ".netrc", "_netrc", ".pgpass", ".htpasswd", ".git-credentials",
            ".pypirc", ".dockercfg", "credentials", "credentials.json", "serviceaccountkey.json", "wp-config.php"
        )
        private val SECRET_FILE_EXTENSIONS = setOf("json", "yml", "yaml", "xml", "properties", "toml", "ini", "env", "txt", "conf", "cfg")
        private val CONFIG_EXTENSIONS = setOf("properties", "ini", "cfg", "conf", "toml", "yml", "yaml", "txt", "sh", "env")

        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "heif", "tif", "tiff", "psd",
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "mp4", "m4v", "mov", "mkv", "avi", "webm", "3gp",
            "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "tar", "jar", "war", "aar", "apk", "aab",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
            "ttf", "otf", "woff", "woff2", "eot",
            "so", "a", "o", "dylib", "dll", "exe", "class", "dex", "bin", "dat",
            "db", "sqlite", "sqlite3", "realm",
            "jks", "keystore", "p12", "pfx"
        )

        /** Words that show a value is a stand-in, not a real secret. */
        private val PLACEHOLDERS = listOf(
            "your", "example", "changeme", "change_me", "change-me", "placeholder", "xxxx", "****", "todo",
            "dummy", "sample", "fake", "redacted", "replace", "insert", "enter", "here", "password", "passwd",
            "secret", "token", "apikey", "api_key", "api-key", "\${", "{{", "%s", "%(", "<", ">", "\u2022",
            "\u2026", "null", "none", "undefined", "env.", "getenv"
        )

        /** Throw-away passwords that are not worth a warning when they sit inside a URL. */
        private val URL_PLACEHOLDERS = setOf(
            "pass", "pwd", "postgres", "root", "admin", "user", "username", "guest", "test", "mysql", "mongo",
            "redis", "docker", "xxx", "***", "1234", "12345", "123456"
        )

        private fun looksLikeSecretValue(value: String): Boolean {
            if (value.length < 8) return false
            val first = value[0]
            if (first == '$' || first == '%' || first == '{' || first == '<' || first == '@' ||
                first == '/' || first == '[' || first == '('
            ) return false
            val lower = value.lowercase()
            for (p in PLACEHOLDERS) if (lower.contains(p)) return false
            var hasLower = false
            var hasUpper = false
            var hasDigit = false
            var hasSymbol = false
            for (c in value) {
                when {
                    c in 'a'..'z' -> hasLower = true
                    c in 'A'..'Z' -> hasUpper = true
                    c in '0'..'9' -> hasDigit = true
                    else -> hasSymbol = true
                }
            }
            var kinds = 0
            if (hasLower) kinds++
            if (hasUpper) kinds++
            if (hasDigit) kinds++
            if (hasSymbol) kinds++
            if (kinds < 2) return false
            return entropy(value) >= MIN_ENTROPY
        }

        private fun looksLikeUrlPassword(pw: String): Boolean {
            val first = pw[0]
            if (first == '$' || first == '%' || first == '{' || first == '[' || first == '(') return false
            val lower = pw.lowercase()
            if (lower in URL_PLACEHOLDERS) return false
            for (p in PLACEHOLDERS) if (lower.contains(p)) return false
            return true
        }

        private fun entropy(s: String): Double {
            val counts = HashMap<Char, Int>()
            for (c in s) counts[c] = (counts[c] ?: 0) + 1
            val n = s.length.toDouble()
            var h = 0.0
            for (v in counts.values) {
                val p = v / n
                h -= p * (ln(p) / LN2)
            }
            return h
        }

        /** A token with a distinctive prefix: very few false alarms. */
        private fun token(
            provider: String,
            hints: Array<String>,
            pattern: String,
            accept: ((String) -> Boolean)? = null
        ) = Rule(provider, SecretKind.TOKEN, hints, false, false, Regex(pattern), 0, accept)

        // Identifier characters that may surround a "password"-like word in a variable or setting name.
        private const val NAME = "[A-Za-z0-9_.\\-]*"
        private const val SENSITIVE_WORD =
            "(?:password|passwd|secret|api[_\\-]?key|apikey|access[_\\-]?key|private[_\\-]?key|" +
                "(?:auth|access|api|bot|refresh|session)[_\\-]?token)"
        private val GENERIC_HINTS = arrayOf(
            "password", "passwd", "secret", "apikey", "api_key", "api-key", "accesskey", "access_key",
            "access-key", "privatekey", "private_key", "private-key", "token"
        )

        private val RULES: List<Rule> = listOf(
            token("GitHub", arrayOf("ghp_", "gho_", "ghu_", "ghs_", "ghr_"), """\bgh[pousr]_[A-Za-z0-9]{36,255}"""),
            token("GitHub", arrayOf("github_pat_"), """\bgithub_pat_[A-Za-z0-9_]{22,255}"""),
            token("GitLab", arrayOf("glpat-"), """\bglpat-[A-Za-z0-9_\-]{20,}"""),
            token(
                "AWS", arrayOf("AKIA", "ASIA", "ABIA", "ACCA"),
                """\b(?:AKIA|ASIA|ABIA|ACCA)[A-Z0-9]{16}(?![A-Z0-9])""",
                accept = { !it.endsWith("EXAMPLE") }
            ),
            Rule(
                "AWS", SecretKind.TOKEN, arrayOf("aws_secret", "awssecret"), true, false,
                Regex("""(?i)aws_?secret_?(?:access_?)?key["']?\s*[:=]\s*["']?[A-Za-z0-9/+=]{40}(?![A-Za-z0-9/+=])"""),
                0, null
            ),
            token("Google", arrayOf("AIza"), """\bAIza[0-9A-Za-z_\-]{35}"""),
            token("Google", arrayOf("GOCSPX-"), """\bGOCSPX-[A-Za-z0-9_\-]{28}"""),
            token("Slack", arrayOf("xox"), """\bxox[abprs]-[A-Za-z0-9\-]{10,}"""),
            token(
                "Slack", arrayOf("hooks.slack.com"),
                """https://hooks\.slack\.com/services/T[A-Za-z0-9]{8,}/B[A-Za-z0-9]{8,}/[A-Za-z0-9]{20,}"""
            ),
            token("Stripe", arrayOf("_live_"), """\b[rs]k_live_[0-9A-Za-z]{20,}"""),
            token("OpenAI", arrayOf("sk-proj-"), """\bsk-proj-[A-Za-z0-9_\-]{40,}"""),
            token("Anthropic", arrayOf("sk-ant-"), """\bsk-ant-[A-Za-z0-9_\-]{30,}"""),
            token("SendGrid", arrayOf("SG."), """\bSG\.[A-Za-z0-9_\-]{22}\.[A-Za-z0-9_\-]{43}"""),
            token("npm", arrayOf("npm_"), """\bnpm_[A-Za-z0-9]{36}(?![A-Za-z0-9])"""),
            token("Hugging Face", arrayOf("hf_"), """\bhf_[A-Za-z0-9]{34,}"""),
            token("DigitalOcean", arrayOf("dop_v1_"), """\bdop_v1_[a-f0-9]{64}"""),
            token("Shopify", arrayOf("shpat_", "shpca_", "shppa_", "shpss_"), """\bshp(?:at|ca|pa|ss)_[a-fA-F0-9]{32}"""),
            token("Telegram", arrayOf(":AA"), """\b[0-9]{8,10}:AA[A-Za-z0-9_\-]{32,34}(?![A-Za-z0-9_\-])"""),

            Rule(
                "", SecretKind.PRIVATE_KEY, arrayOf("PRIVATE KEY"), false, false,
                Regex("""-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?-----"""),
                0, null
            ),

            // https://user:secret@host/ : a password written into an address (database URLs, git remotes ...)
            Rule(
                "", SecretKind.PASSWORD, arrayOf("://"), false, false,
                Regex("""\b[a-zA-Z][a-zA-Z0-9+.\-]{1,20}://[^\s:@/"'<>]{1,64}:([^\s:@/"'<>]{4,64})@[A-Za-z0-9.\-\[\]]+"""),
                1, { looksLikeUrlPassword(it) }
            ),

            // password = "value"  /  "api_key": "value"  /  val storePassword = "value"
            Rule(
                "", SecretKind.PASSWORD, GENERIC_HINTS, true, false,
                Regex(
                    "(?i)(?<![A-Za-z0-9_.\\-])" + NAME + SENSITIVE_WORD + NAME +
                        "[\"']?\\s*[:=]\\s*[\"']([^\"'\\s\\\\]{8,100})[\"']"
                ),
                1, { looksLikeSecretValue(it) }
            ),

            // DB_PASSWORD=value  /  password: value   (settings files only, where quotes are optional)
            Rule(
                "", SecretKind.PASSWORD, GENERIC_HINTS, true, true,
                Regex(
                    "(?im)^[ \\t]*(?:export[ \\t]+)?" + NAME + SENSITIVE_WORD + NAME +
                        "[ \\t]*[:=][ \\t]*([^\\s\"'#]{8,100})[ \\t]*(?:#.*)?$"
                ),
                1, { looksLikeSecretValue(it) }
            )
        )
    }
}
