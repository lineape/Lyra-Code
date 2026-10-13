package com.yukisoffd.lyracode.webaccess

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.yukisoffd.lyracode.ai.ChatRecord
import com.yukisoffd.lyracode.workspace.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Short-lived, per-file read capabilities. Never accepts a client-supplied filesystem path. */
internal class WebAccessMedia(private val context: Context, private val workspace: WorkspaceManager) {
    private data class Entry(val id: String, val source: String, val name: String, val mime: String, var expires: Long)
    private val entries = linkedMapOf<String, Entry>()
    private val referencesByRecord = mutableMapOf<Long, Pair<String, Map<String,Pair<String,String>>>>()
    private val attachmentPattern=Regex("<lyra_attachment_v1>([\\s\\S]*?)</lyra_attachment_v1>")
    private val markdownPattern=Regex("(?:!\\[[^]]*]|\\[[^]]*])\\((?:<([^>]+)>|([^\\s)]+))[^)]*\\)")
    private val htmlPattern=Regex("(?:src|href)=[\"']([^\"']+)[\"']",RegexOption.IGNORE_CASE)
    private val pathPattern=Regex("(?:file|content|proot)://[^\\s<>\"')]+|/[^\\s<>\"')]+\\.(?:png|jpe?g|gif|webp|svg|mp4|webm|mp3|wav|ogg|m4a)",RegexOption.IGNORE_CASE)

    @Synchronized fun update(messages: List<ChatRecord>, uploads: List<UploadedFile>) {
        val references = linkedMapOf<String, Pair<String, String>>()
        var target: MutableMap<String,Pair<String,String>> = references
        fun add(source: String, name: String = "", mime: String = "") {
            if (source.isNotBlank() && !source.startsWith("data:") && !source.startsWith("http:") && !source.startsWith("https:")) {
                target[source] = name.ifBlank { Uri.parse(source).lastPathSegment.orEmpty() } to mime
            }
        }
        uploads.forEach { add(it.uri, it.name, it.mimeType) }
        referencesByRecord.keys.retainAll(messages.map { it.id }.toSet())
        for (message in messages) {
            val cached=referencesByRecord[message.id]
            if(cached?.first==message.content) { references.putAll(cached.second);continue }
            target=linkedMapOf()
            attachmentPattern.findAll(message.content).forEach { match ->
                runCatching { JSONObject(match.groupValues[1]).let { add(it.optString("uri"), it.optString("name"), it.optString("mime_type")) } }
            }
            markdownPattern.findAll(message.content).forEach {
                add(it.groupValues[1].ifBlank { it.groupValues[2] })
            }
            htmlPattern.findAll(message.content).forEach { add(it.groupValues[1]) }
            pathPattern.findAll(message.content).forEach { add(it.value) }
            referencesByRecord[message.id]=message.content to target
            references.putAll(target)
        }
        val selected=references.entries.take(256)
        entries.keys.retainAll(selected.map { it.key }.toSet())
        selected.forEach { (source, metadata) ->
            entries.getOrPut(source) { Entry(UUID.randomUUID().toString(), source, metadata.first, metadata.second, 0) }.expires = System.currentTimeMillis() + 300_000
        }
    }

    @Synchronized fun catalog(): JSONArray = JSONArray(entries.values.map { JSONObject().put("id",it.id).put("source",it.source).put("name",it.name).put("mime",it.mime) })

    fun open(id: String): Resource {
        val entry = synchronized(this) { entries.values.firstOrNull { it.id == id && it.expires >= System.currentTimeMillis() } }
            ?: throw WebHttpError(404, "Media expired or unavailable")
        val uri = Uri.parse(entry.source)
        require(uri.scheme in setOf(null,"file","content",ProotWorkspace.SCHEME)) { "Unsupported media source" }
        val file = when (uri.scheme) {
            "content" -> null
            ProotWorkspace.SCHEME -> {
                val root = ProotWorkspace.directory(context, ProotWorkspace.uri(uri.authority.orEmpty(), "/")).canonicalFile
                val candidate = File(root, uri.path.orEmpty().trimStart('/')).canonicalFile
                require(SharedStorageWorkspace.contains(root,candidate))
                candidate
            }
            else -> {
                val candidate = if (uri.scheme == "file") File(requireNotNull(uri.path)).canonicalFile else {
                    val raw = File(uri.path ?: entry.source)
                    if (raw.isAbsolute) raw.canonicalFile else File(workspace.termuxRootPath() ?: throw WebHttpError(404,"No workspace"),uri.path ?: entry.source).canonicalFile
                }
                val uploadsRoot = File(context.filesDir, "chat_uploads").canonicalFile
                val workspaceRoot = workspace.termuxRootPath()?.let(::File)?.canonicalFile
                if (!SharedStorageWorkspace.contains(uploadsRoot,candidate) && (workspaceRoot == null || !SharedStorageWorkspace.contains(workspaceRoot,candidate))) {
                    SharedStorageWorkspace.file(context,Uri.fromFile(candidate))
                }
                candidate
            }
        }
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        val mime = entry.mime.ifBlank { MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream" }
            .takeIf { it.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) } ?: "application/octet-stream"
        val safeMime = mime.takeIf { it.startsWith("image/") || it.startsWith("audio/") || it.startsWith("video/") } ?: "application/octet-stream"
        if (file != null) {
            require(file.isFile && file.canRead()) { "Media unavailable" }
            return Resource(safeMime,file.length(),entry.name) { file.inputStream() }
        }
        // SAF is supported only for existing phone attachments with an actual read grant.
        require(context.checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        require(context.contentResolver.persistedUriPermissions.any { grant -> grant.isReadPermission &&
            (uri == grant.uri || uri.toString().startsWith(grant.uri.toString().trimEnd('/') + "/")) }) { "Media has no persistent grant" }
        val length = context.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use { if(it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L
        require(length >= 0) { "Media size unavailable" }
        return Resource(safeMime,length,entry.name) { context.contentResolver.openInputStream(uri) ?: error("Media unavailable") }
    }

    data class Resource(val mime: String, val length: Long, val name: String, val input: () -> InputStream)
}
