package com.example.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real Android file and document management helper.
 * Uses app-specific external storage and Storage Access Framework.
 * Respects scoped storage and avoids root/broad filesystem traversal.
 */
class DocumentManagerHelper(private val context: Context) {

    private val documentsDir: File by lazy {
        val dir = File(context.getExternalFilesDir(null), "documents")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    fun listDocuments(): List<Map<String, Any>> {
        val files = documentsDir.listFiles() ?: return emptyList()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return files.filter { it.isFile }.map { file ->
            mapOf(
                "fileName" to file.name,
                "sizeBytes" to file.length(),
                "lastModified" to sdf.format(Date(file.lastModified())),
                "mimeType" to getMimeType(file.name),
                "absolutePath" to file.absolutePath
            )
        }
    }

    fun saveDocument(fileName: String, content: String): Pair<Boolean, String> {
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return try {
            val file = File(documentsDir, safeName)
            file.writeText(content, Charsets.UTF_8)
            true to "Document saved successfully: ${file.name} (${file.length()} bytes) at ${file.absolutePath}"
        } catch (e: Exception) {
            false to "Failed to save document '$fileName': ${e.message}"
        }
    }

    fun readDocument(fileName: String): Pair<Boolean, String> {
        val file = File(documentsDir, fileName)
        if (!file.exists() || !file.isFile) {
            // Check if full path or relative
            val direct = File(fileName)
            if (direct.exists() && direct.isFile && direct.absolutePath.startsWith(context.filesDir.parent ?: "")) {
                return try {
                    true to direct.readText(Charsets.UTF_8)
                } catch (e: Exception) {
                    false to "Failed to read file: ${e.message}"
                }
            }
            return false to "Document '$fileName' not found in JARVIS documents storage."
        }

        return try {
            val text = file.readText(Charsets.UTF_8)
            true to text
        } catch (e: Exception) {
            false to "Failed to read document '$fileName': ${e.message}"
        }
    }

    fun deleteDocument(fileName: String): Pair<Boolean, String> {
        val file = File(documentsDir, fileName)
        if (!file.exists()) {
            return false to "Document '$fileName' does not exist."
        }
        return try {
            val deleted = file.delete()
            if (deleted) {
                true to "Document '$fileName' deleted."
            } else {
                false to "Could not delete document '$fileName'."
            }
        } catch (e: Exception) {
            false to "Error deleting document: ${e.message}"
        }
    }

    fun prepareCreateDocument(mimeType: String, suggestedFileName: String): Pair<Boolean, String> {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType.ifBlank { "text/plain" }
            putExtra(Intent.EXTRA_TITLE, suggestedFileName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            true to "SAF file creation picker opened. Please select where to save '$suggestedFileName'."
        } catch (e: Exception) {
            false to "Failed to launch Storage Access Framework: ${e.message}"
        }
    }

    fun prepareOpenDocument(mimeType: String): Pair<Boolean, String> {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType.ifBlank { "*/*" }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            true to "SAF document picker opened. Please select a document."
        } catch (e: Exception) {
            false to "Failed to launch document picker: ${e.message}"
        }
    }

    fun openFileWithDefaultViewer(fileName: String): Pair<Boolean, String> {
        val file = File(documentsDir, fileName)
        val target = if (file.exists() && file.isFile) file else File(fileName)
        if (!target.exists() || !target.isFile) {
            return false to "File '$fileName' does not exist."
        }

        return try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target
            )
            val mimeType = getMimeType(target.name)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true to "Opened file '${target.name}' with system default viewer."
        } catch (e: Exception) {
            false to "Failed to open file: ${e.message}"
        }
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast(".", "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "text/plain"
    }
}
