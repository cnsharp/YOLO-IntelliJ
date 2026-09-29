package com.cnsharp.yolo.panel

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import java.io.File

/**
 * Makes type references printed by agents clickable in the embedded terminal.
 *
 * Two kinds of references are linked:
 *  - **Qualified names** (e.g. `com.foo.Bar`, `com.foo.Bar.Baz` for inner classes): resolved across the
 *    whole project, including library/JDK sources, since a fully-qualified name is unambiguous.
 *  - **Simple names** (e.g. `Bar`): linked *only* when the name is a real type inside the project's content
 *    roots (see [YoloProjectTypes]), so ubiquitous JDK/library types like `String` or `List` — and ordinary
 *    capitalized words like `Result` or `OK` — are deliberately not highlighted.
 *
 * A trailing lowercase extension (e.g. `Bar.kt`) is excluded so these links never collide with the
 * file-path links produced by [FileLinkFilter].
 *
 * Resolution uses only public PSI APIs ([com.intellij.psi.JavaPsiFacade] / [com.intellij.psi.search.PsiShortNamesCache])
 * and is skipped while the index is in dumb mode, so it stays Marketplace-safe and never blocks the terminal.
 */
class TypeLinkFilter(
    private val project: Project?,
    private val typesProvider: (Project?) -> YoloProjectTypes.Snapshot = { p ->
        if (p != null) YoloProjectTypes.snapshot(p) else YoloProjectTypes.Snapshot(emptySet(), emptySet())
    },
    private val wrapState: WrapState? = null,
    /**
     * Test-only hook: when non-null, the type name a generated link targets is reported here synchronously
     * (once per linked reference, at link-generation time). Lets offline tests assert the *reference* a
     * reconstructed (hard-wrapped) type link would resolve, without a live project/index — PSI navigation
     * itself stays deferred to click time and is exercised only in a real IDE. Production passes `null`.
     */
    private val onResolved: ((typeRef: String) -> Unit)? = null,
) : HyperlinkFilter {

    override fun apply(text: String): LinkResult? {
        if (text.isBlank() || isDiffLine(text) || (project != null && DumbService.isDumb(project))) return null
        // Only link names that are real project types, so ordinary capitalized words and shortcut
        // notations (e.g. the `Ctrl` in `Ctrl/C`) are not painted blue.
        val types = typesProvider(project)
        val items = mutableListOf<LinkResultItem>()
        val projectRef = project

        // --- Hard-wrap reconstruction: stitch a qualified name split across physical lines. ---
        val prefix = wrapState?.pendingType ?: ""
        wrapState?.pendingType = ""
        if (prefix.isNotEmpty()) {
            val trimmed = text.trimStart()
            val leading = text.length - trimmed.length
            val combined = prefix + trimmed
            val cm = TYPE_NAME_PATTERN.matcher(combined)
            var g = 0
            while (cm.find() && g++ < MAX_MATCHES_PER_LINE) {
                val mStart = cm.start()
                val mEnd = cm.end()
                // Only the match spanning the prefix/continuation boundary is a reconstruction.
                if (mStart >= prefix.length || mEnd <= prefix.length) continue
                val qualified = cm.group("qualified")
                val simple = cm.group("simple")
                val known = when {
                    qualified != null -> lastTypeNameSegment(qualified) in types.simple
                    simple != null -> types.containsSimple(simple)
                    else -> false
                }
                if (!known) continue
                val name = qualified ?: simple ?: continue
                val tailStart = leading
                val tailEnd = leading + (mEnd - prefix.length)
                if (tailEnd > text.length) continue
                // Report the resolved reference (test seam) synchronously — PSI navigation itself is deferred
                // to click time, but the type this link targets is known here.
                onResolved?.invoke(name)
                val link = yoloHyperlink(projectRef) {
                    if (projectRef != null) {
                        val target = resolveType(projectRef, name)
                        if (target != null) openNavigationItem(target)
                    }
                }
                items.add(LinkResultItem(tailStart, tailEnd, link))
                wrapState?.continuationSpan = tailStart until tailEnd
            }
        }
        // --- End hard-wrap reconstruction ---

        val matcher = TYPE_NAME_PATTERN.matcher(text)
        var guard = 0
        var linkedThisLine = false
        val cont = wrapState?.continuationSpan
        while (matcher.find() && guard++ < MAX_MATCHES_PER_LINE) {
            // Skip a match overlapping the reconstructed continuation tail (above) to avoid a duplicate span.
            if (cont != null && matcher.start() < cont.last && matcher.end() > cont.first) continue
            val qualified = matcher.group("qualified")
            val simple = matcher.group("simple")
            val known = when {
                // Qualified name: gate by its trailing segment being a known project type. This avoids the
                // old O(N²) pre-computation of the full qualified set; resolution still happens on click.
                qualified != null -> lastTypeNameSegment(qualified) in types.simple
                simple != null -> types.containsSimple(simple)
                else -> false
            }
            if (known) {
                // Report the resolved reference (test seam) synchronously — PSI navigation itself is deferred to
                // click time, but the type this link targets is known here.
                onResolved?.invoke(qualified ?: simple ?: "")
                // Resolution is deferred to click time so streaming output is never blocked by PSI index queries
                // on the terminal emulator thread. The link navigates only if the name resolves to a real type
                // in some language's contributor.
                val link = yoloHyperlink(project) {
                    if (project != null) {
                        val name = qualified ?: simple
                        val target = if (name != null) resolveType(project, name) else null
                        if (target != null) {
                            // The terminal may have hard-wrapped right before this type's `#member`
                            // (`…LoanOrderServiceImpl` + `#method`): the visible text here is only the class,
                            // but the continuation completed the reference, so land on the member.
                            val completed = name.let { wrapState?.completedMembers?.get(it) }
                            val memberItem = completed?.let { resolveMember(project, target, it) }
                            if (memberItem != null) openNavigationItem(memberItem) else openNavigationItem(target)
                        }
                    }
                }
                items.add(LinkResultItem(matcher.start(), matcher.end(), link))
                linkedThisLine = true
                continue
            }
            // File-name fallback: a bare source-file base name that is not a class (e.g. a Kotlin file facade
            // like `YoloNavigation`). Linked to the source file so it is clickable too.
            if (simple != null && types.containsFile(simple)) {
                val link = yoloHyperlink(project) {
                    if (project != null) {
                        val vf = types.resolveFile(simple)
                        if (vf != null) openFileAt(project, File(vf.path), null, null)
                    }
                }
                items.add(LinkResultItem(matcher.start(), matcher.end(), link))
                linkedThisLine = true
            }
        }

        // --- Type:line references: `TypeName:line[:col]` (e.g. an agent "caller" citation like
        //     `DeductPaymentServiceImpl:182`). Link the full span and, on click, open the resolved type's
        //     source file at the given line. Gated to known project types **and** known source-file base
        //     names (mirroring the bare-type loop's file-name fallback) — a `TypeName` immediately followed
        //     by `:digits` is excluded from the type loop via the `(?!:\d)` lookahead in TYPE_NAME_PATTERN,
        //     so only this branch links it (no overlapping hyperlink). ---
        val tl = TYPE_LINE_PATTERN.matcher(text)
        var tlGuard = 0
        while (tl.find() && tlGuard++ < MAX_MATCHES_PER_LINE) {
            val qualified = tl.group("qualified")
            val simple = tl.group("simple")
            val isType = when {
                qualified != null -> lastTypeNameSegment(qualified) in types.simple
                simple != null -> types.containsSimple(simple)
                else -> false
            }
            // Also accept a known source-file base name (e.g. a Kotlin file facade) so a `TypeName:line`
            // whose name isn't an indexed class still links and opens the file at that line.
            val isFile = simple != null && types.containsFile(simple)
            if (!isType && !isFile) continue
            val name = qualified ?: simple ?: continue
            val line = tl.group("line")?.toIntOrNull() ?: continue
            val col = tl.group("col")?.toIntOrNull()
            // Report the resolved reference (test seam) synchronously — PSI navigation itself is deferred to
            // click time, but the type this link targets is known here.
            onResolved?.invoke(name)
            val link = yoloHyperlink(project) {
                if (project == null) return@yoloHyperlink
                // Prefer type resolution (lands on the class at the line); fall back to opening the source
                // file directly when the name is only a known file facade (no enclosing class to resolve).
                if (isType) {
                    openTypeAtLine(project, name, line, col)
                } else {
                    val vf = simple?.let { types.resolveFile(it) }
                    if (vf != null) openFileAt(project, File(vf.path), line, col)
                }
            }
            items.add(LinkResultItem(tl.start(), tl.end(), link))
            linkedThisLine = true
        }
        // --- End type:line references ---

        // Hard-wrap head detection: if a qualified name reaches end-of-line, remember it so the next physical
        // line can reconstruct (e.g. `com.foo.Ba` + `r` → `com.foo.Bar`). Skip when this line already linked a
        // complete type, and don't hold a known complete type as a pending prefix (it is not a wrap, and holding
        // it would risk false links on the next line) unless it ends in a separator (clearly incomplete).
        if (!linkedThisLine) {
            val head = TYPE_HEAD_PATTERN.matcher(text)
            if (head.find()) {
                val headName = head.group("qualified")
                if (headName != null && headName.isNotEmpty()) {
                    val lastSeg = lastTypeNameSegment(headName)
                    val endsWithSep = headName.endsWith('.') || headName.endsWith(':')
                    if (endsWithSep || lastSeg !in types.simple) wrapState?.pendingType = headName
                }
            }
        }
        return if (items.isEmpty()) null else LinkResult(items)
    }
}
