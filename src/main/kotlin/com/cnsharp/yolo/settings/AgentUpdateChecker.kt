package com.cnsharp.yolo.settings

import com.cnsharp.yolo.Yolo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
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

    @Volatile private var checking = false
    private val lock = Any()
    private val results = mutableMapOf<String, UpdateInfo>()
    private val listeners = mutableListOf<(Map<String, UpdateInfo>) -> Unit>()

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
        val installed = InstalledAgents.installed()
        val specs = AgentRegistry.agents.mapNotNull { def ->
            val spec = def.install ?: return@mapNotNull null
            if (!spec.automatable) return@mapNotNull null
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
                synchronized(results) { specs.forEach { (cmd, _) -> results[cmd] = UpdateInfo(Status.CHECKING) } }
                publish()
                for ((cmd, spec) in specs) {
                    val info = try {
                        checkAgent(cmd, spec)
                    } catch (e: Exception) {
                        LOG.warn("${Yolo.NAME}: update check failed for $cmd", e)
                        UpdateInfo(Status.ERROR)
                    }
                    synchronized(results) { results[cmd] = info }
                }
                publish()
            } finally {
                checking = false
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
        publish()
    }

    /** Read the installed version from the agent's own `<command> --version`, compare with the package source. */
    private fun checkAgent(command: String, spec: InstallSpec): UpdateInfo {
        val current = currentVersion(command)
        return when (spec.type) {
            "npm", "pip", "brew" -> {
                val latest = latestVersion(spec)
                when {
                    latest.isNotEmpty() && current.isNotEmpty() -> {
                        val cmp = compareSemver(semverOf(current), semverOf(latest))
                        // current >= latest (incl. a newer-than-latest local build) => up to date.
                        if (cmp < 0) UpdateInfo(Status.UPDATE_AVAILABLE, current, latest)
                        else UpdateInfo(Status.UP_TO_DATE, current, latest)
                    }
                    latest.isNotEmpty() -> {
                        // Installed version unreadable; fall back to the package manager's outdated probe.
                        val outdated = probeOutdated(spec)
                        UpdateInfo(if (outdated) Status.UPDATE_AVAILABLE else Status.UP_TO_DATE, current, latest)
                    }
                    else -> UpdateInfo(Status.ERROR, current, "")
                }
            }
            else -> UpdateInfo(Status.MANUAL, current, "")
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

    /** Latest published version for a package-manager spec. Empty if it cannot be determined. */
    private fun latestVersion(spec: InstallSpec): String = when (spec.type) {
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
