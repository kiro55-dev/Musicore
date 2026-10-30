package com.example.musicore

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** Recorre carpetas (SAF) de forma recursiva y devuelve las canciones encontradas. */
object Scanner {
    private val exts = setOf("mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "webm", "weba", "mka", "amr")

    fun scan(ctx: Context, trees: List<String>): List<Song> {
        val out = LinkedHashMap<String, Song>() // clave = documentId → sin duplicados entre carpetas solapadas
        for (t in trees) {
            val tree = Uri.parse(t)
            walk(ctx, tree, DocumentsContract.getTreeDocumentId(tree), out)
        }
        return out.values.sortedBy { it.title.lowercase() }
    }

    private fun walk(ctx: Context, tree: Uri, docId: String, out: MutableMap<String, Song>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                when {
                    mime == Document.MIME_TYPE_DIR -> walk(ctx, tree, id, out)
                    mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in exts -> {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                        out.putIfAbsent(id, Song(id, uri, name.substringBeforeLast('.')))
                    }
                }
            }
        }
    }
}
