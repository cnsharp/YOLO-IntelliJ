package com.cnsharp.yolo.settings

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger

/** How an agent is installed. `type` is one of: npm | pip | brew | shell | url.
 *  - npm/pip/brew: install via the named package manager (`pkg`).
 *  - shell: run `cmd` verbatim through the user's login shell (curl/irm installers, …). `cmdWin` is the
 *    Windows-only variant (e.g. a PowerShell one-liner), used when [com.intellij.openapi.util.SystemInfo.isWindows].
 *  `updateCmd` (any type), when set, is the agent's *own* self-update subcommand (e.g. `claude update`); it is
 *    first-class: the Update button runs it instead of the package manager, re-running the installer, or
 *    opening the site.
 *  - url: no automatable package; the Install button opens `url` in the browser instead.
 *
 * `latest` is an optional structured source for the *latest* published version (mirrors the VSCode
 * `yolo` product's `agents.json`). When present, [AgentUpdateChecker] queries that source over HTTP
 * instead of (or in addition to) the package-manager shell probes, so `shell`/`url` agents whose
 * version cannot be read from a package manager (Cursor, Trae, Kiro, ...) still get a live "latest"
 * badge. One of `npm` / `github` / `pypi` / `brew` names the upstream, or `web` points at a page whose
 * version is scraped with `pattern`. */
data class WebSource(
    val url: String = "",
    val pattern: String = ""
)

data class LatestSpec(
    val npm: String = "",
    val github: String = "",
    val pypi: String = "",
    val brew: String = "",
    val web: WebSource? = null
) {
    val hasSource: Boolean
        get() = npm.isNotBlank() || github.isNotBlank() || pypi.isNotBlank() || brew.isNotBlank()
            || (web != null && web.url.isNotBlank())
}

data class InstallSpec(
    val type: String = "",
    val pkg: String = "",
    val cmd: String = "",
    val cmdWin: String = "",
    val url: String = "",
    val updateCmd: String = "",
    val latest: LatestSpec? = null
) {
    /** Whether the plugin can run this install head-less (everything except `url`). */
    val automatable: Boolean get() = type in setOf("npm", "pip", "brew", "shell")
    /** Like [automatable] but also false for `brew` on native Windows (no Homebrew there). */
    val automatableOnPlatform: Boolean
        get() = when (type) {
            "brew" -> !com.intellij.openapi.util.SystemInfo.isWindows
            else -> automatable
        }
    /** Human-readable install target shown in the status line, e.g. the package or command. */
    val target: String get() = when (type) {
        "npm", "pip", "brew" -> pkg
        "shell" -> if (com.intellij.openapi.util.SystemInfo.isWindows && cmdWin.isNotBlank()) cmdWin else cmd
        else -> url
    }
    /** True when the Update button can perform a real upgrade (the agent's own `updateCmd`, or a
     *  package-manager spec). */
    val hasUpdateCommand: Boolean get() = type in setOf("npm", "pip", "brew") || updateCmd.isNotBlank()
    /** True when a structured `latest` source is declared (so the version checker can query it over HTTP). */
    val hasLatestSource: Boolean get() = latest?.hasSource == true
}

/** The single terminal vendor-lifecycle state (manually assigned only):
 *  the product/service is discontinued (offline). Shared by the stage-A merge
 *  filter and the Manage Agents status flag. */
val TERMINAL_STATUSES: Set<String> = setOf("closed")

/** Whether this registry's install path works on each OS (v2, Meta/AGENTS.md §4). */
data class PlatformSupport(
    val darwin: String? = null,
    val win32: String? = null,
    val linux: String? = null
)

data class AgentDef(
    val id: String,
    val displayName: String,
    val command: String,
    val skipFlag: String = "",
    val resumeFlag: String = "",
    val skipEnv: Pair<String, String>? = null,
    val icon: String = "",
    /** Agent's homepage (e.g. https://claude.ai/) — powers the "Website" button on the Manage Agents card.
     *  Distinct from [InstallSpec.url], which is the install/download page (blank for npm/pip/brew agents). */
    val url: String = "",
    val install: InstallSpec? = null,
    /** Vendor-lifecycle status from the remote v2 catalog. Bundled v1 entries have no status (= active). */
    val status: String = "active",
    /** Whether this registry's install path works on each OS (v2). */
    val platformSupport: PlatformSupport? = null,
    /** UTC+0 (…Z) timestamp of the agent's most recent upstream release (v2). */
    val lastReleaseAt: String = ""
)

object AgentRegistry {

    private val LOG = Logger.getInstance(AgentRegistry::class.java)

    /** Bundled catalog (v1, from classpath), immutable. Used as the merge base and fallback. */
    val bundled: List<AgentDef> = load()

    /** Live catalog snapshot, atomically replaced by the startup hot-update. */
    @Volatile
    private var currentAgents: List<AgentDef> = bundled

    @Volatile
    private var byIdMap: Map<String, AgentDef> = bundled.associateBy { it.id.lowercase() }

    @Volatile
    private var byCommandMap: Map<String, AgentDef> = bundled.associateBy { it.command.lowercase() }

    val agents: List<AgentDef> get() = currentAgents

    /** OS key matching the platformSupport keys (Meta/AGENTS.md §4). */
    private fun currentOsKey(): String {
        val os = (System.getProperty("os.name") ?: "").lowercase()
        return when {
            os.contains("win") -> "win32"
            os.contains("mac") || os.contains("darwin") -> "darwin"
            else -> "linux"
        }
    }

    /** Keep everything except an explicit `unsupported` for this OS. A missing
     *  `platformSupport` (legacy v1 entries / user-defined overrides) and
     *  `viaWsl` (win32 through WSL) are kept. `osKey` is injectable for tests. */
    fun isSupportedOnThisOs(def: AgentDef, osKey: String = currentOsKey()): Boolean {
        val s = def.platformSupport ?: return true
        val v = when (osKey) {
            "win32" -> s.win32
            "darwin" -> s.darwin
            else -> s.linux
        }
        return v != "unsupported"
    }

    /** Replace the live catalog and rebuild the lookup maps atomically. */
    fun replaceAll(list: List<AgentDef>) {
        val safe = list.filter { isSupportedOnThisOs(it) }
        if (safe.isEmpty()) return
        byIdMap = safe.associateBy { it.id.lowercase() }
        byCommandMap = safe.associateBy { it.command.lowercase() }
        currentAgents = safe
    }

    /** Restore the bundled catalog (e.g. when a remote update must be discarded). */
    fun restoreBundled() = replaceAll(bundled)

    fun byId(id: String): AgentDef? = byIdMap[id.lowercase()]

    fun byCommand(command: String): AgentDef? = byCommandMap[command.lowercase()]

    private fun lookupByKey(key: String): AgentDef? {
        val k = key.lowercase()
        return byCommandMap[k] ?: byIdMap[k]
    }

    /** Lookup by command first, then by id — mirrors how DefaultSkipFlags.forId was called. */
    fun skipFlagFor(key: String): String = lookupByKey(key)?.skipFlag ?: ""

    fun resumeFlagFor(key: String): String = lookupByKey(key)?.resumeFlag ?: ""

    fun skipEnvFor(key: String): Pair<String, String>? = lookupByKey(key)?.skipEnv

    /** The install specification for the agent, or null if it declares none (custom tools, unknown). */
    fun installFor(key: String): InstallSpec? = lookupByKey(key)?.install

    /** Returns the classpath icon path, or null if the agent is unknown or has no icon.
     *  agents.json stores the icon as a bare filename (e.g. `claude.svg`); the bundled-icons
     *  root differs per end, so IntelliJ prepends its own `/icons/agents/` here. */
    fun iconFor(id: String): String? {
        val file = byIdMap[id.lowercase()]?.icon?.takeIf { it.isNotBlank() } ?: return null
        return if (file.startsWith("/")) file else "/icons/agents/$file"
    }

    private fun load(): List<AgentDef> {
        return try {
            val stream = AgentRegistry::class.java.getResourceAsStream("/agents.json")
            if (stream == null) {
                LOG.error("AgentRegistry: agents.json not found in classpath")
                return emptyList()
            }
            stream.bufferedReader().use { reader -> parseCatalog(reader.readText()) }
                .filter { isSupportedOnThisOs(it) }
        } catch (e: Exception) {
            LOG.error("AgentRegistry: failed to load agents.json", e)
            emptyList()
        }
    }

    /**
     * Parse a catalog body: either the legacy v1 flat array, or the v2 envelope
     * `{schemaVersion, updatedAt, agents}`. v2 requires schemaVersion == 2 and an
     * array `agents`; unknown versions are rejected (return empty). Extra v2 fields
     * on each agent (status/platformSupport/uninstall/…) are ignored by Gson.
     */
    fun parseCatalog(raw: String): List<AgentDef> {
        val root = JsonParser.parseString(raw)
        val arr = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && root.asJsonObject.has("agents") &&
                root.asJsonObject.get("schemaVersion")?.asInt == 2 &&
                root.asJsonObject.get("agents").isJsonArray -> root.asJsonObject.getAsJsonArray("agents")
            else -> return emptyList()
        }
        val rawList: List<AgentDefJson> = Gson().fromJson(arr, object : TypeToken<List<AgentDefJson>>() {}.type)
        return rawList.map { it.toAgentDef() }
    }

    data class AgentDefJson(
        val id: String = "",
        val displayName: String = "",
        val command: String = "",
        val skipFlag: String = "",
        val resumeFlag: String = "",
        val skipEnv: SkipEnvJson? = null,
        val icon: String = "",
        val url: String = "",
        val install: InstallSpec? = null,
        val status: String = "active",
        val platformSupport: PlatformSupport? = null,
        val lastReleaseAt: String = ""
    ) {
        fun toAgentDef() = AgentDef(
            id = id,
            displayName = displayName,
            command = command,
            skipFlag = skipFlag,
            resumeFlag = resumeFlag,
            skipEnv = skipEnv?.let { it.name to it.value },
            icon = icon,
            url = url,
            install = install,
            status = if (status.isBlank()) "active" else status,
            platformSupport = platformSupport,
            lastReleaseAt = lastReleaseAt
        )
    }

    data class SkipEnvJson(val name: String = "", val value: String = "")
}
