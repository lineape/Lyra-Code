package com.yukisoffd.lyracode.workspace

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** Direct shared-storage workspaces selected by the web client, without an SAF activity. */
internal object SharedStorageWorkspace {
    fun permitted(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    fun roots(context: Context): List<File> {
        if (!permitted()) return emptyList()
        val volumes = context.getSystemService(StorageManager::class.java).storageVolumes
        return (listOf(Environment.getExternalStorageDirectory()) + volumes.mapNotNull { it.directory })
            .map { it.canonicalFile }.distinct().filter { it.isDirectory && it.canRead() }
    }

    fun file(context: Context, uri: Uri): File {
        require(permitted()) { "请先在手机授予管理所有文件权限" }
        require(uri.scheme == "file" && uri.authority.isNullOrEmpty()) { "请选择共享存储目录" }
        val candidate = File(requireNotNull(uri.path)).canonicalFile
        val root = requireNotNull(roots(context).firstOrNull { contains(it, candidate) }) { "路径不在共享存储中" }
        val relative = candidate.relativeTo(root).invariantSeparatorsPath
        require(relative != "Android/data" && !relative.startsWith("Android/data/") &&
            relative != "Android/obb" && !relative.startsWith("Android/obb/")) { "此 Android 目录不可访问" }
        return candidate
    }

    fun directory(context: Context, uri: Uri): File = file(context, uri).also {
        require(it.isDirectory && it.canRead()) { "目录不可访问" }
    }

    fun contains(root: File, candidate: File): Boolean =
        candidate == root || candidate.path.startsWith(root.path + File.separator)
}
