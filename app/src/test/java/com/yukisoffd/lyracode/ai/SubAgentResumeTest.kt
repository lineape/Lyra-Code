package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.SubAgentTaskRecord
import com.yukisoffd.lyracode.data.SubAgentTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentResumeTest {
    @Test
    fun `a batch interrupted after task k reuses the finished tasks and runs the rest`() {
        val stored = listOf(
            row(0, "a", child = 101, status = SubAgentTaskStatus.COMPLETED),
            row(1, "b", child = 102, status = SubAgentTaskStatus.COMPLETED),
            row(2, "c", child = 103, status = SubAgentTaskStatus.COMPLETED),
            row(3, "d", child = 104, status = SubAgentTaskStatus.RUNNING),
            row(4, "e"),
            row(5, "f"),
        )

        val plan = SubAgentResume.plan(
            batchId = "batch-1",
            stored = stored,
            requested = requested("a" to true, "b" to true, "c" to true, "d" to true, "e" to true, "f" to true),
            reusableChildIds = setOf(101, 102, 103),
        )!!

        assertEquals(listOf(0, 1, 2), plan.reusedIndices)
        assertEquals(listOf(3, 4, 5), plan.runIndices)
        assertEquals(SubAgentResume.Action.Reuse(101), plan.actions[0])
        assertEquals(SubAgentResume.Action.Run, plan.actions[3])
    }

    @Test
    fun `a finished child whose result never reached the parent is reused`() {
        val stored = listOf(row(0, "research", child = 300, status = SubAgentTaskStatus.RUNNING))

        val plan = SubAgentResume.plan(
            batchId = "batch-2",
            stored = stored,
            requested = requested("research" to true),
            reusableChildIds = setOf(300),
        )!!

        assertEquals(SubAgentResume.Action.Reuse(300), plan.actions[0])
        assertTrue(plan.runIndices.isEmpty())
    }

    @Test
    fun `an interrupted write task is reported with its paths and backups, not run again`() {
        val stored = listOf(
            row(0, "edit", writePaths = listOf("src/A.kt"), child = 204, status = SubAgentTaskStatus.RUNNING),
            row(1, "read", child = 205, status = SubAgentTaskStatus.RUNNING),
        )

        val plan = SubAgentResume.plan(
            batchId = "batch-3",
            stored = stored,
            requested = requested("edit" to false, "read" to true),
            reusableChildIds = setOf(205),
            backupPaths = setOf("src/A.kt.bak"),
        )!!

        val interrupted = plan.actions[0] as SubAgentResume.Action.InterruptedWrite
        assertEquals(listOf("src/A.kt"), interrupted.writePaths)
        assertEquals(listOf("src/A.kt.bak"), interrupted.backupPaths)
        assertEquals(SubAgentResume.Action.Reuse(205), plan.actions[1])
        assertEquals(listOf(0), plan.interruptedIndices)
        assertTrue(plan.runIndices.isEmpty())
    }

    @Test
    fun `an interrupted write task with no backup still names its paths`() {
        val stored = listOf(
            row(0, "edit", writePaths = listOf("src/A.kt", "src/B.kt"), child = 204, status = SubAgentTaskStatus.RUNNING),
        )

        val plan = SubAgentResume.plan(
            batchId = "batch-4",
            stored = stored,
            requested = requested("edit" to false),
            backupPaths = setOf("src/B.kt.bak"),
        )!!

        val interrupted = plan.actions[0] as SubAgentResume.Action.InterruptedWrite
        assertEquals(listOf("src/A.kt", "src/B.kt"), interrupted.writePaths)
        assertEquals(listOf("src/B.kt.bak"), interrupted.backupPaths)
    }

    @Test
    fun `a write task that never started is safe to run`() {
        val stored = listOf(row(0, "edit", writePaths = listOf("src/A.kt"), status = SubAgentTaskStatus.PENDING))

        val plan = SubAgentResume.plan(
            batchId = "batch-5",
            stored = stored,
            requested = requested("edit" to false),
        )!!

        assertEquals(SubAgentResume.Action.Run, plan.actions[0])
    }

    @Test
    fun `a completed write task is reused without touching its paths`() {
        val stored = listOf(
            row(0, "edit", writePaths = listOf("src/A.kt"), child = 401, status = SubAgentTaskStatus.COMPLETED),
        )

        val plan = SubAgentResume.plan(
            batchId = "batch-6",
            stored = stored,
            requested = requested("edit" to false),
            reusableChildIds = setOf(401),
        )!!

        assertEquals(SubAgentResume.Action.Reuse(401), plan.actions[0])
    }

    @Test
    fun `a completed write task whose child is gone is interrupted, not reused with an empty result`() {
        val stored = listOf(
            row(0, "edit", writePaths = listOf("src/A.kt"), child = 401, status = SubAgentTaskStatus.COMPLETED),
        )

        val plan = SubAgentResume.plan(
            batchId = "batch-6b",
            stored = stored,
            requested = requested("edit" to false),
        )!!

        assertEquals(SubAgentResume.Action.InterruptedWrite(listOf("src/A.kt"), emptyList()), plan.actions[0])
    }

    @Test
    fun `a child killed before it finished is not reusable, so its write is reported instead`() {
        val stored = listOf(
            row(0, "edit", writePaths = listOf("src/A.kt"), child = 501, status = SubAgentTaskStatus.RUNNING),
        )

        val plan = SubAgentResume.plan(
            batchId = "batch-6c",
            stored = stored,
            requested = requested("edit" to false),
            // The old childProducedResult would have put this id here for idle + assistant.
            reusableChildIds = emptySet(),
            backupPaths = setOf("src/A.kt.bak"),
        )!!

        assertEquals(
            SubAgentResume.Action.InterruptedWrite(listOf("src/A.kt"), listOf("src/A.kt.bak")),
            plan.actions[0],
        )
    }

    @Test
    fun `a child that is idle with a non-blank assistant answer has finished`() {
        assertTrue(SubAgentResume.childFinished("idle", "assistant", "the answer"))
    }

    @Test
    fun `a child that is running with partial text has not finished`() {
        assertEquals(false, SubAgentResume.childFinished("running", "assistant", "half an ans"))
    }

    @Test
    fun `a child killed before its first token has not finished`() {
        assertEquals(false, SubAgentResume.childFinished("running", "assistant", ""))
    }

    @Test
    fun `a child whose last message is a tool result has not finished`() {
        assertEquals(false, SubAgentResume.childFinished("idle", "tool", "tool output"))
    }

    @Test
    fun `a child that is idle but blank has not finished`() {
        assertEquals(false, SubAgentResume.childFinished("idle", "assistant", " "))
        assertEquals(false, SubAgentResume.childFinished("idle", "assistant", null))
    }

    @Test
    fun `a child whose conversation is gone has not finished`() {
        assertEquals(false, SubAgentResume.childFinished(null, null, null))
    }

    @Test
    fun `a child interrupted when its run was cancelled has not finished`() {
        assertEquals(false, SubAgentResume.childFinished("interrupted", "assistant", "part"))
    }

    @Test
    fun `different task text does not resume the stored batch`() {
        val stored = listOf(row(0, "old plan", child = 501, status = SubAgentTaskStatus.COMPLETED))

        assertNull(
            SubAgentResume.plan(
                batchId = "batch-7",
                stored = stored,
                requested = requested("new plan" to true),
            ),
        )
    }

    @Test
    fun `a different task count does not resume the stored batch`() {
        val stored = listOf(row(0, "a", child = 501, status = SubAgentTaskStatus.COMPLETED))

        assertNull(
            SubAgentResume.plan(
                batchId = "batch-8",
                stored = stored,
                requested = requested("a" to true, "b" to true),
            ),
        )
    }

    private fun row(
        index: Int,
        text: String,
        writePaths: List<String> = emptyList(),
        child: Long = 0L,
        status: String = SubAgentTaskStatus.PENDING,
    ) = SubAgentTaskRecord(
        index = index,
        taskText = text,
        writePaths = writePaths,
        childConversationId = child,
        status = status,
    )

    private fun requested(vararg tasks: Pair<String, Boolean>): List<SubAgentResume.RequestedTask> =
        tasks.mapIndexed { index, (text, readOnly) -> SubAgentResume.RequestedTask(index, text, readOnly) }
}
