package com.yukisoffd.lyracode.ai

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/** One finished child task: its value, or the error that stopped it. */
internal sealed interface SubAgentCallOutcome<out T> {
    data class Completed<T>(val value: T) : SubAgentCallOutcome<T>
    data class Failed(val error: Throwable) : SubAgentCallOutcome<Nothing>
}

/**
 * Runs child tasks with at most [maxConcurrency] in flight.
 *
 * Contract:
 * - Children are dispatched in task order at every limit; a limit of 1 runs them strictly one
 *   at a time, so a task may rely on an earlier task's writes.
 * - Results keep task order, whatever order the tasks finish in.
 * - A child failure is returned at its own index and does not stop its siblings.
 * - Cancelling the caller's job cancels every running child, because all of them are children
 *   of the caller's coroutine scope. A child that throws [CancellationException] on its own is
 *   reported as that child's failure and does not cancel its siblings.
 */
internal class SubAgentBatchRunner(private val maxConcurrency: Int) {
    suspend fun <T> run(count: Int, block: suspend (index: Int) -> T): List<SubAgentCallOutcome<T>> {
        require(count >= 0) { "Sub-agent task count must not be negative." }
        if (count == 0) return emptyList()
        val permits = maxConcurrency.coerceAtLeast(1)
        return coroutineScope {
            val semaphore = Semaphore(permits)
            val outcomes = arrayOfNulls<SubAgentCallOutcome<T>>(count)
            // Acquire before each launch, in task order, so children are dispatched in task
            // order at every limit and a limit of 1 is strictly sequential. A cancelled
            // acquire throws here; coroutineScope then cancels the children already launched.
            val jobs = (0 until count).map { index ->
                semaphore.acquire()
                launch {
                    try {
                        outcomes[index] = try {
                            SubAgentCallOutcome.Completed(block(index))
                        } catch (cancellation: CancellationException) {
                            // Parent cancellation must unwind the whole scope; a child that stops
                            // alone reports at its own index so its siblings keep their results.
                            if (!currentCoroutineContext().isActive) throw cancellation
                            SubAgentCallOutcome.Failed(cancellation)
                        } catch (error: Throwable) {
                            SubAgentCallOutcome.Failed(error)
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }
            jobs.forEach { it.join() }
            outcomes.mapIndexed { index, outcome ->
                outcome ?: SubAgentCallOutcome.Failed(
                    IllegalStateException("Sub-agent task ${index + 1} did not start."),
                )
            }
        }
    }
}

/**
 * Progress of one batch, written by every child coroutine and read when a status line is built.
 * Counts are atomic, so a status line never sees a half-updated tally.
 */
internal class SubAgentBatchProgress {
    private val completed = AtomicInteger(0)
    private val running = ConcurrentHashMap<Int, String>()

    fun started(index: Int, agentName: String) {
        running[index] = agentName
    }

    fun finished(index: Int) {
        running.remove(index)
        completed.incrementAndGet()
    }

    val completedCount: Int get() = completed.get()

    val runningCount: Int get() = running.size
}
