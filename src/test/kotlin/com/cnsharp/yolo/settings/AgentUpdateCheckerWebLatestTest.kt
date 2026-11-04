package com.cnsharp.yolo.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentUpdateCheckerWebLatestTest {

    /** junie's real `web` source pattern (decoded from agents.json). */
    private val juniePattern = "\"version\"\\s*:\\s*\"([^\"]+)\""

    @Test
    fun `junie returns the highest semver, not the last line`() {
        // The update-info.jsonl lists many "version" entries; the newest build is the highest
        // number, which is NOT last in the file. The old end-of-input lookahead only ever matched
        // the final line, so updates were silently missed.
        val html = """
            {"version": "888.12", "channel": "stable"}
            {"version": "3893.6", "channel": "stable"}
            {"version": "1000.0", "channel": "stable"}
        """.trimIndent()
        assertEquals("3893.6", AgentUpdateChecker.parseWebLatest(html, juniePattern))
    }

    @Test
    fun `returns the highest semver across many matches`() {
        val html = "v1.2.3 v1.10.0 v2.0.0 v0.9.9"
        assertEquals("2.0.0", AgentUpdateChecker.parseWebLatest(html, "v([\\d.]+)"))
    }

    @Test
    fun `compares patch numbers numerically not lexically`() {
        // Lexical order would pick "1.9.0" over "1.10.0"; semver order must pick "1.10.0".
        val html = "rel-1.9.0 rel-1.10.0 rel-1.2.0"
        assertEquals("1.10.0", AgentUpdateChecker.parseWebLatest(html, "rel-([\\d.]+)"))
    }

    @Test
    fun `returns the only version when there is a single match`() {
        val html = "release 3.4.5 is out"
        assertEquals("3.4.5", AgentUpdateChecker.parseWebLatest(html, "([\\d.]+)"))
    }

    @Test
    fun `returns empty string when nothing matches`() {
        val html = "no version here"
        assertEquals("", AgentUpdateChecker.parseWebLatest(html, "(\\d+\\.\\d+\\.\\d+)"))
    }

    @Test
    fun `non-semver versions fall back to the first match`() {
        // "2024-01" does not parse as semver, so the first capture wins (preserves old behavior
        // for date/build-id style schemes).
        val html = "id-2024-01 id-2025-02"
        assertEquals("2024-01", AgentUpdateChecker.parseWebLatest(html, "id-([\\w-]+)"))
    }

    @Test
    fun `uses the captured group not the whole match`() {
        val html = "prefix X1.0.0Y suffix"
        assertEquals("1.0.0", AgentUpdateChecker.parseWebLatest(html, "X([\\d.]+)Y"))
    }
}
