package com.cnsharp.yolo.panel

import java.util.regex.Pattern

/**
 * Link-detection building blocks shared by the terminal [com.jediterm.terminal.model.hyperlinks.HyperlinkFilter]s.
 *
 * Everything here is public-API-only (regex + IntelliJ navigation), so the filters stay Marketplace-safe.
 * Centralizing the patterns keeps the regex strings in one place (no drift between filters) and makes the
 * group layouts documented below the single source of truth each filter references.
 */

/** Max hyperlinks a single terminal line may produce before the rest is ignored (defensive cap). */
internal const val MAX_MATCHES_PER_LINE = 50

/**
 * A fixed allowlist of common programming / source / config file extensions across the IntelliJ-platform
 * family (Java/Kotlin/Scala/Groovy, JS/TS, Python, Go, Rust, C/C++/C#, Ruby, PHP, Swift, ObjC, Dart,
 * SQL, shell, markup & data formats, and IntelliJ's own project files).
 *
 * The terminal file-link filters use this instead of a generic "any word-char extension" rule, so a
 * dotted name that is *not* a real file — e.g. `pay.amount.mark`, `JSON.parseObject`, `Ctrl/C` — is never
 * mistaken for a file reference. Only names ending in one of these recognized extensions are linked.
 *
 * Case-insensitive (matched via `(?i:…)` wherever the fragment is embedded).
 */
// >>> YOLO_LINK_PATTERNS_START >>>
internal const val PROGRAMMING_EXT: String = "kt|kts|java|scala|sc|groovy|gradle|py|pyi|pyw|rb|rake|php|pl|pm|lua|sh|bash|zsh|ksh|bat|cmd|ps1|psm1|psd1|js|jsx|mjs|cjs|ts|tsx|vue|html|htm|xhtml|css|scss|sass|less|styl|go|rs|c|h|cc|cpp|cxx|hpp|hxx|hh|cs|m|mm|swift|d|nim|zig|s|asm|ex|exs|clj|cljs|cljc|erl|hs|ml|mli|fs|fsx|fsi|jl|r|proto|sol|graphql|gql|xml|xsd|xsl|xslt|wsdl|json|json5|jsonc|yaml|yml|toml|ini|cfg|conf|config|properties|env|lock|csv|tsv|log|md|markdown|rst|txt|text|diff|patch|editorconfig|gitignore|dockerignore|tf|tfvars|feature|bnf|avsc|edn|iml|ipr|iws"

internal val PATH_PATTERN: Pattern = Pattern.compile("""(?<![\\/\w.])((?:(?:[A-Za-z]:[\\/]?)|[\\/]|[~][\\/]?|\\\\[A-Za-z0-9._\-]+(?:[\\/][A-Za-z0-9._\-]+)+|[A-Za-z0-9._\-]+[\\/])(?:[A-Za-z0-9._\-]+[\\/])*(?:[A-Za-z0-9._\-]+\.((?i:$PROGRAMMING_EXT))(?![\\/\w.])|[A-Za-z0-9._\-]+))(?::(\d+)(?:-(\d+))?(?::(\d+))?)?""")

internal val QUOTED_PATH_PATTERN: Pattern = Pattern.compile("""(["'])((?:[A-Za-z]:)?[\\/][^"']*?\.(?i:$PROGRAMMING_EXT))\1(?::(\d+))?(?::(\d+))?""")

internal val STACK_BARE_PATTERN: Pattern = Pattern.compile("""(?<![\\/\w.\-])([\w.\-]+\.(?i:$PROGRAMMING_EXT)):(\d+)(?::(\d+))?""")

internal val STACK_BARE_NAME_PATTERN: Pattern = Pattern.compile("""(?<![\\/\w.\-])([\w.\-]+\.(?i:$PROGRAMMING_EXT)|\.(?:gitignore|dockerignore|editorconfig))(?![\\/\w.:])""")

internal val STACK_PY_DQ_PATTERN: Pattern = Pattern.compile("""File "([^"]+\.(?i:$PROGRAMMING_EXT))", line (\d+)""")

internal val STACK_PY_SQ_PATTERN: Pattern = Pattern.compile("""File '([^']+\.(?i:$PROGRAMMING_EXT))', line (\d+)""")

internal val TYPE_NAME_PATTERN: Pattern = Pattern.compile("""(?<![.\w/\\])(?<qualified>\\?(?:[A-Za-z_][A-Za-z0-9_]*+)(?:(?:\.|::|\\)[A-Za-z_][A-Za-z0-9_]*+)+)(?!\.[a-z])(?![#.][A-Za-z_]\w*)(?!:\d)|(?<![.\w/\\])(?<simple>(?![A-Z]+\b)[A-Z][a-zA-Z0-9_]*+)(?!\.[a-z])(?![#.][A-Za-z_]\w*)(?!:\d)""")

internal val TYPE_LINE_PATTERN: Pattern = Pattern.compile("""(?<![.\w/\\])(?:(?<qualified>\\?(?:[A-Za-z_][A-Za-z0-9_]*+)(?:(?:\.|::|\\)[A-Za-z_][A-Za-z0-9_]*+)+)|(?<simple>(?![A-Z]+\b)[A-Z][a-zA-Z0-9_]*+)):(?<line>\d+)(?::(?<col>\d+))?""")

internal val MEMBER_REF_PATTERN: Pattern = Pattern.compile("""(?<class>(?<![.\w/\\])(?:\\?(?:[A-Za-z_][A-Za-z0-9_]*+)(?:(?:\.|::|\\)[A-Za-z_][A-Za-z0-9_]*+)+)|(?<![.\w/\\])[A-Z][a-zA-Z0-9_]*+)[.#](?<member>[A-Za-z_]\w*)""")

internal val URL_PATTERN: Pattern = Pattern.compile("""https?://[^\s<>"'\)\]]+""")

internal val TYPE_HEAD_PATTERN: Pattern = Pattern.compile("""(?<![.\w/\\])(?<qualified>\\?(?:[A-Za-z_][A-Za-z0-9_]*+)(?:(?:\.|::|\\)[A-Za-z_][A-Za-z0-9_]*+)+)\s*$""")

/**
 * A trailing, standalone, capitalized identifier at end-of-line that has no dot and no `:line` — the head
 * fragment of a *bare* (path-less) file name the terminal hard-wrapped mid-name, e.g. the `AccountD` in
 *
 *     AccountD
 *     ataMigrationDispatcher.java:18
 *
 * The lookbehind keeps it from matching a *suffix* of a longer word (e.g. the `Class` of `MyClass`), and
 * the capitalized-start + min-length-2 gate limits false positives (a line ending in prose like "see Foo").
 * It is only a wrap *candidate*: if the next line completes it into a real `name.ext:line`, [StackTraceLinkFilter]
 * links both rows; if not, the head link resolves to the bare fragment (a dead link, same trade-off as the
 * path-prefixed wrap in [FileLinkFilter]).
 */
internal val BARE_HEAD_PATTERN: Pattern = Pattern.compile("""(?<![\\/\w.\-])([A-Z][A-Za-z0-9_\-]{2,})\s*$""")

internal val MEMBER_HEAD_PATTERN: Pattern = Pattern.compile("""(?<![.\w/\\])(?<class>(?:(?:\\?(?:[A-Za-z_][A-Za-z0-9_]*+)(?:(?:\.|::|\\)[A-Za-z_][A-Za-z0-9_]*+)+)|[A-Z][a-zA-Z0-9_]*+))\s*$""")

// <<< YOLO_LINK_PATTERNS_END <<<

/**
 * True when the file reference at [fileStart] is the *tail* of a truncated path — i.e. the character(s)
 * immediately before it are a `…` (U+2026) or a `...` run. Agents abbreviate long paths with `…` in the
 * middle (e.g. `Read(src/main/kotlin/com/cnshar…entExtenderConfigurable.kt)`); the fragment after the
 * marker is not a real file, so it must not be linked. The path component class is ASCII-safe, so `…`
 * always lands *just before* the captured fragment and must be checked here.
 */
internal fun isTruncatedPathHead(text: String, fileStart: Int): Boolean {
    if (fileStart < 1) return false
    val c = text[fileStart - 1]
    return c == '…' || (c == '.' && text.startsWith("...", fileStart - 3))
}

