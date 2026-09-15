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
internal val PROGRAMMING_EXT: String = buildList {
    // JVM / static languages
    addAll(listOf("kt", "kts", "java", "scala", "sc", "groovy", "gradle"))
    // Dynamic / scripting
    addAll(listOf("py", "pyi", "pyw", "rb", "rake", "php", "pl", "pm", "lua", "sh", "bash", "zsh", "ksh", "bat", "cmd", "ps1", "psm1", "psd1"))
    // Web / front-end
    addAll(listOf("js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "html", "htm", "xhtml", "css", "scss", "sass", "less", "styl"))
    // Systems / native
    addAll(listOf("go", "rs", "c", "h", "cc", "cpp", "cxx", "hpp", "hxx", "hh", "cs", "m", "mm", "swift", "d", "nim", "zig", "s", "asm"))
    // Functional / other
    addAll(listOf("ex", "exs", "clj", "cljs", "cljc", "erl", "hs", "ml", "mli", "fs", "fsx", "fsi", "jl", "r", "proto", "sol", "graphql", "gql"))
    // Data / config / markup
    addAll(listOf("xml", "xsd", "xsl", "xslt", "wsdl", "json", "json5", "jsonc", "yaml", "yml", "toml", "ini", "cfg", "conf", "config", "properties", "env", "lock", "csv", "tsv", "log"))
    // Docs / misc
    addAll(listOf("md", "markdown", "rst", "txt", "text", "diff", "patch", "editorconfig", "gitignore", "dockerignore", "tf", "tfvars", "feature", "bnf", "avsc", "edn"))
    // IntelliJ project files
    addAll(listOf("iml", "ipr", "iws"))
}.joinToString("|")

/**
 * Path references printed by agents: `path`, `path:line`, `path:line:column`, `path:line-line` (range),
 * `path:column` (a bare `:N` is treated as a line; a `:N:M` after it as column), and `file://` URIs.
 * Drives [com.cnsharp.yolo.panel.FileLinkFilter].
 *
 * Windows is supported: drive letters (`C:\`, `C:\file.kt`), drive root, and UNC shares
 * (`\\server\share\...`); `~` home is supported on every OS. A leading slash/word/dot is excluded so the
 * pattern never reaches into a `http(s)://` URL's own path.
 *
 * **Completion requirement (extension OR `:line`):** the matched path must end in a recognized extension
 * ([PROGRAMMING_EXT]) *or* be followed by a `:line` reference. This stops a long path the terminal
 * *hard-wrapped* across lines from being linked as several broken fragments: the head fragment (e.g.
 * `…/WEB-INF/cla`) has neither an extension nor a line number, so it is skipped; only a fragment that
 * still looks like a complete file links.
 *
 * **Trailing extension boundary:** the extension is followed by a negative lookahead `(?![\\/\w.])` so a
 * listed extension only matches as a *full* extension, never as a prefix of a longer one. Without it the
 * regex would greedily grab the first listed extension it can — e.g. `.markdown` would match `.m`,
 * `.module` → `.m`, `.commit` → `.c` — truncating the link at `.m` and pointing at a non-existent file.
 * A `:`line reference is still allowed after the extension (`:` is not in the boundary class).
 *
 * The component character class is ASCII-safe (`[A-Za-z0-9._\-]+`): any non-ASCII script naturally
 * *terminates* the path instead of being absorbed, so a sentence like `扫描src/main/Foo.kt失效的key` links
 * only `src/main/Foo.kt` and never the surrounding prose, for every language. (Trade-off: a name containing
 * non-ASCII chars is not linked.)
 *
 * **Groups:** 1 = full path (without extension), 2 = extension, 3 = line, 4 = range end, 5 = column.
 */
internal val PATH_PATTERN: Pattern = Pattern.compile(
    """(?<![\\/\w.])((?:(?:[A-Za-z]:[\\/]?)|[\\/]|[~][\\/]?|\\\\[A-Za-z0-9._\-]+(?:[\\/][A-Za-z0-9._\-]+)+|[A-Za-z0-9._\-]+[\\/])(?:[A-Za-z0-9._\-]+[\\/])*(?:[A-Za-z0-9._\-]+\.((?i:$PROGRAMMING_EXT))(?![\\/\w.])|[A-Za-z0-9._\-]+))(?::(\d+)(?:-(\d+))?(?::(\d+))?)?"""
)

/**
 * Quoted path (allows embedded spaces), e.g. `"/path with space/Bar.kt":5`. Requires an absolute-ish path
 * (starts with `/` or a drive) ending in a recognized programming extension. A `…` (U+2026) or three ASCII
 * dots `...` inside the quotes marks a truncated path and is dropped by [FileLinkFilter].
 *
 * **Groups:** 1 = opening quote, 2 = path, 3 = line, 4 = column.
 */
internal val QUOTED_PATH_PATTERN: Pattern = Pattern.compile(
    """(["'])((?:[A-Za-z]:)?[\\/][^"']*?\.(?i:$PROGRAMMING_EXT))\1(?::(\d+))?(?::(\d+))?"""
)

/**
 * Bare `FileName.ext:line` / `FileName.ext:line:col` with no directory component (stack-trace frame,
 * e.g. `at com.foo.Bar.method(Bar.java:123)`). Drives [com.cnsharp.yolo.panel.StackTraceLinkFilter].
 *
 * **Groups:** 1 = file, 2 = line, 3 = column.
 */
internal val STACK_BARE_PATTERN: Pattern = Pattern.compile(
    """(?<![\\/\w.\-])([\w.\-]+\.(?i:$PROGRAMMING_EXT)):(\d+)(?::(\d+))?"""
)

/**
 * Names that are *only* ever dotfiles — never a trailing extension (`foo.gitignore` does not exist):
 * `.gitignore`, `.dockerignore`, `.editorconfig`. [STACK_BARE_NAME_PATTERN] links the leading-dot form
 * for these, but not for ordinary extensions, so a bare `.env`/`.json` is not linked while `foo.env` is.
 */
internal val DOTFILE_NAMES: String = listOf("gitignore", "dockerignore", "editorconfig").joinToString("|")

/**
 * Bare file name with no line number, e.g. `plugin.xml`, `build.gradle.kts` — only the base name. The
 * extension must be a recognized programming extension so a dotted non-file (`pay.amount.mark`) is never
 * linked, and a trailing boundary is required so it does not grab the start of a longer path or a
 * `name:line` reference.
 *
 * A real base name is **required** before the extension: `[\w.\-]+\.` needs at least one name character
 * before the dot, so a bare `.env` / `.json` — a lone extension the agent did not print as a file — is not
 * linked; only a name-carrying `foo.env` / `index.js` is. The sole exception is a true dotfile whose name
 * is never an extension ([DOTFILE_NAMES] — `.gitignore`, `.dockerignore`, `.editorconfig`), which links on
 * its own. Requiring the name+dot also stops a lone word from being read as an extension: several
 * extensions are single letters (`s` = assembly, `c`, `h`, `m`, `d`, `r`) or common English words (`go`,
 * `log`, `env`), so otherwise a possessive/contraction (`web's`) or a phrase (`let's go`) would link the
 * trailing `s`/`go`.
 *
 * **Groups:** 1 = file.
 */
internal val STACK_BARE_NAME_PATTERN: Pattern = Pattern.compile(
    """(?<![\\/\w.\-])([\w.\-]+\.(?i:$PROGRAMMING_EXT)|\.(?:$DOTFILE_NAMES))(?![\\/\w.:])"""
)

/** Python traceback `File "path", line N` (double-quoted). **Groups:** 1 = file, 2 = line. */
internal val STACK_PY_DQ_PATTERN: Pattern = Pattern.compile(
    """File "([^"]+\.(?i:$PROGRAMMING_EXT))", line (\d+)"""
)

/** Python traceback `File 'path', line N` (single-quoted). **Groups:** 1 = file, 2 = line. */
internal val STACK_PY_SQ_PATTERN: Pattern = Pattern.compile(
    """File '([^']+\.(?i:$PROGRAMMING_EXT))', line (\d+)"""
)

/**
 * Type references: a qualified name (`com.foo.Bar`, inner classes `com.foo.Bar.Baz`, C# `MyApp.Services.UserService`,
 * Rust/Ruby `foo::Bar`, PHP `\App\Models\User`, Python/Go `myapp.models.User` / `http.Client`) or a simple
 * capitalized identifier (`Bar`). Neither may be preceded by a word char/dot/path separator; a trailing
 * `.lowercase` is excluded so a member access (`Class.member`) is left for [com.cnsharp.yolo.panel.MemberLinkFilter]
 * rather than swallowed here. The pattern also refuses to match when immediately followed by a member
 * separator + identifier (`Class#member` / `Class.Member`, `.` excluded only when lowercase), so the
 * whole reference is left for [com.cnsharp.yolo.panel.MemberLinkFilter] to resolve to the member. Drives
 * [com.cnsharp.yolo.panel.TypeLinkFilter].
 *
 * Qualified segments are joined by `.`, `::`, or `\` — the namespace separators used across languages — so
 * one pattern covers them all; actual resolution/gating is delegated to [com.cnsharp.yolo.panel.YoloProjectTypes]
 * + the language-agnostic `gotoClassContributor` EP.
 *
 * The simple branch is case-*shape* sensitive: it requires a `PascalCase` type name (starts uppercase, not
 * all-uppercase), so all-caps tokens like `OK`, `BUILD`, `SUCCESSFUL` are rejected — they are constant/acronym
 * style, not type names, and would otherwise link whenever the project happens to define a same-named type.
 * Qualified names are unaffected.
 *
 * **Named groups:** `qualified`, `simple` (mutually exclusive — exactly one is non-null per match).
 */
internal val TYPE_NAME_PATTERN: Pattern = Pattern.compile(
    """(?<![.\w/\\])(?<qualified>\\?(?:[A-Za-z_][A-Za-z0-9_]*+)(?:(?:\.|::|\\)[A-Za-z_][A-Za-z0-9_]*+)+)(?!\.[a-z])(?![#.][A-Za-z_]\w*)""" +
        """|(?<![.\w/\\])(?<simple>(?![A-Z]+\b)[A-Z][a-zA-Z0-9_]*+)(?!\.[a-z])(?![#.][A-Za-z_]\w*)"""
)

/**
 * `Class.member` / `Class#member` references, navigating to the specific method/field/inner class. A class
 * reference (qualified name in any language's namespace convention, or a simple capitalized identifier, not
 * preceded by a word/dot/path separator) followed by `.`/`#` and a member name; not preceded by a path
 * separator so a path segment like `messages/YoloBundle` is never mistaken for a class. Drives
 * [com.cnsharp.yolo.panel.MemberLinkFilter].
 *
 * The class part reuses the multi-separator qualified form (`.` / `::` / `\`; any-case segments) so C#
 * `MyApp.Services.UserService.SomeMethod`, Python `myapp.models.User.save`, etc. are recognized too.
 *
 * The member name is **not** preceded by a `(?<![.\w])` lookbehind: that anchor would be evaluated at the
 * position right after the `[.#]` separator, where the preceding char is always `.` or `#`, so it can only
 * ever succeed for the `#` form and silently rejects the `.` form (`Class.member`) — the primary case the
 * filter exists for. The `[.#]` separator already guarantees the boundary the lookbehind was meant to enforce.
 *
 * **Named groups:** `class`, `member`.
 */
internal val MEMBER_REF_PATTERN: Pattern = Pattern.compile(
    """(?<class>(?<![.\w/\\])(?:\\?(?:[A-Za-z_][A-Za-z0-9_]*+)(?:(?:\.|::|\\)[A-Za-z_][A-Za-z0-9_]*+)+)|[A-Z][a-zA-Z0-9_]*+)[.#](?<member>[A-Za-z_]\w*)"""
)

/** `http(s)://` URLs (no trailing whitespace/quote/bracket). Drives [com.cnsharp.yolo.panel.UrlLinkFilter]. */
internal val URL_PATTERN: Pattern = Pattern.compile("""https?://[^\s<>"'\)\]]+""")

/**
 * Trailing type-name fragment reaching end-of-line — a qualified name (`com.foo.Ba`) that the terminal
 * hard-wrapped just before its final segment. Captured as group `qualified`. Used by
 * [com.cnsharp.yolo.panel.TypeLinkFilter] to stitch the fragment to the next physical line
 * (`com.foo.Ba` + `r` → `com.foo.Bar`). Only the qualified (separator-containing) form matches, so an
 * ordinary capitalized word at end-of-line is never held as a pending prefix.
 */
internal val TYPE_HEAD_PATTERN: Pattern = Pattern.compile(
    """(?<![.\w/\\])(?<qualified>\\?(?:[A-Za-z_][A-Za-z0-9_]*+)(?:(?:\.|::|\\)[A-Za-z_][A-Za-z0-9_]*+)+)\s*$"""
)

/**
 * Trailing class-part fragment reaching end-of-line — a `Class` (qualified or capitalized simple) that the
 * terminal hard-wrapped just before its `#member` / `.member`. Captured as group `class`. Used by
 * [com.cnsharp.yolo.panel.MemberLinkFilter] to stitch the fragment to the next physical line
 * (`Foo.impl.Ba` + `r#method` → `Foo.impl.Bar#method`).
 */
internal val MEMBER_HEAD_PATTERN: Pattern = Pattern.compile(
    """(?<![.\w/\\])(?<class>(?:(?:\\?(?:[A-Za-z_][A-Za-z0-9_]*+)(?:(?:\.|::|\\)[A-Za-z_][A-Za-z0-9_]*+)+)|[A-Z][a-zA-Z0-9_]*+))\s*$"""
)

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

