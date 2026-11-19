package com.cnsharp.yolo.settings

import com.google.gson.Gson
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern

/**
 * Startup catalog hot-update for the IntelliJ edition. Fetches the official v2
 * agent catalog, merges it with the bundled catalog (dropping agents that are
 * both outdated and not installed locally), replaces [AgentRegistry]'s live
 * snapshot, and caches the result under the IDE config dir so the editor works
 * offline. The source URL is locked to the official repo — no user-configurable
 * endpoint (a malicious catalog could carry install commands that get executed).
 *
 * Mirrors agentCatalog.ts on the VS Code side; keep the merge rules in sync with
 * Meta/meta/CATALOG_MERGE.md.
 */
object AgentCatalogUpdater {
    private val LOG = Logger.getInstance(AgentCatalogUpdater::class.java)

    private const val CATALOG_URL =
        "https://raw.githubusercontent.com/cnsharp/Agent-YOLO/main/meta/agents.json"
    private const val ICON_BASE_URL =
        "https://raw.githubusercontent.com/cnsharp/Agent-YOLO/main/resources/icons/agents/"
    private const val ETAG_KEY = "yolo.catalogEtag"
    private const val TIMEOUT_MS = 20_000
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024

    private val OUTDATED = setOf("stale", "maintaining", "closed")
    private val KNOWN_STATUS = setOf("active", "maintaining", "stale", "closed", "unknown")
    private val ID_RE = Pattern.compile("[a-z][a-z0-9-]*")
    private val ICON_RE = Pattern.compile("[A-Za-z0-9._-]+\\.(svg|png)")

    private val configDir: Path get() = Paths.get(PathManager.getConfigPath(), "yolo")
    private val cacheFile: File get() = configDir.resolve("agents.remote.json").toFile()
    private val iconDir: File get() = configDir.resolve("icons").resolve("agents").toFile()

    private fun canonStatus(s: String): String = if (s in KNOWN_STATUS) s else "unknown"
    private fun idOf(a: AgentDef): String = a.id.ifBlank { a.command }

    private fun isOutdatedAndUninstalled(a: AgentDef, installed: Set<String>): Boolean =
        a.command.lowercase() !in installed && canonStatus(a.status) in OUTDATED

    /**
     * Canonical merge, stage A (see Meta/meta/CATALOG_MERGE.md): operate only on the
     * two official catalogs. User-added agents are local-only (Settings layer, stage B)
     * and never enter here, so they cannot be touched by the outdated filter.
     */
    fun mergeCatalogs(
        builtin: List<AgentDef>,
        remote: List<AgentDef>,
        installed: Set<String>
    ): List<AgentDef> {
        val result = LinkedHashMap<String, AgentDef>()
        for (b in builtin) result[idOf(b)] = b
        for (e in remote) {
            val id = idOf(e)
            if (isOutdatedAndUninstalled(e, installed)) {
                result.remove(id)
                continue
            }
            result[id] = e
        }
        return if (result.isEmpty()) builtin.toList() else result.values.toList()
    }

    /** Load the last-good merged catalog from disk (instant offline display). */
    fun loadCached() {
        try {
            if (!cacheFile.isFile) return
            val parsed = AgentRegistry.parseCatalog(cacheFile.readText(StandardCharsets.UTF_8))
            if (parsed.isNotEmpty()) AgentRegistry.replaceAll(parsed)
        } catch (e: Exception) {
            LOG.warn("AgentCatalogUpdater: failed to read cached catalog", e)
        }
    }

    /**
     * Fetch+merge the remote catalog into the live registry. Blocks on network I/O, so the caller
     * must invoke it off the EDT (see [AgentExtenderSettings.ensureSyncScheduled], which runs this
     * before probing installed commands so detection sees the final catalog).
     */
    fun refreshBlocking() = refresh()

    private data class Fetch(val text: String, val etag: String?)

    private fun fetch(etag: String?): Fetch? = try {
        val conn = URL(CATALOG_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "agentyolo")
        if (etag != null) conn.setRequestProperty("If-None-Match", etag)
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        when (val code = conn.responseCode) {
            304 -> { conn.disconnect(); null } // not modified
            in 200..299 -> {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val responseEtag = conn.getHeaderField("ETag")
                conn.disconnect()
                if (body.toByteArray(StandardCharsets.UTF_8).size > MAX_BODY_BYTES) null
                else Fetch(body, responseEtag)
            }
            else -> { conn.disconnect(); null }
        }
    } catch (e: Exception) {
        LOG.warn("AgentCatalogUpdater: catalog fetch failed", e)
        null
    }

    private fun validate(list: List<AgentDef>): List<AgentDef> =
        list.filter {
            it.id.isNotBlank() && ID_RE.matcher(it.id).matches() &&
                it.command.isNotBlank() && it.displayName.isNotBlank() &&
                (it.icon.isBlank() || ICON_RE.matcher(it.icon).matches())
        }.take(500)

    private fun refresh() {
        val props = PropertiesComponent.getInstance()
        val result = fetch(props.getValue(ETAG_KEY)) ?: return

        val remote = validate(AgentRegistry.parseCatalog(result.text))
        if (remote.isEmpty()) return
        val builtin = AgentRegistry.bundled
        if (builtin.isEmpty()) return

        // Installed set: prefer the persisted cache; if it is empty probe the
        // union live via AgentDetector (cache-backed), lower-cased.
        var installed = AgentExtenderSettings.getInstance().state.installedCommands
            .map { it.lowercase() }.toSet()
        if (installed.isEmpty()) {
            installed = (builtin.asSequence() + remote.asSequence())
                .map { it.command }.distinct()
                .filter { AgentDetector.canExecute(it) }
                .map { it.lowercase() }.toSet()
        }

        val merged = mergeCatalogs(builtin, remote, installed)
        if (merged.isEmpty()) return

        // Persist last-good snapshot + etag (best effort).
        try {
            FileUtil.createParentDirs(cacheFile)
            cacheFile.writeText(Gson().toJson(merged.map { it.toJsonDef() }), StandardCharsets.UTF_8)
            result.etag?.let { props.setValue(ETAG_KEY, it) }
        } catch (e: Exception) {
            LOG.warn("AgentCatalogUpdater: failed to cache catalog", e)
        }

        AgentRegistry.replaceAll(merged)
        downloadMissingIcons(merged)
        // Deliberately no fireChanged() here: the startup sequence publishes once after it has also
        // probed installed commands, so the UI never renders against a catalog/installed mismatch.
    }

    /** Download icons not bundled and not yet cached, into <config>/yolo/icons/agents. */
    private fun downloadMissingIcons(agents: List<AgentDef>) {
        try {
            FileUtil.createDirectory(iconDir)
        } catch (e: Exception) {
            return
        }
        for (a in agents) {
            val icon = a.icon
            if (icon.isBlank() || !ICON_RE.matcher(icon).matches()) continue
            val target = File(iconDir, icon)
            if (target.isFile) continue
            if (AgentRegistry::class.java.getResource("/icons/agents/$icon") != null) continue
            try {
                val conn = URL(ICON_BASE_URL + icon).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "agentyolo")
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                if (conn.responseCode !in 200..299) { conn.disconnect(); continue }
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                if (bytes.size > 512 * 1024) continue
                val ok = if (icon.endsWith(".png"))
                    bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
                        byteArrayOf(137.toByte(), 80.toByte(), 78.toByte(), 71.toByte(), 13.toByte(), 10.toByte(), 26.toByte(), 10.toByte()))
                else
                    String(bytes, 0, minOf(bytes.size, 512), StandardCharsets.UTF_8)
                        .contains(Regex("<svg\\b", RegexOption.IGNORE_CASE))
                if (ok) Files.write(target.toPath(), bytes)
            } catch (e: Exception) {
                // Icon is optional — the agent still renders with the default glyph.
            }
        }
    }

    private fun AgentDef.toJsonDef(): AgentRegistry.AgentDefJson =
        AgentRegistry.AgentDefJson(
            id = id, displayName = displayName, command = command,
            skipFlag = skipFlag, resumeFlag = resumeFlag,
            skipEnv = skipEnv?.let { AgentRegistry.SkipEnvJson(it.first, it.second) },
            icon = icon, url = url, install = install, status = status
        )
}
