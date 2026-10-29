package com.cnsharp.yolo.settings

import com.cnsharp.yolo.Yolo
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Background checker for newer versions of the **installed** agent CLIs.
 *
 * Check strategy (per the chosen design): the *installed* version is read from the agent's own binary via
 * `<command> --version` (its built-in command), while the *latest* version is queried from the agent's package
 * source (`npm view` / `pip index versions` / `brew info`), and the two are compared as semver. `shell` / `url`
 * agents have no package source we can query, so they are reported as [Status.MANUAL] (the Update button then
 * opens the agent's site instead of running a command).
 *
 * Results are kept in a transient in-memory cache keyed by lower-cased command, so the Settings page can
 * reflect them on open and the terminal dropdown could surface a badge. The cache is not persisted.
 */
object AgentUpdateChecker {

    /** Per-agent update status. */
    enum class Status { NA, CHECKING, UP_TO_DATE, UPDATE_AVAILABLE, MANUAL, ERROR }

    /** Result for one agent: status plus the detected installed/latest versions (for display). */
    data class UpdateInfo(
        val status: Status,
        val current: String = "",
        val latest: String = ""
    )

    private val LOG = Logger.getInstance(AgentUpdateChecker::class.java)

    /** Single probe timeout - a hung --version or registry query must not stall the whole scan. */
    private const val PROBE_TIMEOUT_MS = 20_000
    /** Key under which last-known latest versions are persisted (cache-first on panel open). */
    private const val CACHE_KEY = "yolo.latestVersions"
    private const val CACHE_KEY_CURRENT = "yolo.currentVersions"
    private const val CACHE_KEY_CURRENT_TS = "yolo.currentVersions.ts"
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

    @Volatile private var checking = false
    private val lock = Any()
    private val results = mutableMapOf<String, UpdateInfo>()
    private val listeners = mutableListOf<(Map<String, UpdateInfo>) -> Unit>()
    /** Detections collected during a scan, flushed to PropertiesComponent once at the end. */
    private val dirtyLatest = mutableMapOf<String, String>()
    private val dirtyCurrent = mutableMapOf<String, String>()
    private val dirtyCurrentTs = mutableMapOf<String, Long>()
    /** Ensures the persisted cache is loaded into results at most once (status NA, version shown). */
    private var seeded = false

    /** Full result for a command (lower-cased), or null when never checked. Read under the same monitor the
     *  pooled-thread writer uses, so a table repaint (EDT) can't race a checkAll write and throw CME. */
    fun infoFor(command: String): UpdateInfo? = synchronized(results) { results[command.lowercase()] }

    /** Convenience: just the status for a command. */
    fun statusFor(command: String): Status = infoFor(command)?.status ?: Status.NA

    /** Whether a background scan is currently running (so callers can avoid overlapping scans). */
    fun isChecking(): Boolean = checking

    /** Immutable snapshot of all known results, keyed by lower-cased command. */
    fun snapshot(): Map<String, UpdateInfo> = synchronized(results) { results.toMap() }

    fun addListener(l: (Map<String, UpdateInfo>) -> Unit) {
        synchronized(listeners) { listeners += l }
    }

    fun removeListener(l: (Map<String, UpdateInfo>) -> Unit) {
        synchronized(listeners) { listeners -= l }
    }

    private fun publish() {
        val snap = snapshot()
        ApplicationManager.getApplication().invokeLater {
            synchronized(listeners) { listeners.toList() }.forEach { it(snap) }
        }
    }

    /** Check every installed agent that declares an automatable install spec. Safe to call repeatedly. */
    fun checkAll() {
        // Flip the guard synchronously on the calling thread so two rapid calls can't both slip past it
        // before the pooled thread sets `checking` (which would spawn two duplicate scans).
        synchronized(lock) {
            if (checking) return
            checking = true
        }
        seedFromCache()
        val installed = InstalledAgents.installed()
        val specs = AgentRegistry.agents.mapNotNull { def ->
            val spec = def.install ?: return@mapNotNull null
            if (!spec.automatable && !spec.hasLatestSource) return@mapNotNull null
            if (def.command.lowercase() !in installed) return@mapNotNull null
            def.command.lowercase() to spec
        }
        if (specs.isEmpty()) {
            checking = false
            return
        }

        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            try {
                synchronized(results) { specs.forEach { (cmd, _) -> results[cmd] = UpdateInfo(Status.CHECKING, results[cmd]?.current ?: "", results[cmd]?.latest ?: "") } }
                publish()
                for ((cmd, spec) in specs) {
                    val info = try {
                        checkAgent(cmd, spec)
                    } catch (e: Exception) {
                        LOG.warn("${Yolo.NAME}: update check failed for $cmd", e)
                        UpdateInfo(Status.ERROR)
                    }
                    synchronized(results) { results[cmd] = info }
                    if (info.latest.isNotBlank()) markLatest(cmd, info.latest)
                    if (info.current.isNotBlank()) markCurrent(cmd, info.current)
                }
                publish()
            } finally {
                checking = false
                flushLatest()
            }
        }
    }

    /** Re-check a single command (e.g. right after a successful upgrade) and publish the new status. */
    fun refresh(command: String, spec: InstallSpec) {
        val info = try {
            checkAgent(command, spec)
        } catch (e: Exception) {
            LOG.warn("${Yolo.NAME}: update refresh failed for $command", e)
            UpdateInfo(Status.ERROR)
        }
        synchronized(results) { results[command.lowercase()] = info }
        if (info.latest.isNotBlank()) markLatest(command, info.latest)
        if (info.current.isNotBlank()) markCurrent(command, info.current)
        flushLatest()
        publish()
    }

    /** Read the installed version from the agent's own `<command> --version`, compare with the package source. */
    private fun checkAgent(command: String, spec: InstallSpec): UpdateInfo {
        val current = currentVersion(command)
        if (!canQueryVersion(spec)) {
            // No queryable package source (shell/url without a latest source, or brew on Windows): report
            // MANUAL so the UI offers the agent's own update command / site instead of a fake result.
            return if (current.isNotEmpty()) UpdateInfo(Status.MANUAL, current, "") else UpdateInfo(Status.NA)
        }
        val latest = latestVersion(spec)
        return when {
            latest.isNotEmpty() && current.isNotEmpty() -> {
                val cmp = compareSemver(semverOf(current), semverOf(latest))
                // current >= latest (incl. a newer-than-latest local build) => up to date.
                if (cmp < 0) UpdateInfo(Status.UPDATE_AVAILABLE, current, latest)
                else UpdateInfo(Status.UP_TO_DATE, current, latest)
            }
            latest.isNotEmpty() -> {
                // Installed version unreadable; fall back to the package manager's outdated probe
                // (only meaningful for npm/pip/brew; web/github sources have no such probe).
                val outdated = if (spec.type in setOf("npm", "pip", "brew")) probeOutdated(spec) else false
                UpdateInfo(if (outdated) Status.UPDATE_AVAILABLE else Status.UP_TO_DATE, current, latest)
            }
            else -> UpdateInfo(Status.ERROR, current, "")
        }
    }

    /** Run `<command> --version` (falling back to `-v`) and extract the first semver-looking token. */
    private fun currentVersion(command: String): String {
        for (flag in listOf("--version", "-v")) {
            val (ok, out) = run("$command $flag")
            if (ok) semverOf(out)?.let { return it.joinToString(".") }
        }
        return ""
    }

    /** Latest published version for an install spec. A declared `latest` source (npm / github /
     *  pypi / brew / web) is queried over HTTP first; otherwise we fall back to the package-manager
     *  shell probes (npm view / pip / brew info). Empty if it cannot be determined. */
    private fun latestVersion(spec: InstallSpec): String {
        if (spec.latest?.npm?.isNotBlank() == true) return npmLatestFromApi(spec.latest!!.npm)
        if (spec.latest?.github?.isNotBlank() == true) return githubLatest(spec.latest!!.github)
        if (spec.latest?.pypi?.isNotBlank() == true) return pypiLatest(spec.latest!!.pypi)
        if (spec.latest?.brew?.isNotBlank() == true) return brewLatestFromApi(spec.latest!!.brew)
        val web = spec.latest?.web
        if (web != null && web.url.isNotBlank()) return webLatest(web)
        return when (spec.type) {
        "npm" -> {
            val (ok, out) = run("npm view '${spec.pkg.replace("'", "'\\''")}' version")
            if (ok) semverOf(out)?.joinToString(".") ?: "" else ""
        }
        "pip" -> pipLatest(spec)
        "brew" -> {
            val (ok, out) = run("brew info '${spec.pkg.replace("'", "'\\''")}' --json=v2")
            if (ok) {
                // JSON: { "versions": { "stable": "1.2.3" } }
                Regex("\"stable\"\\s*:\\s*\"([^\"]+)\"").find(out)?.groupValues?.get(1)?.let { v ->
                    semverOf(v)?.joinToString(".") ?: v
                } ?: ""
            } else ""
        }
        else -> ""
        }
    }

    /** pip latest: prefer `pip index versions` (newest listed first), fall back to a dry-run's "Would install". */
    private fun pipLatest(spec: InstallSpec): String {
        val pkg = spec.pkg.replace("'", "'\\''")
        val (ok1, out1) = run("python3 -m pip index versions '$pkg'")
        if (ok1) {
            Regex("Available versions:\\s*([0-9][^,\\s]*)").find(out1)?.groupValues?.get(1)?.let {
                return semverOf(it)?.joinToString(".") ?: it
            }
        }
        val (ok2, out2) = run("python3 -m pip install --upgrade '$pkg' --dry-run")
        if (ok2) {
            Regex("Would install\\s+$pkg[=\\s-]+([0-9][0-9A-Za-z.\"-]*)").find(out2)?.groupValues?.get(1)
                ?.trim('-', '"')?.let { return semverOf(it)?.joinToString(".") ?: it }
        }
        return ""
    }

    /** Legacy read-only "is there a newer version" probe, used as a fallback when the installed version is
     *  unreadable. Returns true when the package manager reports an available upgrade. */
    private fun probeOutdated(spec: InstallSpec): Boolean {
        val cmd = when (spec.type) {
            "npm" -> "npm outdated -g '${spec.pkg.replace("'", "'\\''")}' --json"
            "pip" -> "python3 -m pip install --upgrade --dry-run '${spec.pkg.replace("'", "'\\''")}'"
            "brew" -> "brew outdated '${spec.pkg.replace("'", "'\\''")}'"
            else -> return false
        }
        val (ok, out) = run(cmd)
        if (!ok) return false
        return when (spec.type) {
            "npm" -> { val t = out.trim(); t.isNotEmpty() && t != "{}" }
            "pip" -> Regex("Would install\\s+${Regex.escape(spec.pkg)}[=\\s-]").containsMatchIn(out)
            "brew" -> out.trim().isNotEmpty()
            else -> false
        }
    }

    /** Mutating upgrade command. For `shell` agents that declare `updateCmd`, run that (the agent's own
     *  self-update subcommand); otherwise fall back to re-running the installer, which also fetches latest. */
    fun updateCommand(spec: InstallSpec): String = when (spec.type) {
        "npm" -> "npm install -g '${spec.pkg.replace("'", "'\\''")}'"
        "pip" -> "python3 -m pip install --upgrade '${spec.pkg.replace("'", "'\\''")}'"
        "brew" -> "brew upgrade '${spec.pkg.replace("'", "'\\''")}'"
        "shell" -> when {
            spec.updateCmd.isNotBlank() -> spec.updateCmd
            SystemInfo.isWindows && spec.cmdWin.isNotBlank() -> spec.cmdWin
            else -> spec.cmd
        }
        else -> ""
    }

    /** Extract the first `major.minor[.patch]` token from arbitrary text (ignoring any pre-release suffix). */
    private fun semverOf(text: String): IntArray? {
        val m = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(text) ?: return null
        val (maj, min, pat) = m.destructured
        return intArrayOf(maj.toIntOrNull() ?: 0, min.toIntOrNull() ?: 0, pat.toIntOrNull() ?: 0)
    }

    /** Compare two semver arrays lexicographically; 0 if equal, <0 if a<b, >0 if a>b. */
    private fun compareSemver(a: IntArray?, b: IntArray?): Int {
        if (a == null && b == null) return 0
        if (a == null) return -1
        if (b == null) return 1
        for (i in 0 until min(a.size, b.size)) {
            if (a[i] != b[i]) return a[i] - b[i]
        }
        return a.size - b.size
    }

    /** Whether this spec's latest version can be queried on the current platform. */
    private fun canQueryVersion(spec: InstallSpec): Boolean {
        return (spec.automatableOnPlatform && spec.type in setOf("npm", "pip", "brew")) || spec.hasLatestSource
    }

    /** Fetch a URL body as text with a bounded timeout, or null on any failure (so the caller shows a
     *  dash instead of hanging the scan on a blocked/slow registry). */
    private fun fetchText(url: String, accept: String? = null): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "agentyolo")
            if (accept != null) conn.setRequestProperty("Accept", accept)
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            if (conn.responseCode !in 200..299) { conn.disconnect(); null }
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) { null }
    }

    /** Latest version from the npm dist-tags endpoint (small payload, unlike the full packument). */
    private fun npmLatestFromApi(pkg: String): String {
        val url = "https://registry.npmjs.org/-/package/" + pkg.replace("/", "%2f") + "/dist-tags"
        val text = fetchText(url) ?: return ""
        return try {
            val obj = Gson().fromJson(text, JsonObject::class.java)
            obj.getAsJsonPrimitive("latest")?.asString ?: ""
        } catch (e: Exception) { "" }
    }

    /** Latest version from a GitHub repo's latest release (release name's semver preferred over tag_name). */
    private fun githubLatest(repo: String): String {
        val url = "https://api.github.com/repos/" + repo + "/releases/latest"
        val text = fetchText(url, "application/vnd.github+json") ?: return ""
        return try {
            val obj = Gson().fromJson(text, JsonObject::class.java)
            for (key in listOf("name", "tag_name")) {
                val raw = obj.get(key)?.takeIf { !it.isJsonNull }?.asString ?: continue
                val cleaned = raw.removePrefix("v")
                val v = semverOf(cleaned)
                if (v != null && v[0] < 1000) return v.joinToString(".") // reject calendar-style tags
            }
            ""
        } catch (e: Exception) { "" }
    }

    /** Latest version from PyPI's JSON API. */
    private fun pypiLatest(pkg: String): String {
        val url = "https://pypi.org/pypi/" + pkg + "/json"
        val text = fetchText(url) ?: return ""
        return try {
            val obj = Gson().fromJson(text, JsonObject::class.java)
            obj.getAsJsonObject("info")?.getAsJsonPrimitive("version")?.asString ?: ""
        } catch (e: Exception) { "" }
    }

    /** Latest version from the Homebrew formula API (no auth, unlike the GitHub API rate limit). */
    private fun brewLatestFromApi(formula: String): String {
        val url = "https://formulae.brew.sh/api/formula/" + formula + ".json"
        val text = fetchText(url) ?: return ""
        return try {
            val obj = Gson().fromJson(text, JsonObject::class.java)
            obj.getAsJsonObject("versions")?.getAsJsonPrimitive("stable")?.asString ?: ""
        } catch (e: Exception) { "" }
    }

    /** Latest version scraped from a web page that has no structured API. Returns the first capture
     *  group (or the whole match). Used for date-versioned tools whose install page is server-rendered. */
    private fun webLatest(src: WebSource): String {
        val html = fetchText(src.url) ?: return ""
        return try {
            val m = Regex(src.pattern, RegexOption.MULTILINE).find(html)
            if (m != null) (if (m.groupValues.size > 1) m.groupValues[1] else m.groupValues[0]) else ""
        } catch (e: Exception) { "" }
    }

    // -- persisted cache of last-known latest versions (cache-first on panel open) --

    private fun seedFromCache() {
        if (seeded) return
        seeded = true
        val now = System.currentTimeMillis()
        val latestMap = loadVersionMap(CACHE_KEY)
        val curMap = loadVersionMap(CACHE_KEY_CURRENT)
        val tsMap = loadTsMap(CACHE_KEY_CURRENT_TS)
        synchronized(results) {
            for ((cmd, ver) in latestMap) {
                val k = cmd.lowercase()
                if (ver.isNotBlank() && !results.containsKey(k)) {
                    results[k] = UpdateInfo(Status.NA, "", ver)
                }
            }
            for ((cmd, ver) in curMap) {
                val k = cmd.lowercase()
                val ts = tsMap[cmd] ?: 0L
                if (ver.isNotBlank() && now - ts < CACHE_TTL_MS) {
                    val existing = results[k]
                    results[k] = UpdateInfo(existing?.status ?: Status.NA, ver, existing?.latest ?: "")
                }
            }
        }
    }

    private fun markLatest(command: String, version: String) {
        if (version.isNotBlank()) dirtyLatest[command.lowercase()] = version
    }

    private fun markCurrent(command: String, version: String) {
        if (version.isNotBlank()) {
            val k = command.lowercase()
            dirtyCurrent[k] = version
            dirtyCurrentTs[k] = System.currentTimeMillis()
        }
    }

    private fun loadVersionMap(key: String): Map<String, String> = try {
        val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
        Gson().fromJson<Map<String, String>>(PropertiesComponent.getInstance().getValue(key) ?: "{}", type) ?: emptyMap()
    } catch (e: Exception) { emptyMap() }

    private fun loadTsMap(key: String): Map<String, Long> = try {
        val type = object : com.google.gson.reflect.TypeToken<Map<String, Long>>() {}.type
        Gson().fromJson<Map<String, Long>>(PropertiesComponent.getInstance().getValue(key) ?: "{}", type) ?: emptyMap()
    } catch (e: Exception) { emptyMap() }

    private fun flushLatest() {
        if (dirtyLatest.isEmpty() && dirtyCurrent.isEmpty()) return
        val pc = PropertiesComponent.getInstance()
        if (dirtyLatest.isNotEmpty()) {
            val existing = try {
                val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                val cur = pc.getValue(CACHE_KEY)
                val map = Gson().fromJson<Map<String, String>>(cur ?: "{}", type)
                (map ?: emptyMap()).toMutableMap()
            } catch (e: Exception) { mutableMapOf() }
            existing.putAll(dirtyLatest)
            pc.setValue(CACHE_KEY, Gson().toJson(existing))
            dirtyLatest.clear()
        }
        if (dirtyCurrent.isNotEmpty()) {
            val existingCur = loadVersionMap(CACHE_KEY_CURRENT).toMutableMap()
            existingCur.putAll(dirtyCurrent)
            pc.setValue(CACHE_KEY_CURRENT, Gson().toJson(existingCur))
            dirtyCurrent.clear()
            val existingTs = loadTsMap(CACHE_KEY_CURRENT_TS).toMutableMap()
            existingTs.putAll(dirtyCurrentTs)
            pc.setValue(CACHE_KEY_CURRENT_TS, Gson().toJson(existingTs))
            dirtyCurrentTs.clear()
        }
    }

    /** Run a shell command and return (success, combined output). Mirrors AgentExtenderConfigurable.runInstallCommand. */
    fun run(command: String): Pair<Boolean, String> {
        if (command.isBlank()) return false to "empty command"
        val pb = if (SystemInfo.isWindows) {
            ProcessBuilder("powershell", "-NoProfile", "-Command", command)
        } else {
            val shell = System.getenv("SHELL")?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "bash"
            ProcessBuilder(shell, "-lic", command)
        }
        return try {
            pb.redirectErrorStream(true)
            // Stop a pager (triggered by e.g. `--version`/`--help` on some CLIs, or `brew info`) from hanging
            // the scan waiting on a TTY. Mirrors AgentDetector.runForExit.
            pb.environment()["PAGER"] = "cat"
            pb.environment()["GIT_PAGER"] = "cat"
            val p = pb.start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val finished = p.waitFor(600, TimeUnit.SECONDS)
            (finished && p.exitValue() == 0) to out
        } catch (e: Exception) {
            false to (e.message ?: "exception")
        }
    }
}
