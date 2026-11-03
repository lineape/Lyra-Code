package com.yukisoffd.lyracode.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TextBackupStoreTest {
    private class SimulatedKill : RuntimeException("process died mid-copy")

    /** In-memory workspace: a copy can be told to write half the content and then die. */
    private class FakeWorkspace {
        val content = linkedMapOf<String, String>()
        var killOnNextCopy = false
        var renameSupported = true
        var replaceSupported = true
        var failRenameFrom: String? = null
        var killOnCopyTo: String? = null

        fun store(): TextBackupStore = TextBackupStore(
            exists = { path -> path in content },
            copy = { source, target ->
                val moving = content[source] ?: throw NoSuchElementException(source)
                if (killOnNextCopy || target == killOnCopyTo) {
                    killOnNextCopy = false
                    content[target] = moving.take(1)
                    throw SimulatedKill()
                }
                content[target] = moving
            },
            remove = { path -> content.remove(path) != null },
            rename = { path, newName ->
                if (!renameSupported || path == failRenameFrom) return@TextBackupStore false
                val directory = path.substringBeforeLast('/', missingDelimiterValue = "")
                val target = if (directory.isEmpty()) newName else "$directory/$newName"
                if (!replaceSupported && target in content) return@TextBackupStore false
                val moved = content.remove(path) ?: return@TextBackupStore false
                content[target] = moved
                true
            },
        )
    }

    @Test
    fun `an ordinary write replaces the backup with the current content`() {
        val files = FakeWorkspace()
        val store = files.store()
        files.content["notes.md"] = "first"
        store.replaceBackup("notes.md")
        assertEquals("first", files.content["notes.md.bak"])

        files.content["notes.md"] = "second"
        store.replaceBackup("notes.md")

        // The second write keeps a backup of the state before it, not the first backup for good.
        assertEquals("second", files.content["notes.md.bak"])
    }

    @Test
    fun `a kill during the copy leaves the previous backup whole`() {
        val files = FakeWorkspace()
        val store = files.store()
        files.content["notes.md"] = "first"
        store.replaceBackup("notes.md")
        files.content["notes.md"] = "second"
        files.killOnNextCopy = true

        try {
            store.replaceBackup("notes.md")
            fail("the simulated kill must reach the caller")
        } catch (expected: SimulatedKill) {
            // expected
        }

        // The truncated temporary is never the backup the report treats as good.
        assertEquals("first", files.content["notes.md.bak"])
        assertEquals("s", files.content["notes.md.bak.tmp"])
        assertTrue(files.store().preserveInterrupted("notes.md", "batch-1"))
        assertEquals("first", files.content["notes.md.batch1.interrupted.bak"])
    }

    @Test
    fun `writing a path that does not exist yet makes no backup`() {
        val files = FakeWorkspace()

        files.store().replaceBackup("created.md")

        // A file the tool is about to create has no previous content to keep.
        assertFalse(files.content.containsKey("created.md.bak"))
    }

    @Test
    fun `a backup is never itself backed up`() {
        val files = FakeWorkspace()
        files.content["notes.md.bak"] = "old"

        files.store().replaceBackup("notes.md.bak")

        assertNull(files.content["notes.md.bak.bak"])
    }

    @Test
    fun `an interrupted write keeps the backup no later write can replace`() {
        val files = FakeWorkspace()
        val store = files.store()
        files.content["notes.md"] = "before the task"
        store.replaceBackup("notes.md")

        // The child dies halfway through its write and the process restarts.
        files.content["notes.md"] = "half"
        assertTrue(store.preserveInterrupted("notes.md", "batch-1"))
        assertEquals("before the task", files.content["notes.md.batch1.interrupted.bak"])

        // The parent then edits the file: the ordinary backup is replaced by the state before
        // that edit, while the preserved copy still holds the state from before the task.
        files.content["notes.md"] = "half, edited"
        store.replaceBackup("notes.md")
        assertEquals("half, edited", files.content["notes.md.bak"])
        assertEquals("before the task", files.content["notes.md.batch1.interrupted.bak"])
    }

    @Test
    fun `preserving twice in one batch keeps the first copy`() {
        val files = FakeWorkspace()
        val store = files.store()
        files.content["notes.md"] = "original"
        store.replaceBackup("notes.md")

        assertTrue(store.preserveInterrupted("notes.md", "batch-1"))
        files.content["notes.md.bak"] = "newer"
        assertFalse(store.preserveInterrupted("notes.md", "batch-1"))

        assertEquals("original", files.content["notes.md.batch1.interrupted.bak"])
    }

    /**
     * The store cannot tell a retry from a new interruption, so the copy is named for its batch:
     * a later interruption of the same path must keep the state it interrupted, not find the
     * earlier batch's copy already in place and leave it frozen.
     */
    @Test
    fun `a second interruption in a later batch keeps its own copy`() {
        val files = FakeWorkspace()
        val store = files.store()
        files.content["notes.md"] = "v1"
        store.replaceBackup("notes.md")
        assertTrue(store.preserveInterrupted("notes.md", "first-batch"))

        // Good work follows, and then the path is interrupted again. The state to keep from the
        // second interruption is the one its own write found, not the first batch's copy.
        files.content["notes.md"] = "v4-good"
        store.replaceBackup("notes.md")
        assertTrue(store.preserveInterrupted("notes.md", "second-batch"))

        assertEquals("v1", files.content["notes.md.firstbat.interrupted.bak"])
        assertEquals("v4-good", files.content["notes.md.secondba.interrupted.bak"])
    }

    @Test
    fun `unsupported rename retains the previous backup`() {
        val files = FakeWorkspace()
        files.content["notes.md"] = "current"
        files.content["notes.md.bak"] = "previous"
        files.renameSupported = false

        try {
            files.store().replaceBackup("notes.md")
            fail("publication must fail")
        } catch (expected: IllegalStateException) {
            // The caller must not overwrite the original.
        }
        assertEquals("previous", files.content["notes.md.bak"])
        assertEquals("current", files.content["notes.md"])
    }

    @Test
    fun `failed publication leaves the previous backup and permits retry`() {
        val files = FakeWorkspace()
        files.content["notes.md"] = "current"
        files.content["notes.md.bak"] = "previous"
        files.failRenameFrom = "notes.md.bak.tmp"

        try {
            files.store().replaceBackup("notes.md")
            fail("publication must fail")
        } catch (expected: IllegalStateException) {
            // expected
        }
        assertEquals("previous", files.content["notes.md.bak"])
        files.failRenameFrom = null
        files.store().replaceBackup("notes.md")
        assertEquals("current", files.content["notes.md.bak"])
    }

    @Test
    fun `unsupported replacement leaves backup and similarly named user file intact`() {
        val files = FakeWorkspace()
        files.content["notes.md"] = "current"
        files.content["notes.md.bak"] = "previous"
        files.content["notes.md.bak.previous"] = "user file"
        files.replaceSupported = false

        try {
            files.store().replaceBackup("notes.md")
            fail("publication must fail")
        } catch (expected: IllegalStateException) {
            // expected
        }
        assertEquals("previous", files.content["notes.md.bak"])
        assertEquals("user file", files.content["notes.md.bak.previous"])
        assertEquals("current", files.content["notes.md"])
    }

    @Test
    fun `a partial temporary without a backup is never preserved`() {
        val files = FakeWorkspace()
        files.content["notes.md.bak.tmp"] = "partial"

        assertFalse(files.store().preserveInterrupted("notes.md", "batch-1"))
        assertNull(files.content["notes.md.batch1.interrupted.bak"])
    }

    @Test
    fun `unsupported rename cannot publish a partial preserved copy`() {
        val files = FakeWorkspace()
        files.content["notes.md.bak"] = "original"
        files.renameSupported = false
        files.killOnCopyTo = "notes.md.batch1.interrupted.bak"

        try {
            files.store().preserveInterrupted("notes.md", "batch-1")
            fail("publication must fail")
        } catch (expected: RuntimeException) {
            // No final copy may be trusted after the failure.
        }

        assertNull(files.content["notes.md.batch1.interrupted.bak"])
        assertEquals("original", files.content["notes.md.bak"])
        files.renameSupported = true
        assertTrue(files.store().preserveInterrupted("notes.md", "batch-1"))
    }

    @Test
    fun `a kill during preservation leaves no partial preserved copy`() {
        val files = FakeWorkspace()
        val store = files.store()
        files.content["notes.md"] = "original"
        store.replaceBackup("notes.md")
        files.killOnNextCopy = true

        try {
            store.preserveInterrupted("notes.md", "batch-1")
            fail("the simulated kill must reach the caller")
        } catch (expected: SimulatedKill) {
            // expected
        }

        assertNull(files.content["notes.md.batch1.interrupted.bak"])
        assertEquals("original", files.content["notes.md.bak"])
    }
}
