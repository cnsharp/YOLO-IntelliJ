package com.cnsharp.yolo.panel

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import java.io.File

/**
 * Makes file references printed by agents clickable in the embedded terminal.
 *
 * Matches references of the form `path`, `path:line`, `path:line:column`, and `path:line-line` (a line
 * range — opens at the start line), e.g. `src/foo/Bar.kt:42`, `/abs/Bar.kt:42:13`, `C:\foo\Bar.kt:7`,
 * `./Makefile:10`, `~/x/y.kt:3`. Paths inside quotes (allowing embedded spaces, e.g.
 * `"/path with space/Bar.kt":5`) are also linked. `file://` URIs are accepted. When the file actually
 * exists in the project (or the agent's working dir / a content root), the reference becomes a hyperlink
 * that opens it in the IDE editor; clicking also hides the YOLO pane.
 *
 * A reference is only linked when it resolves to a real file *at match time* — see [exists]. This stops
 * the panel from painting clickable links for paths the agent printed incorrectly (e.g. a wrong directory
 * component), which would otherwise be dead links that navigate nowhere. The existence check is a cheap
 * filesystem `isFile` (no PSI/index query, no read action), so it never blocks the terminal emulator
 * thread; the heavier index/PSI resolution for navigation still happens on click in [resolve].
 *
 * A reference that is a fragment of a truncated path (e.g. `…` in the middle) is never linked — see the
 * truncation guards in [apply] and [StackTraceLinkFilter].
 *
 * Built entirely on public APIs: JediTerm's [HyperlinkFilter] / [LinkInfo] for the terminal link, and
 * IntelliJ's [com.intellij.openapi.fileEditor.OpenFileDescriptor] / [com.intellij.openapi.fileEditor.FileEditorManager]
 * for navigation — so it stays Marketplace-safe.
 */
class FileLinkFilter(
    private val project: Project?,
    private val baseDir: String,
    private val wrapState: WrapState? = null
) : HyperlinkFilter {

    override fun apply(text: String): LinkResult? {
        if (text.isBlank() || isDiffLine(text)) {
            // A blank or diff line breaks any pending wrap sequence.
            wrapState?.pendingPath = ""
            wrapState?.continuationSpan = null
            return null
        }
        val items = mutableListOf<LinkResultItem>()

        // --- Hard-wrap path reconstruction ---
        // JediTerm calls apply() per physical line. When a long path wraps at terminal width, the
        // head line ends with a path fragment (no extension, no :line) and we store it in
        // wrapState.pendingPath. On the very next call (the continuation line) we prepend that
        // prefix, re-match PATH_PATTERN on the joined text, and create a link for the tail portion
        // inside the current line. The link resolves the FULL reconstructed path so navigation is
        // correct even though only the tail is visible and clickable.
        val prefix = wrapState?.pendingPath ?: ""
        wrapState?.pendingPath = ""
        wrapState?.continuationSpan = null

        if (prefix.isNotEmpty()) {
            val trimmed = text.trimStart()
            val leading = text.length - trimmed.length
            val combined = prefix + trimmed
            val cm = PATH_PATTERN.matcher(combined)
            while (cm.find()) {
                val matchStart = cm.start(1)
                val matchEnd = cm.end()
                // Only care about matches that span the prefix/continuation boundary.
                if (matchStart >= prefix.length || matchEnd <= prefix.length) continue
                val rawC = cm.group(1)
                val hasExtC = cm.group(2) != null
                val hasLineC = cm.group(3) != null
                if (!hasExtC && !hasLineC) {
                    // Still no ext/line — path wraps again; store new prefix for next line.
                    if (combined.substring(matchEnd).isBlank()) wrapState?.pendingPath = rawC
                    continue
                }
                if (rawC.contains('…') || rawC.contains("...") || isTruncatedPath(combined, cm.end(1))) {
                    // Same placeholder fallback as the single-line path above, but mapped onto the
                    // continuation line: link the final filename segment here. The filename may sit fully
                    // on this line, or begin on the head (then only its tail, on this line, is linked).
                    // Inline truncation leaves no valid filename, so nothing links.
                    if (isPlaceholderTruncation(rawC)) {
                        val name = lastFilenameSegment(rawC)
                        if (name != null) {
                            val nameStartInRaw = rawC.length - name.length
                            val tailStart = leading + (nameStartInRaw - prefix.length).coerceAtLeast(0)
                            val tailEnd = leading + (matchEnd - prefix.length)
                            if (tailStart >= leading && tailEnd <= text.length && tailEnd > tailStart) {
                                items.add(LinkResultItem(tailStart, tailEnd, yoloHyperlink(project) {
                                    val file = resolve(name) ?: return@yoloHyperlink
                                    val p = project ?: return@yoloHyperlink
                                    openFileAt(p, file, null, null)
                                }))
                            }
                        }
                    }
                    continue
                }
                val lineNum = cm.group(3)?.toIntOrNull()
                val col = cm.group(5)?.toIntOrNull()
                val fullPath = fileLinkTarget(rawC, cm.group(2))
                // Remember what the head fragment turned out to be, so the head row's link (created on the
                // previous line, before this reconstruction) opens the same complete file reference.
                wrapState?.completedPaths?.set(prefix, WrapState.CompletedPath(fullPath, lineNum, col))
                val link = yoloHyperlink(project) {
                    val file = resolve(fullPath) ?: return@yoloHyperlink
                    val p = project ?: return@yoloHyperlink
                    openFileAt(p, file, lineNum, col)
                }
                // Map the tail portion back to positions within `text`.
                val tailStart = leading
                val tailEnd = leading + (matchEnd - prefix.length)
                if (tailEnd <= text.length) {
                    items.add(LinkResultItem(tailStart, tailEnd, link))
                    wrapState?.continuationSpan = tailStart until tailEnd
                }
            }
        }
        // --- End hard-wrap reconstruction ---

        // Quoted paths first (may contain spaces, e.g. `"/path with space/Bar.kt":5`). Their full spans are
        // recorded so the unquoted pass below can suppress a *sub-path* that falls inside the quotes — e.g.
        // `space/Bar.kt` inside `"/path with space/Bar.kt"` would otherwise be linked twice.
        val quotedSpans = mutableListOf<Pair<Int, Int>>()
        val q = QUOTED_PATH_PATTERN.matcher(text)
        while (q.find()) {
            val raw = q.group(2)
            // Same truncation guard as below: skip `…`/`...` marked (incomplete) paths.
            if (raw.contains('…') || raw.contains("...") || isTruncatedPath(text, q.end(2))) continue
            val line = q.group(3)?.toIntOrNull()
            val column = q.group(4)?.toIntOrNull()
            val link = yoloHyperlink(project) {
                val file = resolve(raw) ?: return@yoloHyperlink
                val p = project ?: return@yoloHyperlink
                openFileAt(p, file, line, column)
            }
            // Span the path (group 2) only — the quotes must not be clickable. When a `:line[:col]` follows
            // the closing quote (group 3), add it as a *second* link so the whole reference is clickable
            // without ever painting the quotes blue. A single span cannot skip the quote in the middle, so
            // the previous `q.end() - 1` trick wrongly dropped the last column digit on `":line:col"`.
            items.add(LinkResultItem(q.start(2), q.end(2), link))
            if (q.group(3) != null) {
                items.add(LinkResultItem(q.start(3) - 1, q.end(), link))
            }
            quotedSpans.add(q.start() to q.end())
        }

        // Standard (unquoted) path references.
        val m = PATH_PATTERN.matcher(text)
        while (m.find()) {
            // Skip a match that lies inside a quoted path (see quotedSpans above).
            if (quotedSpans.any { m.start(1) < it.second && m.end() > it.first }) continue
            // Skip a match that overlaps the reconstructed continuation tail (see the hard-wrap block
            // above): the continuation line's own complete path was already linked there, so linking it
            // again here would paint a duplicate (and now-overlapping) link on the same text.
            val cont = wrapState?.continuationSpan
            if (cont != null && m.start(1) < cont.last && m.end() > cont.first) continue
            // Skip the tail of a truncated path: a `…`/`...` immediately before the path start (e.g.
            // `Read(src/main/kotlin/com/cnshar…/real/Bar.kt)`). The agent only abbreviated the middle, so
            // the fragment after the marker is not a real file.
            if (isTruncatedPathHead(text, m.start(1))) continue
            val raw = m.group(1)
            val hasExt = m.group(2) != null
            val hasLine = m.group(3) != null
            // Skip bare extension-less, line-less paths: they are either directory references (not openable)
            // or — more importantly — fragments of a long path the terminal hard-wrapped across lines, which
            // would otherwise be painted as broken links (see PATH_PATTERN's completion requirement).
            if (!hasExt && !hasLine) {
            // Hard-wrap head detection: if this path fragment reaches the end of the line, store it so
            // the next physical line can attempt reconstruction.
            // A trailing path separator (the terminal wrapped exactly at a '/') is treated as end-of-line
            // and preserved in the prefix, so the reconstructed path keeps its delimiter. Without this,
            // `…/biz/service/` + `statemachine/Config.java` is dropped because the match ends before the
            // slash and the remainder ("/") is not blank.
            // Guard: only store when the last path segment has NO dot. A dot in the last segment means
            // the wrap split inside the extension (e.g. "build.gradl" for ".gradle") and the continuation
            // line would carry only the remaining extension chars — creating a 1–2 character phantom link
            // (e.g. just "e"). Without a dot the fragment ends mid-name (e.g. "OrderTransitionContext"
            // before ".java"), which is the correct wrap case.
            if (wrapState != null) {
                val rest = text.substring(m.end())
                val reachesEnd = rest.isBlank() || rest.all { it == '/' || it == '\\' }
                if (reachesEnd) {
                    // Capture from the path start to end-of-line (minus trailing whitespace) so any
                    // trailing separator survives into the prefix for correct reconstruction.
                    val headRaw = text.substring(m.start(1)).trimEnd()
                    val lastSep = headRaw.lastIndexOfAny(charArrayOf('/', '\\'))
                    val lastSegment = if (lastSep >= 0) headRaw.substring(lastSep + 1) else headRaw
                    if (!lastSegment.contains('.')) {
                        // Always store the fragment so the next line can reconstruct it — even when it is a
                        // truncated path (`...`/`…`). But a truncated head can never resolve to a real file,
                        // so don't paint a dead half-link on it: only link-complete wrapped heads are
                        // highlighted here; the final filename is linked on the continuation line instead
                        // (see the wrap-continuation block above).
                        wrapState.pendingPath = headRaw
                        if (!headRaw.contains("...") && !headRaw.contains('…')) {
                            // Highlight the head row too, so a wrapped path is not left half-painted. The
                            // fragment alone is not a real path yet, so the link opens whatever the next line
                            // completes it to (recorded below); if nothing completes it, [resolve] finds no
                            // file and the click simply does nothing.
                            val link = yoloHyperlink(project) {
                                val completed = wrapState.completedPaths[headRaw]
                                val file = resolve(completed?.path ?: headRaw) ?: return@yoloHyperlink
                                val p = project ?: return@yoloHyperlink
                                openFileAt(p, file, completed?.line, completed?.column)
                            }
                            items.add(LinkResultItem(m.start(1), m.start(1) + headRaw.length, link))
                        }
                    }
                }
            }
            continue
        }
            // A `…`/`...` truncation marker means the path is incomplete — most abbreviated paths are
            // dropped (e.g. `Proj…name`, `com/cnshar…`, `Proj...name` — the marker abuts a word and
            // truncates a *component*). But when the marker is a *standalone* path segment (`/.../`,
            // `/…/`), agents mean "middle directories omitted" and the *final filename* after it is still
            // a real, complete file — so we fall back to linking just that last segment
            // (e.g. `libra-statis/.../listener/ChannelConfigSyncListener.java` → `ChannelConfigSyncListener.java`)
            // instead of dropping the whole reference. See [lastFilenameSegment] / [isPlaceholderTruncation].
            if (raw.contains('…') || raw.contains("...") || isTruncatedPath(text, m.end(1))) {
                linkLastFilenameSegment(text, m.start(1), raw, items)
                continue
            }
            val line = m.group(3)?.toIntOrNull()
            val column = m.group(5)?.toIntOrNull()
            // group(1) already carries the full path *including* its extension; just hand it to [resolve].
            // (Historically this re-appended `.${group(2)}`, yielding a doubled `Foo.kt.kt` that never resolved.)
            val fullPath = fileLinkTarget(raw, m.group(2))
            // Resolution is deferred to click time (see resolve) so streaming output is never blocked by
            // index/PSI queries on the terminal emulator thread.
            val link = yoloHyperlink(project) {
                val file = resolve(fullPath) ?: return@yoloHyperlink
                val p = project ?: return@yoloHyperlink
                openFileAt(p, file, line, column)
            }
            // Span the whole reference (path + optional :line:column) so a click anywhere navigates.
            items.add(LinkResultItem(m.start(1), m.end(), link))
        }

        return if (items.isEmpty()) null else LinkResult(items)
    }

    /** Resolve a possibly-relative path against the agent's working dir and the project's content roots.
     *  Called from the click handler (EDT), wrapped in a read action because it touches the project model. */
    private fun resolve(raw: String): File? {
        val project = this.project ?: return null
        val candidates = mutableListOf<File>()
        when {
            raw.startsWith("file://") -> candidates += File(raw.removePrefix("file://"))
            raw.startsWith("~/") -> candidates += File(System.getProperty("user.home"), raw.removePrefix("~/"))
            raw == "~" -> candidates += File(System.getProperty("user.home"))
        }
        candidates += File(raw)
        candidates += File(baseDir, raw)
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            candidates += File(root.path, raw)
        }
        return candidates.firstOrNull { it.isFile }
    }

    /**
     * True when [raw] contains a truncation marker (`…` or `...`) that forms its OWN path segment — i.e. it
     * is bounded by path separators (`/.../`, `/…/`) or a line boundary. Agents use such a placeholder to mean
     * "middle directories omitted", leaving the final filename after it intact and linkable. An *inline*
     * marker (`cnshar…`, `Proj...name`) abuts a word and truncates a *component*, so its tail is unreliable
     * and must be dropped. Used by [linkLastFilenameSegment] to decide whether a truncated reference can still
     * yield a usable last-segment filename.
     */
    private fun isPlaceholderTruncation(raw: String): Boolean {
        for (marker in listOf("...", "…")) {
            var i = raw.indexOf(marker)
            while (i >= 0) {
                val before = if (i == 0) '/' else raw[i - 1]
                val after = if (i + marker.length >= raw.length) '/' else raw[i + marker.length]
                if ((before == '/' || before == '\\') && (after == '/' || after == '\\')) return true
                i = raw.indexOf(marker, i + marker.length)
            }
        }
        return false
    }

    /**
     * The final path segment of [raw] when it is a complete-looking file (a recognized programming extension
     * or a true dotfile, per [STACK_BARE_NAME_PATTERN]), else null. A truncated directory fragment
     * (`com/cnshar`), an inline-abbreviated tail (`Proj...name`), or a bare extension (`env`) all return null;
     * `ChannelConfigSyncListener.java` / `.gitignore` return themselves. Used to fall back to linking just the
     * filename when the path is a placeholder-truncated reference.
     */
    private fun lastFilenameSegment(raw: String): String? {
        val lastSep = raw.lastIndexOfAny(charArrayOf('/', '\\'))
        val name = if (lastSep >= 0) raw.substring(lastSep + 1) else raw
        if (name.isEmpty()) return null
        return if (STACK_BARE_NAME_PATTERN.matcher(name).matches()) name else null
    }

    /**
     * When [raw] (a matched path that contains a `…`/`...` marker) is a placeholder truncation, link just its
     * final filename segment [raw] within [text] (at offset [pathStart]). Inline truncations leave no valid
     * filename, so nothing is added. The link resolves the bare filename — it is clickable even though the
     * omitted middle directories mean [resolve] may only find the file when it sits at a content root.
     */
    private fun linkLastFilenameSegment(
        text: String,
        pathStart: Int,
        raw: String,
        items: MutableList<LinkResultItem>
    ) {
        if (!isPlaceholderTruncation(raw)) return
        val name = lastFilenameSegment(raw) ?: return
        val start = pathStart + (raw.length - name.length)
        val end = pathStart + raw.length
        if (start < 0 || end > text.length || start >= end) return
        items.add(LinkResultItem(start, end, yoloHyperlink(project) {
            val file = resolve(name) ?: return@yoloHyperlink
            val p = project ?: return@yoloHyperlink
            openFileAt(p, file, null, null)
        }))
    }

    companion object {
        /**
         * True when the path captured by a match is truncated: the char right after the captured path
         * (at [pathEnd]) is a `…` (U+2026) or a `...` run. Because the path component class is ASCII-safe,
         * `…` is never consumed into the captured group, so it always shows up at this boundary and must be
         * checked here — `raw.contains('…')` alone would miss it.
         */
        private fun isTruncatedPath(text: String, pathEnd: Int): Boolean {
            if (pathEnd >= text.length) return false
            val c = text[pathEnd]
            return c == '…' || (c == '.' && text.startsWith("...", pathEnd))
        }
    }
}

/**
 * The file path to open for an unquoted [PATH_PATTERN] match. [raw] is group 1 — the full path *including*
 * its extension — and [ext] is group 2 (the bare extension, or null when the match has no extension but a
 * `:line`). [raw] already carries the extension, so it is returned unchanged: never re-append `.` + [ext],
 * or [resolve] would look for a doubled `Foo.kt.kt` and find nothing.
 */
internal fun fileLinkTarget(raw: String, ext: String?): String = raw
