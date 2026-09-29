package com.cnsharp.yolo.panel

/**
 * Shared mutable state for tracking terminal references that the terminal *hard-wrapped* across consecutive
 * physical lines.
 *
 * JediTerm calls each [com.jediterm.terminal.model.hyperlinks.HyperlinkFilter] in registration order
 * for every physical line. One [WrapState] is created per terminal session and handed to every filter that
 * needs cross-line reconstruction, so:
 *
 * - [FileLinkFilter] reads the OLD [pendingPath] (captured on the *previous* line), attempts path
 *   reconstruction, and writes [continuationSpan] before returning.
 * - [TypeLinkFilter] / [MemberLinkFilter] read [pendingType] / [pendingMember] and stitch a wrapped
 *   `Class`/`Class.member` reference back together.
 * - [StackTraceLinkFilter] reads [continuationSpan] and suppresses any bare-name match that already has a
 *   correct link created by [FileLinkFilter] / [MemberLinkFilter].
 *
 * Each "pending" field is written by its owning filter at the END of a call (when a reference fragment reaches
 * the end of the line) and read at the START of the *next* call. [continuationSpan] is reset by
 * [FileLinkFilter] at the top of each line (it runs first) so it always reflects the current line.
 */
class WrapState {
    /** Path fragment at the end of the PREVIOUS line that had no extension or line-number. */
    var pendingPath: String = ""

    /**
     * Type-name fragment at the end of the PREVIOUS line — a qualified name (with namespace separators) that
     * had no trailing member. Stitched to the next line so a wrapped `com.foo.Ba` + `r` becomes `com.foo.Bar`.
     */
    var pendingType: String = ""

    /**
     * Class-part fragment at the end of the PREVIOUS line — a `Class` (qualified or capitalized simple) that
     * had no `#member` / `.member` yet. Stitched to the next line so a wrapped `Foo.impl.Ba` + `r#method`
     * becomes `Foo.impl.Bar#method`.
     */
    var pendingMember: String = ""

    /**
     * True when [pendingMember] already carries its `#member` / `.member` part — i.e. the terminal split the
     * reference *inside* the member name (`Foo#antiFra` + `udConfirm`) rather than inside the class name.
     * Used to require the continuation to be a plain identifier remainder: a next line that is itself a
     * complete reference (`UserService.find`) is a new token, not a wrap remainder, and must not be glued on.
     */
    var pendingMemberHasMember: Boolean = false

    /**
     * Head reference text -> the member name the continuation line completed it to. A wrapped reference is
     * stitched when the *next* line is processed, which is after the head line's link was already created — so
     * the head link looks its member up here at click time and lands on the real method instead of falling
     * back to the class (navigating a truncated `Foo#antiFra` head would otherwise only open the class).
     */
    val completedMembers: MutableMap<String, String> = HashMap()

    /**
     * Head path fragment (end of the PREVIOUS line) -> the complete reference the continuation line
     * reconstructed from it. Same reason as [completedMembers]: the head row is highlighted before the
     * continuation is known, so its link looks the full path up here at click time.
     */
    val completedPaths: MutableMap<String, CompletedPath> = HashMap()

    /**
     * The most recent path a wrap chain completed to (the full file plus optional line/column), set when a
     * continuation line with an extension/`:line` finishes the reconstruction. Head links across the *whole*
     * chain — not just the immediate predecessor — navigate to this, so a multi-line wrap no longer leaves
     * its earliest rows with a dead link.
     *
     * Lifetime: set when a chain completes, and cleared only when a *new* wrap chain begins (a fresh path
     * fragment reaching end-of-line). It must NOT be cleared on every line, and it must NOT be cleared on a
     * blank/diff line either — the agent typically prints more output (often with blank lines) after a
     * wrapped path, and that must keep `lastCompletedPath` alive, or the wrapped path's head rows would turn
     * into dead links (only the final row would stay clickable). This is intentionally chain-scoped, not
     * per-line. The blank/diff branch in [FileLinkFilter.apply] only resets `pendingPath`/`continuationSpan`
     * (to stop a later line being mistaken for a continuation), never this field.
     */
    var lastCompletedPath: CompletedPath? = null

    /** A file reference completed by a continuation line: full path plus its optional line/column. */
    data class CompletedPath(val path: String, val line: Int?, val column: Int?)

    /**
     * When a filter reconstructed a wrapped reference on the CURRENT line, this is the `[startOffset, endOffset)`
     * range in the current line text where the continuation tail was linked. [StackTraceLinkFilter] suppresses
     * any match that overlaps this span to avoid a duplicate (and incorrect) link for the same text.
     */
    var continuationSpan: IntRange? = null
}
