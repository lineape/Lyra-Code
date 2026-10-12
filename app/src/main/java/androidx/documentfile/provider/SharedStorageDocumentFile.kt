package androidx.documentfile.provider

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.yukisoffd.lyracode.workspace.SharedStorageWorkspace
import java.io.File

/** Direct filesystem documents preserve exact tool filenames and the selected workspace boundary. */
internal class SharedStorageDocumentFile(
    private val context: Context,
    private val workspaceRoot: File,
    private var path: File = workspaceRoot,
    parent: DocumentFile? = null,
) : DocumentFile(parent) {
    private fun file(): File = SharedStorageWorkspace.file(context,Uri.fromFile(path)).also {
        require(SharedStorageWorkspace.contains(workspaceRoot.canonicalFile,it)) { "Path is outside the workspace" }
    }
    private fun child(name: String): SharedStorageDocumentFile {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) { "Invalid filename" }
        return SharedStorageDocumentFile(context,workspaceRoot,File(file(),name),this)
    }
    override fun getUri(): Uri = Uri.fromFile(file())
    override fun getName(): String = path.name
    override fun getType(): String? = if(isDirectory)null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.',"").lowercase()) ?: "application/octet-stream"
    override fun isDirectory(): Boolean = runCatching { file().isDirectory }.getOrDefault(false)
    override fun isFile(): Boolean = runCatching { file().isFile }.getOrDefault(false)
    override fun isVirtual(): Boolean = false
    override fun exists(): Boolean = runCatching { file().exists() }.getOrDefault(false)
    override fun canRead(): Boolean = runCatching { file().canRead() }.getOrDefault(false)
    override fun canWrite(): Boolean = runCatching { file().canWrite() }.getOrDefault(false)
    override fun length(): Long = runCatching { file().length() }.getOrDefault(0L)
    override fun lastModified(): Long = runCatching { file().lastModified() }.getOrDefault(0L)
    override fun findFile(displayName: String): DocumentFile? = child(displayName).takeIf { it.exists() }
    override fun listFiles(): Array<DocumentFile> = file().listFiles().orEmpty().mapNotNull {
        runCatching { child(it.name).also { document -> document.file() } }.getOrNull()
    }.toTypedArray()
    override fun createFile(mimeType: String, displayName: String): DocumentFile? = child(displayName).takeIf { it.file().createNewFile() }
    override fun createDirectory(displayName: String): DocumentFile? = child(displayName).takeIf { it.file().isDirectory || it.file().mkdir() }
    override fun renameTo(displayName: String): Boolean {
        val current=file();require(current!=workspaceRoot.canonicalFile) { "Cannot rename workspace root" }
        require(displayName.isNotBlank() && displayName !in listOf(".","..") && '/' !in displayName && '\\' !in displayName)
        val target=SharedStorageDocumentFile(context,workspaceRoot,File(current.parentFile,displayName)).file()
        if(target.exists())return false
        return current.renameTo(target).also { if(it)path=target }
    }
    override fun delete(): Boolean {
        require(file()!=workspaceRoot.canonicalFile) { "Cannot delete workspace root" }
        fun remove(document: SharedStorageDocumentFile): Boolean {
            val target=document.file()
            if(java.nio.file.Files.isSymbolicLink(document.path.toPath()))return document.path.delete()
            if(target.isDirectory && !target.listFiles().orEmpty().all { remove(document.child(it.name)) })return false
            return target.delete()
        }
        return remove(this)
    }
}
