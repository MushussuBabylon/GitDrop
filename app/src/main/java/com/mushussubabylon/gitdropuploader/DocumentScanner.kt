package com.mushussubabylon.gitdropuploader

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * What the user picked.
 *
 *  - [SingleFile]: one document from the multi-file picker.
 *  - [Tree]: a whole folder from the folder picker. Everything inside is included except the
 *    documents listed in [excluded] (files or sub-folders the user unticked in the browser).
 *
 * Destination paths are relative to what was picked: the picked folder's own name is not part
 * of the path, so its contents land directly in the chosen repository folder.
 */
sealed class Sel {
    class SingleFile(val uri: Uri, val name: String, val size: Long) : Sel()
    class Tree(val treeUri: Uri, val rootDocId: String, val rootName: String, val excluded: Set<String>) : Sel()
}

class ScanResult(
    val files: List<UploadFile>,
    /** ".git" folders that were left out (GitHub cannot store anything below a ".git" path). */
    val gitFoldersSkipped: Int,
    /** Folders whose content could not be listed. */
    val unreadableFolders: List<String>
)

/** Reads files and folders chosen through Android's Storage Access Framework. Blocking: call from a worker thread. */
class DocumentScanner(private val resolver: ContentResolver) {

    class Child(val docId: String, val name: String, val isDir: Boolean, val size: Long)

    /** Display name and size (-1 when unknown) of a single document. */
    fun describe(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
        } catch (_: Exception) {
        }
        return Pair(name ?: uri.lastPathSegment ?: "file", size)
    }

    /** Name of the document with this id inside a picked tree (used for the picked folder itself). */
    fun treeRootName(treeUri: Uri, docId: String): String {
        try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
                }
        } catch (_: Exception) {
        }
        return docId.substringAfterLast('/').substringAfterLast(':').ifEmpty { "folder" }
    }

    /** Direct children of a folder: folders first, then files, each group sorted by name. */
    fun listChildren(treeUri: Uri, parentDocId: String): List<Child> {
        val out = ArrayList<Child>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw FileNotFoundException("cannot list $parentDocId")
        cursor.use { c ->
            val idI = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameI = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeI = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeI = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (c.moveToNext()) {
                if (idI < 0 || c.isNull(idI)) continue
                val id = c.getString(idI)
                val name = if (nameI >= 0 && !c.isNull(nameI)) c.getString(nameI) else id
                val mime = if (mimeI >= 0 && !c.isNull(mimeI)) c.getString(mimeI) else ""
                val size = if (sizeI >= 0 && !c.isNull(sizeI)) c.getLong(sizeI) else -1L
                out.add(Child(id, name, mime == DocumentsContract.Document.MIME_TYPE_DIR, size))
            }
        }
        out.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        return out
    }

    /**
     * Turns the selection into the list of files to upload.
     * [prefix] is the destination folder inside the repository ("" or "docs/photos/").
     */
    fun scan(
        selection: List<Sel>,
        prefix: String,
        isCancelled: () -> Boolean,
        onFound: (Int) -> Unit
    ): ScanResult {
        val files = ArrayList<UploadFile>()
        val unreadable = ArrayList<String>()
        var gitSkipped = 0
        var lastReported = 0

        fun found() {
            if (files.size - lastReported >= 25) {
                lastReported = files.size
                onFound(files.size)
            }
        }

        fun walk(tree: Sel.Tree, docId: String, rel: String) {
            if (isCancelled()) throw UploadCancelled()
            val kids = try {
                listChildren(tree.treeUri, docId)
            } catch (e: Exception) {
                unreadable.add(if (rel.isEmpty()) tree.rootName else rel)
                return
            }
            for (k in kids) {
                if (k.docId in tree.excluded) continue
                val path = if (rel.isEmpty()) k.name else "$rel/${k.name}"
                if (k.isDir) {
                    if (k.name.equals(".git", ignoreCase = true)) {
                        gitSkipped++
                        continue
                    }
                    walk(tree, k.docId, path)
                } else {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree.treeUri, k.docId)
                    files.add(UploadFile(prefix + path, normalizeSize(k.size)) { open(uri) })
                    found()
                }
            }
        }

        for (s in selection) {
            if (isCancelled()) throw UploadCancelled()
            when (s) {
                is Sel.SingleFile -> {
                    files.add(UploadFile(prefix + s.name, normalizeSize(s.size)) { open(s.uri) })
                    found()
                }
                is Sel.Tree -> walk(s, s.rootDocId, "")
            }
        }
        onFound(files.size)
        return ScanResult(files, gitSkipped, unreadable)
    }

    /**
     * Some providers report 0 or nothing for files they have not downloaded yet. Treat both as
     * "unknown": the engine then copies the file once to learn its true size.
     */
    private fun normalizeSize(size: Long): Long = if (size > 0) size else -1L

    private fun open(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
}
