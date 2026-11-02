package com.yukisoffd.lyracode.workspace

/**
 * Keeps the undo copy beside every text file a tool overwrites, and one copy that a later write
 * can never replace.
 *
 * An ordinary write replaces `<path>.bak` with the file's current content, so the backup always
 * holds the state before the latest write. The replacement copies to a temporary name first and
 * renames it over the old backup, so a kill during the copy cannot leave a truncated backup that
 * a later report would treat as good.
 *
 * A write task that started in an open sub-agent batch may be interrupted at any point. Its
 * `<path>.bak` is the only copy from before that write, so the batch's recovery path copies it
 * once to `<path>.interrupted.bak`, a name no ordinary write produces.
 *
 * The file operations arrive as lambdas so the policy runs in a JVM unit test;
 * [NativeFileManager] supplies the SAF implementation.
 */
internal class TextBackupStore(
    private val exists: (String) -> Boolean,
    private val copy: (source: String, target: String) -> Unit,
    private val remove: (String) -> Boolean,
    private val rename: (path: String, newName: String) -> Boolean,
) {
    /** Replaces the backup with the file's current content. */
    fun replaceBackup(path: String) {
        if (path.endsWith(BACKUP_SUFFIX, ignoreCase = true)) return
        if (!exists(path)) return
        val backup = backupPath(path)
        val temporary = temporaryPath(backup)
        // A kill here leaves the old backup intact; the truncated temporary is not a backup.
        copy(path, temporary)
        if (exists(backup) && !remove(backup)) throw IllegalStateException("无法替换备份: $backup")
        if (rename(temporary, backup.substringAfterLast('/'))) return
        // Some providers do not implement rename. The temporary is complete, so copying it over
        // the old name still replaces the backup; only a kill inside this fallback can truncate it.
        copy(temporary, backup)
        remove(temporary)
    }

    /**
     * Copies the backup to the never-replaced name, once. Returns true when this call made the
     * copy; false when there is no backup yet or the copy already exists.
     */
    fun preserveInterrupted(path: String): Boolean {
        if (path.endsWith(BACKUP_SUFFIX, ignoreCase = true)) return false
        val backup = backupPath(path)
        val preserved = interruptedBackupPath(path)
        if (exists(preserved) || !exists(backup)) return false
        val temporary = temporaryPath(preserved)
        // Complete the copy before the preserved name exists, so a kill cannot freeze a partial
        // copy under the name the report treats as good.
        copy(backup, temporary)
        if (exists(preserved)) {
            remove(temporary)
            return false
        }
        if (!rename(temporary, preserved.substringAfterLast('/'))) {
            copy(temporary, preserved)
            remove(temporary)
        }
        return true
    }

    companion object {
        const val BACKUP_SUFFIX = ".bak"
        const val INTERRUPTED_SUFFIX = ".interrupted.bak"
        const val TEMPORARY_SUFFIX = ".tmp"

        fun backupPath(path: String): String = "$path$BACKUP_SUFFIX"

        fun interruptedBackupPath(path: String): String = "$path$INTERRUPTED_SUFFIX"

        fun temporaryPath(path: String): String = "$path$TEMPORARY_SUFFIX"
    }
}
