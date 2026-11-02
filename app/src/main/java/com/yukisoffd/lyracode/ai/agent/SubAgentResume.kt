package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ConversationStore
import com.yukisoffd.lyracode.data.SubAgentTaskRecord

/**
 * Decides what a `run_sub_agents` call does when its parent conversation has an undelivered
 * batch: reuse finished children, run the tasks that never started, and report — never blindly
 * re-run — a write task whose child may have changed files before the app died.
 *
 * Pure by design: the caller supplies the stored rows, the tasks the model just asked for,
 * which child conversations produced a result, and which `.bak` copies exist. Everything here
 * is unit-testable without Android.
 */
internal object SubAgentResume {
    data class RequestedTask(
        val index: Int,
        val text: String,
        val readOnly: Boolean,
    )

    sealed interface Action {
        /** Re-use the stored result in this child conversation. */
        data class Reuse(val childConversationId: Long) : Action

        /** Run the task now. */
        data object Run : Action

        /** Never re-run: the child may have written files. Report the paths and any `.bak`. */
        data class InterruptedWrite(
            val writePaths: List<String>,
            val backupPaths: List<String>,
        ) : Action
    }

    data class Plan(val batchId: String, val actions: Map<Int, Action>) {
        val reusedIndices: List<Int> = actions.filterValues { it is Action.Reuse }.keys.sorted()
        val runIndices: List<Int> = actions.filterValues { it is Action.Run }.keys.sorted()
        val interruptedIndices: List<Int> = actions.filterValues { it is Action.InterruptedWrite }.keys.sorted()
    }

    /**
     * True only when the child conversation returned normally: it is idle and its last message
     * is a non-blank assistant answer.
     *
     * [com.yukisoffd.lyracode.ai.agent.OpenAiAgent.runSubAgentTask] writes RUNNING before
     * `runLoop` starts, and `runLoop` writes idle only after the final message, so a process
     * death leaves the child running or interrupted. A child killed before its first token,
     * mid-stream or inside `write_file` therefore never looks finished, and its partial text
     * is never handed back as a result. A child whose conversation no longer exists did not
     * finish either.
     */
    fun childFinished(
        conversationStatus: String?,
        lastMessageRole: String?,
        lastMessageContent: String?,
    ): Boolean = conversationStatus == ConversationStore.STATUS_IDLE &&
        lastMessageRole == "assistant" &&
        !lastMessageContent.isNullOrBlank()

    /**
     * Returns null when the stored batch is not the batch the model just asked for: a different
     * task count or different task text at any index. A null plan starts a fresh batch.
     *
     * [reusableChildIds] are child conversations that [childFinished] proved finished even
     * though their row never recorded completion, which is exactly the "finished child whose
     * result never reached the parent" case. A write task whose child is not in that set is
     * never re-run: the interrupted write beats reuse. [backupPaths] are the workspace-relative
     * `.bak` paths that exist right now.
     */
    fun plan(
        batchId: String,
        stored: List<SubAgentTaskRecord>,
        requested: List<RequestedTask>,
        reusableChildIds: Set<Long> = emptySet(),
        backupPaths: Set<String> = emptySet(),
    ): Plan? {
        val ordered = stored.sortedBy { it.index }
        if (ordered.size != requested.size) return null
        if (requested.map { it.index } != ordered.indices.toList()) return null
        if (ordered.indices.any { ordered[it].taskText.trim() != requested[it].text.trim() }) return null
        val actions = requested.associate { task ->
            val row = ordered[task.index]
            task.index to when {
                row.childConversationId > 0 && row.childConversationId in reusableChildIds ->
                    Action.Reuse(row.childConversationId)
                row.childConversationId == 0L -> Action.Run
                task.readOnly -> Action.Run
                else -> Action.InterruptedWrite(
                    writePaths = row.writePaths,
                    backupPaths = row.writePaths.map { "$it.bak" }.filter { it in backupPaths },
                )
            }
        }
        return Plan(batchId, actions)
    }
}
