package com.cnsharp.yolo.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the canonical catalog merge, stage A (see Meta/meta/CATALOG_MERGE.md),
 * plus the OS filter and the v2 field plumbing (`platformSupport` / `lastReleaseAt`).
 * User-added agents are local-only and never enter this merge; they are re-applied
 * by each end's settings layer in stage B.
 */
class AgentCatalogMergeTest {

    private fun agent(id: String, status: String = "active") = AgentDef(
        id = id, displayName = id, command = id, status = status
    )

    @Test
    fun `closed is the only status filtered when not installed`() {
        val builtin = listOf(agent("legacy-builtin"))
        val remote = listOf(
            agent("aider", "stale"),
            agent("maintain-x", "maintaining"),
            agent("closed-x", "closed"),
            agent("qodercn", "active"),
        )
        val installed = setOf("aider") // aider installed -> kept anyway

        val ids = AgentCatalogUpdater.mergeCatalogs(builtin, remote, installed)
            .map { it.id }.toSet()

        assertTrue("built-in with no remote entry -> kept", "legacy-builtin" in ids)
        assertTrue("stale is not filtered", "aider" in ids)
        assertTrue("maintaining is not filtered", "maintain-x" in ids)
        assertTrue("active new agent -> kept", "qodercn" in ids)
        assertFalse("uninstalled + closed -> filtered", "closed-x" in ids)
    }

    @Test
    fun `a closed agent that is installed is kept`() {
        val remote = listOf(agent("aider", "closed"))
        val ids = AgentCatalogUpdater.mergeCatalogs(
            emptyList(), remote, setOf("aider")
        ).map { it.id }
        assertEquals(listOf("aider"), ids)
    }

    @Test
    fun `empty result falls back to the full builtin list`() {
        val builtin = listOf(agent("x"))
        val result = AgentCatalogUpdater.mergeCatalogs(
            builtin, listOf(agent("y", "closed")), emptySet()
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

    @Test
    fun `terminal state set contains only closed`() {
        assertEquals(setOf("closed"), TERMINAL_STATUSES)
    }

    // ---- OS filter (Meta/AGENTS.md §4, CATALOG_MERGE.md guards) ----

    @Test
    fun `OS filter drops only explicit unsupported`() {
        val def = AgentDef(
            id = "x", displayName = "X", command = "x",
            platformSupport = PlatformSupport(darwin = "supported", win32 = "unsupported", linux = "viaWsl")
        )
        assertTrue(AgentRegistry.isSupportedOnThisOs(def, "darwin"))
        assertFalse(AgentRegistry.isSupportedOnThisOs(def, "win32"))
        assertTrue("viaWsl is kept", AgentRegistry.isSupportedOnThisOs(def, "linux"))
    }

    @Test
    fun `OS filter keeps an entry with no platformSupport`() {
        val def = agent("x") // legacy v1 / user override
        assertTrue(AgentRegistry.isSupportedOnThisOs(def, "win32"))
        assertTrue(AgentRegistry.isSupportedOnThisOs(def, "darwin"))
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
    fun `parseCatalog accepts v2 envelope and carries new fields`() {
        val v2 = """{"schemaVersion":2,"updatedAt":"2026-11-19","agents":[
            {"id":"qodercn","displayName":"Qoder CN","command":"qodercn",
             "status":"active","lastReleaseAt":"2026-11-18T23:09:39Z",
             "platformSupport":{"darwin":"supported","win32":"supported","linux":"supported"}}]}"""
        val parsed = AgentRegistry.parseCatalog(v2)
        assertEquals(1, parsed.size)
        assertEquals("qodercn", parsed[0].id)
        assertEquals("active", parsed[0].status)
        assertEquals("2026-11-18T23:09:39Z", parsed[0].lastReleaseAt)
        assertEquals("supported", parsed[0].platformSupport?.win32)
    }

    @Test
    fun `parseCatalog rejects unknown schema version`() {
        val v3 = """{"schemaVersion":3,"agents":[{"id":"x","command":"x","displayName":"X"}]}"""
        assertTrue(AgentRegistry.parseCatalog(v3).isEmpty())
    }

    @Test
    fun `bundled v2 catalog parses with status and lastReleaseAt fields`() {
        val stream = AgentRegistry::class.java.getResourceAsStream("/agents.json")!!
        val parsed = AgentRegistry.parseCatalog(stream.bufferedReader().use { it.readText() })
        assertEquals(51, parsed.size)
        assertTrue(parsed.any { it.id == "qodercn" && it.status == "active" })
        assertTrue(parsed.any { it.id == "aider" && it.status == "stale" })
        assertTrue("lastReleaseAt was collected", parsed.any { it.lastReleaseAt.isNotBlank() })
        assertTrue("platformSupport is parsed", parsed.all { it.platformSupport != null })
    }
}
