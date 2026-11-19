package com.cnsharp.yolo.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the canonical catalog merge, stage A (see Meta/meta/CATALOG_MERGE.md).
 * User-added agents are local-only and never enter this merge; they are re-applied
 * by each end's settings layer in stage B.
 */
class AgentCatalogMergeTest {

    private fun agent(id: String, status: String = "active") = AgentDef(
        id = id, displayName = id, command = id, status = status
    )

    @Test
    fun `filters outdated agents that are not installed, keeps active ones`() {
        val builtin = listOf(agent("aider"), agent("qoder"), agent("legacy-builtin"))
        val remote = listOf(
            agent("aider", "stale"),
            agent("maintain-x", "maintaining"),
            agent("closed-x", "closed"),
            agent("qodercn", "active"),
        )
        val installed = setOf("qoder", "aider") // aider installed -> kept; others not

        val ids = AgentCatalogUpdater.mergeCatalogs(builtin, remote, installed)
            .map { it.id }.toSet()

        assertTrue("aider installed -> kept", "aider" in ids)
        assertTrue("built-in with no remote entry -> kept", "legacy-builtin" in ids)
        assertTrue("active new agent -> kept", "qodercn" in ids)
        assertFalse("uninstalled+stale -> filtered", "maintain-x" in ids)
        assertFalse("uninstalled+maintaining -> filtered", "closed-x" in ids)
    }

    @Test
    fun `an outdated agent that is installed is kept`() {
        val remote = listOf(agent("aider", "stale"))
        val ids = AgentCatalogUpdater.mergeCatalogs(
            emptyList(), remote, setOf("aider")
        ).map { it.id }
        assertEquals(listOf("aider"), ids)
    }

    @Test
    fun `empty result falls back to the full builtin list`() {
        val builtin = listOf(agent("x"))
        val result = AgentCatalogUpdater.mergeCatalogs(
            builtin, listOf(agent("y", "stale")), emptySet()
        )
        assertEquals(listOf("x"), result.map { it.id })
    }

    @Test
    fun `unknown status is never filtered`() {
        val remote = listOf(agent("mystery", "something-new"))
        val result = AgentCatalogUpdater.mergeCatalogs(
            emptyList(), remote, emptySet()
        )
        assertEquals(listOf("mystery"), result.map { it.id })
    }

    // ---- v1/v2 format compatibility (long-term contract) ----

    @Test
    fun `parseCatalog accepts legacy v1 flat array`() {
        val v1 = """[{"id":"claude","displayName":"Claude Code","command":"claude","status":"active"}]"""
        val parsed = AgentRegistry.parseCatalog(v1)
        assertEquals(1, parsed.size)
        assertEquals("claude", parsed[0].id)
    }

    @Test
    fun `parseCatalog accepts v2 envelope`() {
        val v2 = """{"schemaVersion":2,"updatedAt":"2026-11-18","agents":[
            {"id":"qodercn","displayName":"Qoder CN","command":"qodercn",
             "status":"active","platformSupport":{"darwin":"supported","win32":"supported","linux":"supported"}}]}"""
        val parsed = AgentRegistry.parseCatalog(v2)
        assertEquals(1, parsed.size)
        assertEquals("qodercn", parsed[0].id)
        assertEquals("active", parsed[0].status)
    }

    @Test
    fun `parseCatalog rejects unknown schema version`() {
        val v3 = """{"schemaVersion":3,"agents":[{"id":"x","command":"x","displayName":"X"}]}"""
        assertTrue(AgentRegistry.parseCatalog(v3).isEmpty())
    }

    @Test
    fun `bundled v2 catalog parses with status fields`() {
        val stream = AgentRegistry::class.java.getResourceAsStream("/agents.json")!!
        val parsed = AgentRegistry.parseCatalog(stream.bufferedReader().use { it.readText() })
        assertEquals(51, parsed.size)
        assertTrue(parsed.any { it.id == "qodercn" && it.status == "active" })
        assertTrue(parsed.any { it.id == "aider" && it.status == "stale" })
    }
}
