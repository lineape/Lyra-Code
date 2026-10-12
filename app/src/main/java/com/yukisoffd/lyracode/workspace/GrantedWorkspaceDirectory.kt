package com.yukisoffd.lyracode.workspace

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/** A child selection retains its granted tree anchor, rather than inventing a new SAF grant. */
internal fun grantedWorkspaceDirectory(context: Context, uri: Uri): DocumentFile? {
    val directory = DocumentFile.fromTreeUri(context, uri) ?: return null
    if (!DocumentsContract.isDocumentUri(context, uri)) return directory
    val rootId = DocumentsContract.getTreeDocumentId(uri)
    val selectedId = DocumentsContract.getDocumentId(uri)
    if (selectedId == rootId) return directory
    require(uri.authority == "com.android.externalstorage.documents") { "Unsupported directory provider" }
    grantedDirectorySegments(rootId, selectedId)
    // DocumentFile 1.0.1 already uses the selected document ID for a tree/document URI.
    // Validate its relationship to the grant; traversing again would select nested/nested.
    return directory
}

/** ExternalStorageProvider uses a colon for a volume root and slashes below a directory. */
internal fun grantedDirectorySegments(rootId: String, selectedId: String): List<String> {
    if (selectedId == rootId) return emptyList()
    val prefix = if (rootId.endsWith(':')) rootId else "$rootId/"
    require(selectedId.startsWith(prefix)) { "Directory is outside the granted tree" }
    return selectedId.removePrefix(prefix).split('/').also { segments ->
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "Invalid directory path" }
    }
}
