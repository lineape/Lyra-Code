package com.yukisoffd.lyracode.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentLimitsTest {
    @Test
    fun `default concurrency keeps the sequential behaviour`() {
        assertEquals(1, SubAgentLimits.DEFAULT_MAX_CONCURRENCY)
        assertEquals(6, SubAgentLimits.DEFAULT_MAX_TASKS)
    }

    @Test
    fun `the tool schema advertises the same limit the runtime enforces`() {
        val limit = SubAgentLimits.normalizeTaskLimit(4)

        assertEquals(limit, schemaMaxItems(limit))
        assertEquals(SubAgentLimits.BatchSize.Accepted(4), SubAgentLimits.evaluateBatchSize(4, limit))
        assertEquals(SubAgentLimits.BatchSize.Rejected(5, 4), SubAgentLimits.evaluateBatchSize(5, limit))
    }

    @Test
    fun `the schema clamps an out of range limit instead of advertising it`() {
        assertEquals(SubAgentLimits.HARD_MAX_TASKS, schemaMaxItems(99))
        assertEquals(1, schemaMaxItems(0))
    }

    @Test
    fun `effective concurrency is the configured value capped to the batch size`() {
        assertEquals(1, SubAgentLimits.effectiveConcurrency(1, 6))
        assertEquals(3, SubAgentLimits.effectiveConcurrency(3, 6))
        assertEquals(6, SubAgentLimits.effectiveConcurrency(9, 6))
        assertEquals(1, SubAgentLimits.effectiveConcurrency(9, 1))
        assertEquals(1, SubAgentLimits.effectiveConcurrency(0, 6))
        assertEquals(1, SubAgentLimits.effectiveConcurrency(5, 0))
    }

    @Test
    fun `a batch over the limit is rejected rather than truncated`() {
        val size = SubAgentLimits.evaluateBatchSize(9, 6)

        assertEquals(SubAgentLimits.BatchSize.Rejected(9, 6), size)
        assertTrue(size is SubAgentLimits.BatchSize.Rejected)
    }

    @Test
    fun `limits survive an export and import round trip`() {
        val exported = JSONObject()
        SubAgentLimits.writeExport(exported, maxTasks = 4, maxConcurrency = 3)

        assertEquals(SubAgentLimits.Imported(4, 3), SubAgentLimits.readImport(exported))
    }

    @Test
    fun `import clamps concurrency to the task limit and both to the legal range`() {
        val imported = SubAgentLimits.readImport(
            JSONObject()
                .put(SubAgentLimits.KEY_MAX_TASKS, 40)
                .put(SubAgentLimits.KEY_MAX_CONCURRENCY, 30),
        )

        assertEquals(SubAgentLimits.Imported(SubAgentLimits.HARD_MAX_TASKS, SubAgentLimits.HARD_MAX_TASKS), imported)
    }

    @Test
    fun `an export from before these keys changes nothing`() {
        assertNull(SubAgentLimits.readImport(JSONObject().put("subAgentOrchestrationEnabled", true)))
    }

    private fun schemaMaxItems(limit: Int): Int =
        buildSubAgentFunction(limit)
            .getJSONObject("function")
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("tasks")
            .getInt("maxItems")
}
