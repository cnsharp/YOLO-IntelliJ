package com.cnsharp.yolo.panel

import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkResult

/**
 * A [HyperlinkFilter] decorator that drops link items overlapping text the user has *typed* but not yet
 * submitted, so the agent's input box never turns what you are typing into a hyperlink.
 *
 * A PTY gives back one undifferentiated byte stream: the agent's own output and the TUI's echo of your
 * keystrokes are indistinguishable by the time they reach the terminal buffer, so "is this the input box?"
 * cannot be answered from the output side. The only reliable signal is the bytes going *to* the PTY —
 * [TypedInputGuard] records those, and this filter excludes the matching region of the line.
 *
 * Everything else (the agent's output, the transcript) keeps its links exactly as before.
 */
internal class InputAwareLinkFilter(
    private val delegate: HyperlinkFilter,
    private val guard: TypedInputGuard
) : HyperlinkFilter {

    override fun apply(text: String): LinkResult? {
        val result = delegate.apply(text) ?: return null
        val typed = guard.typedSpansIn(text)
        if (typed.isEmpty()) return result
        val items = result.items.filter { item ->
            typed.none { span -> item.startOffset <= span.last && span.first < item.endOffset }
        }
        return if (items.isEmpty()) null else LinkResult(items)
    }
}

/**
 * Tracks the text the user has typed into the embedded terminal but not yet submitted.
 *
 * Fed from the tty connector's `write` — i.e. exactly the keystrokes and pastes the user sends to the
 * agent — and cleared when the input is submitted (Enter) or abandoned (Ctrl-C / Ctrl-U). See
 * [InputAwareLinkFilter] for why this has to be tracked on the input side.
 *
 * Keystrokes arrive on JediTerm's writer thread and are read from the terminal emulator thread, so the
 * accumulated text is published through a [Volatile] field.
 */
internal class TypedInputGuard {

    @Volatile
    private var typed = ""

    private val lock = Any()

    /** Record [input] sent *to* the PTY by the user (a keystroke, a key sequence, or a paste). */
    fun onUserInput(input: String) {
        if (input.isEmpty()) return
        synchronized(lock) { typed = fold(typed, input) }
    }

    /** Ranges of [line] occupied by the pending typed text; empty when nothing is pending. */
    fun typedSpansIn(line: String): List<IntRange> {
        val pending = typed
        if (pending.isBlank()) return emptyList()
        val spans = ArrayList<IntRange>()
        // A multi-line input (Shift+Enter) is matched per line, since the terminal renders each on its
        // own row and [line] therefore only ever contains one of them.
        for (segment in pending.split('\n')) {
            if (segment.isBlank()) continue
            var from = 0
            while (from <= line.length - segment.length) {
                val at = line.indexOf(segment, from)
                if (at < 0) break
                spans += at until at + segment.length
                from = at + segment.length
            }
        }
        // A dropped file writes its path to the PTY without a trailing newline, so [typed] stays
        // non-empty until the next Enter. The substring match above already suppresses the real input
        // spans (e.g. the dropped path echoed on the input line); we must NOT blanket the whole line when
        // the pending text isn't found, or every agent output line printed before that Enter would lose
        // all of its links. The original blanket was meant to hide stray links during a desync (a submit
        // byte the [fold] branch didn't clear, or an IME/escape we ignored) — that rare case may now keep
        // a stray link under the caret, which is the lesser harm versus suppressing all real output links.
        return spans
    }

    /**
     * Fold [input] (already sent to the PTY) into the pending text. Reading the control-character
     * constants below should make each branch self-explanatory.
     */
    private fun fold(typed: String, input: String): String = when {
        // Enter / Return submits the input, so the box is empty again. A bare CR, LF, or CR+LF is a
        // submit; a multi-line paste carries newlines but is handled by the paste branch below.
        input == CR || input == LF || input == CR + LF -> ""
        // Esc + Enter (Shift/Alt+Enter) is a newline *inside* the input, not a submit.
        input == ESC + CR || input == ESC + LF -> typed + LF
        // Bracketed paste: keep the pasted body, drop the \e[200~ / \e[201~ markers around it.
        input.startsWith(PASTE_START) ->
            typed + input.substringAfter(PASTE_START).substringBefore(PASTE_END)
        // Escape sequence (arrows, function keys, mouse): moves the cursor, does not change the text.
        input.startsWith(ESC) -> typed
        // Backspace / Delete erase the last character.
        input == BACKSPACE || input == DELETE -> typed.dropLast(1)
        // Ctrl-C / Ctrl-U / Ctrl-K clear the input line.
        input in CLEAR_KEYS -> ""
        // Ordinary typing (incl. CJK and tab).
        input.all { it == TAB || (it >= SPACE && it != DELETE.single()) } -> typed + input
        // Any other control byte: leave the pending text alone.
        else -> typed
    }

    private companion object {
        // Control characters the terminal emits for key presses.
        const val ESC = "\u001B"        // escape — begins arrow / function-key / mouse sequences
        const val CR = "\r"             // Enter (carriage return)
        const val LF = "\n"             // Enter (line feed, some look-and-feels)
        const val BACKSPACE = "\b"      // Backspace
        const val DELETE = "\u007F"     // Delete (the DEL control char)
        const val SPACE = ' '
        const val TAB = '\t'

        // Bracketed-paste markers (\e[200~ … \e[201~) wrapping a pasted block.
        const val PASTE_START = "$ESC[200~"
        const val PASTE_END = "$ESC[201~"

        // Control keys that wipe the input line.
        const val CTRL_C = "\u0003"
        const val CTRL_U = "\u0015"
        const val CTRL_K = "\u000B"
        val CLEAR_KEYS = setOf(CTRL_C, CTRL_U, CTRL_K)
    }
}
