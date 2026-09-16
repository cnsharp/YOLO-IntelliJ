package com.cnsharp.yolo.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [FileLinkFilter.apply] — the pure link-matching logic — against the reported false-link
 * regressions: dotted non-files (`pay.amount.mark`), truncated paths (`…`/`...`), CJK prose, and long
 * paths the terminal hard-wraps across lines. The click handler (resolution) is intentionally NOT invoked,
 * so these tests never touch the filesystem or PSI indices — they only assert *what* got linked.
 *
 * Runs without the IntelliJ `BasePlatformTestCase` fixture: [FileLinkFilter] only captures `project` for
 * the deferred click handler, so `apply()` is fully testable with a `null` project (the link's `Runnable`
 * is constructed but never executed here). This keeps `:test` offline — it reuses the local IDEA platform
 * classpath used for compilation instead of the plugin's `testFramework(...)` helper, which cannot parse
 * IDEA 2026.2's module descriptors.
 */
class FileLinkFilterTest {

    private fun linked(text: String): List<String> {
        val filter = FileLinkFilter(null, "/tmp/agent-working-dir")
        return filter.apply(text)
            ?.items
            ?.map { text.substring(it.startOffset, it.endOffset) }
            .orEmpty()
    }

    @Test
    fun testRelativePathWithExtension() {
        assertEquals(listOf("src/main/Foo.kt"), linked("see src/main/Foo.kt here"))
    }

    @Test
    fun testPathWithLineAndColumn() {
        assertEquals(listOf("src/foo/Bar.kt:42:13"), linked("error at src/foo/Bar.kt:42:13"))
    }

    @Test
    fun testAbsoluteUnixPath() {
        assertEquals(listOf("/abs/Bar.java"), linked("file /abs/Bar.java not found"))
    }

    @Test
    fun testWindowsPath() {
        assertEquals(listOf("C:\\foo\\Bar.kt"), linked("open C:\\foo\\Bar.kt"))
    }

    @Test
    fun testLineReferenceWithoutExtensionStillLinks() {
        // `:line` satisfies the completion requirement even when there is no extension.
        assertEquals(listOf("./Makefile:10"), linked("edit ./Makefile:10"))
    }

    @Test
    fun testDirectoryReferenceWithoutExtensionOrLineIsNotLinked() {
        // `src/main/resources/config` has no extension and no line — it is a directory, not openable, and
        // (more importantly) could be a wrapped-path fragment; it must not be linked.
        assertTrue(linked("under src/main/resources/config now").isEmpty())
    }

    @Test
    fun testDottedNonFileIsNotLinked() {
        // `pay.amount.mark` is not a file — no recognized extension, no line number.
        assertTrue(linked("value pay.amount.mark changed").isEmpty())
    }

    @Test
    fun testTruncatedEllipsisPathIsNotLinked() {
        assertTrue(linked("open /Users/me/Proj…name please").isEmpty())
    }

    @Test
    fun testTruncatedAsciiDotPathIsNotLinked() {
        assertTrue(linked("open /Users/me/Proj...name please").isEmpty())
    }

    @Test
    fun testTruncatedMiddleEllipsisTailIsNotLinked() {
        // A path abbreviated with `…` in the middle: the fragment after the marker is the tail of a
        // truncated path, not a real file, so neither the head (`com/cnshar`) nor the tail
        // (`entExtenderConfigurable.kt`) may link.
        assertTrue(linked("Read(src/main/kotlin/com/cnshar…entExtenderConfigurable.kt)").isEmpty())
    }

    @Test
    fun testTruncatedMiddleEllipsisWithFurtherPathIsNotLinked() {
        // The tail can itself be a longer path (slash right after `…`); it must still not link.
        assertTrue(linked("open src/main/kotlin/com/cnshar…/real/AgentExtenderConfigurable.kt end").isEmpty())
    }

    @Test
    fun testCjkSentenceLinksOnlyThePath() {
        assertEquals(listOf("src/main/Foo.kt"), linked("扫描src/main/Foo.kt失效的key"))
    }

    @Test
    fun testLongPathLinksOnceWhenNotWrapped() {
        val path = "./app/biz/service-impl/target/order-biz-service-impl-5.584.3-SNAPSHOT/WEB-INF/classes/config/business.properties"
        assertEquals(listOf(path), linked(path))
    }

    @Test
    fun testHyphenatedPathLinksEntirely() {
        // Paths containing hyphens (e.g. report filenames like `ParamConfigService-unused-fields.html`)
        // must not be split at the hyphen. The regex character class `[A-Za-z0-9._\-]` includes `-`,
        // but we keep a regression test so any future change that drops it is caught.
        val path = "docs/ParamConfigService-unused-fields.html"
        assertEquals(listOf(path), linked(path))
    }

    @Test
    fun testWrappedHeadFragmentIsNotLinked() {
        // Simulate the terminal hard-wrap: the head fragment has neither an extension nor a line number.
        val head = "./app/biz/service-impl/target/order-biz-service-impl-5.584.3-SNAPSHOT/WEB-INF/cla"
        assertTrue(linked(head).isEmpty())
    }

    @Test
    fun testWrappedTailFragmentStillLooksLikeAFile() {
        // The tail fragment ends in a recognized extension, so it is still recognized as a (single) file.
        // This is the unavoidable best case for a hard-wrapped path: the head is dropped, at most one
        // complete-looking fragment links.
        assertEquals(listOf("sses/config/business.properties"), linked("sses/config/business.properties"))
    }

    @Test
    fun testQuotedPathWithSpaces() {
        assertEquals(listOf("/path with space/Bar.kt"), linked("""open "/path with space/Bar.kt" now"""))
    }

    @Test
    fun testExtensionIsNotTruncatedToPrefix() {
        // A listed extension must match as a *full* extension, never as a prefix of a longer one.
        // Regression: the regex grabbed the first listed extension it could — `.markdown` linked only
        // `Foo.m`, `.kts` linked only `Foo.kt`, `.json5` only `Foo.json`. These all have a longer
        // recognized extension, so the whole name must link.
        assertEquals(listOf("src/main/Foo.markdown"), linked("cat src/main/Foo.markdown please"))
        assertEquals(listOf("src/main/Foo.kts"), linked("see src/main/Foo.kts here"))
        assertEquals(listOf("src/main/Foo.json5"), linked("open src/main/Foo.json5 end"))
        assertEquals(listOf("src/main/Foo.mjs"), linked("view src/main/Foo.mjs end"))
    }

    @Test
    fun testUnrecognizedExtensionIsNotLinked() {
        // Extensions that merely *contain* a listed one as a prefix but are not themselves recognized
        // (.module, .commit, .mlis, .more) must not link — and must not be truncated to a wrong file.
        assertTrue(linked("see src/main/Foo.module here").isEmpty())
        assertTrue(linked("edit src/main/Bar.commit now").isEmpty())
        assertTrue(linked("open src/main/Baz.mlis end").isEmpty())
        assertTrue(linked("view src/main/MyClass.more end").isEmpty())
    }

    @Test
    fun testRealSingleCharExtensionStillLinks() {
        // A genuine `.m` (Objective-C) file must still link — the boundary only rejects *prefix* matches.
        assertEquals(listOf("src/main/Foo.m"), linked("see src/main/Foo.m here"))
    }

    // ---- Supplementary cases: branches not covered above ----

    @Test
    fun testPathWithLineRange() {
        // A `path:start-end` range (e.g. a diff hunk) links the whole reference and opens at the start line.
        assertEquals(listOf("src/main/Foo.kt:12-20"), linked("changed src/main/Foo.kt:12-20"))
    }

    @Test
    fun testQuotedPathWithLineAndColumn() {
        // A quoted path with embedded spaces may carry `:line:column` outside the closing quote. The path
        // and the `:line:col` are two separate (quote-excluding) links; the closing quote is never clickable.
        assertEquals(
            listOf("/path with space/Bar.kt", ":42:13"),
            linked("""open "/path with space/Bar.kt":42:13 now"""),
        )
    }

    @Test
    fun testQuotedPathTruncatedNotLinked() {
        // A quoted path the agent abbreviated with `…` mid-way is incomplete and must not link (the
        // `raw.contains('…')` guard in the quoted loop).
        assertTrue(linked("""open "/Users/me/Proj…name/Bar.kt" end""").isEmpty())
    }

    @Test
    fun testWindowsPathWithLineAndColumn() {
        assertEquals(listOf("""C:\foo\Bar.kt:42:13"""), linked("""error at C:\foo\Bar.kt:42:13"""))
    }

    @Test
    fun testHomePathWithLine() {
        // A `~`-prefixed (home-relative) path with a line reference must link.
        assertEquals(listOf("~/foo/Bar.kt:3"), linked("edit ~/foo/Bar.kt:3 please"))
    }

    @Test
    fun testMultiplePathsInOneLine() {
        // Two independent references on one line each become their own link.
        assertEquals(listOf("src/a/Foo.kt", "src/b/Bar.java"), linked("edit src/a/Foo.kt and src/b/Bar.java"))
    }

    // ---- Regression: the resolved path must not double-append the extension ----
    // A [PATH_PATTERN] match's group 1 already contains the extension, so the target is just `raw`.
    // Historically the code re-appended `.${ext}`, producing `src/main/Foo.kt.kt`, which [resolve] could
    // never find — the link showed up but clicking navigated nowhere ("不能准确定位到", e.g. a
    // `.venus/…tsv` reference that never opened). These assertions fail if the re-append is reintroduced.
    @Test
    fun testResolvedPathHasNoDoubleExtension() {
        assertEquals("src/main/Foo.kt", fileLinkTarget("src/main/Foo.kt", "kt"))
        assertEquals(".venus/venus_marked_delete_order_test1_20260819_155241.tsv",
            fileLinkTarget(".venus/venus_marked_delete_order_test1_20260819_155241.tsv", "tsv"))
        assertEquals("/abs/Bar.java", fileLinkTarget("/abs/Bar.java", "java"))
        // No-extension match (carried by `:line`): target is the raw path as-is.
        assertEquals("./Makefile", fileLinkTarget("./Makefile", null))
    }

    @Test
    fun testUnquotedPathResolvesToSingleExtension() {
        // End-to-end detection check: a relative `.tsv` reference links as the exact single-extension path
        // (no doubled `.tsv.tsv`), so `resolve` is given a path that actually exists.
        val ref = ".venus/venus_marked_delete_order_test1_20260819_155241.tsv"
        assertEquals(listOf(ref), linked("see $ref here"))
    }

    // ---- Hard-wrap path reconstruction ----

    private fun linkedWithState(line1: String, line2: String): List<String> {
        val state = WrapState()
        val filter = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        filter.apply(line1)       // processes head line, sets pendingPath
        return filter.apply(line2)
            ?.items
            ?.map { line2.substring(it.startOffset, it.endOffset) }
            .orEmpty()
    }

    @Test
    fun testHardWrapHighlightsBothRowsAndCompletesHead() {
        // A wrapped path must not be left half-painted: the head row is highlighted too. It alone is only a
        // fragment, so it opens whatever the continuation completes it to — the full path plus :line.
        val state = WrapState()
        val filter = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        val head = "app/biz/service-impl/src/main/java/com/cnsharp/order/biz/service/statemachine/Orde"
        val headLinked = filter.apply(head)
            ?.items
            ?.map { head.substring(it.startOffset, it.endOffset) }
            .orEmpty()
        assertEquals("the head row must be highlighted", listOf(head), headLinked)
        val tail = "rTransitionContext.java:42"
        val tailLinked = filter.apply(tail)
            ?.items
            ?.map { tail.substring(it.startOffset, it.endOffset) }
            .orEmpty()
        assertEquals("the continuation row must be highlighted", listOf(tail), tailLinked)
        // The head link resolves the reconstructed reference — same file and line as the continuation.
        assertEquals(
            WrapState.CompletedPath(
                "app/biz/service-impl/src/main/java/com/cnsharp/order/biz/service/statemachine/OrderTransitionContext.java",
                42,
                null
            ),
            state.completedPaths[head]
        )
    }

    @Test
    fun testHardWrapHeadSetsPendingPrefix() {
        val state = WrapState()
        val filter = FileLinkFilter(null, "/tmp", state)
        filter.apply("app/biz/service-impl/src/main/java/com/cnsharp/order/biz/service/statemachine/Orde")
        assertEquals(
            "app/biz/service-impl/src/main/java/com/cnsharp/order/biz/service/statemachine/Orde",
            state.pendingPath
        )
    }

    @Test
    fun testHardWrapPathReconstructedOnContinuationLine() {
        // The reported bug: `…/statemachine/Orde` wraps; the continuation `rTransitionContext.java`
        // must be linked as the tail of the full reconstructed path.
        val tail = "        rTransitionContext.java"
        val linked = linkedWithState(
            "app/biz/service-impl/src/main/java/com/cnsharp/order/biz/service/statemachine/Orde",
            tail
        )
        assertEquals(listOf("rTransitionContext.java"), linked)
    }

    @Test
    fun testHardWrapPathWithLineReconstructed() {
        // Same, but the continuation line also carries a `:line` suffix.
        val tail = "rTransitionContext.java:42"
        val linked = linkedWithState(
            "app/biz/service-impl/src/main/java/com/cnsharp/order/biz/service/statemachine/Orde",
            tail
        )
        assertEquals(listOf("rTransitionContext.java:42"), linked)
    }

    @Test
    fun testHardWrapHeadEndingInSlashSetsPendingPrefix() {
        // The terminal wrapped exactly at a path separator: the head line ends with '/'. The trailing
        // separator must be preserved in the prefix so the reconstructed path keeps its delimiter.
        val state = WrapState()
        val filter = FileLinkFilter(null, "/tmp", state)
        filter.apply("app/biz/service-impl/src/main/java/com/cnsharp/order/biz/service/")
        assertEquals(
            "app/biz/service-impl/src/main/java/com/cnsharp/order/biz/service/",
            state.pendingPath
        )
    }

    @Test
    fun testHardWrapPathEndingInSlashReconstructed() {
        // The reported bug: the head `…/biz/service/` wraps at a separator; the continuation
        // `statemachine/config/StatusGrayScaleConfigBean.java` must be linked as ONE tail of the
        // reconstructed path — not dropped, and without a duplicate link from the standalone pass.
        val tail = "statemachine/config/StatusGrayScaleConfigBean.java"
        val linked = linkedWithState(
            "app/biz/service-impl/src/main/java/com/cnsharp/order/biz/service/",
            tail
        )
        assertEquals(listOf(tail), linked)
    }

    @Test
    fun testExtensionMidWrapDoesNotProducePhantomLink() {
        // When the terminal wraps INSIDE the extension (e.g. "build.gradl" | "e"), the pending prefix
        // must NOT be stored — its last segment contains a dot, indicating a partial extension — so
        // the continuation line's single letter is never linked.
        val state = WrapState()
        val filter = FileLinkFilter(null, "/tmp", state)
        filter.apply("some/path/build.gradl")
        assertEquals("extension-split head must not set pendingPath", "", state.pendingPath)
        // The continuation line must produce no link.
        val result = filter.apply("e")
        assertTrue(result == null || result.items.isEmpty())
    }

    @Test
    fun testBlankLineBreaksWrapSequence() {
        // An empty line between head and continuation clears the pending prefix.
        val state = WrapState()
        val filter = FileLinkFilter(null, "/tmp", state)
        filter.apply("src/main/java/com/example/OrderTrans")
        filter.apply("")            // blank line — must clear pendingPath
        assertEquals("", state.pendingPath)
        // Continuation line is now treated standalone, not as a reconstruction target.
        val result = filter.apply("actionContext.java")
        // Without a prefix the standalone "actionContext.java" has a directory component? No —
        // StackTraceLinkFilter would link it, but FileLinkFilter (PATH_PATTERN) won't since it
        // has no directory component. Verify FileLinkFilter produces nothing.
        assertTrue(result == null || result.items.isEmpty())
    }

    // ---- Placeholder-truncated paths (`...`/`…` as a standalone segment) ----

    private fun linkedBothLines(line1: String, line2: String): Pair<List<String>, List<String>> {
        val state = WrapState()
        val filter = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        val head = filter.apply(line1)
            ?.items
            ?.map { line1.substring(it.startOffset, it.endOffset) }
            .orEmpty()
        val tail = filter.apply(line2)
            ?.items
            ?.map { line2.substring(it.startOffset, it.endOffset) }
            .orEmpty()
        return head to tail
    }

    @Test
    fun testPlaceholderTruncationLinksLastFilename() {
        // A `...` segment means "middle directories omitted"; the final filename is intact and must link.
        // Regression for `libra-statis/.../listener/ChannelConfigSyncListener.java` breaking at `Sync`.
        assertEquals(
            listOf("ChannelConfigSyncListener.java"),
            linked("libra-statis/.../listener/ChannelConfigSyncListener.java")
        )
    }

    @Test
    fun testEllipsisPlaceholderNotLinkableByPathPattern() {
        // The U+2026 form (`/…/`) is a placeholder too, but PATH_PATTERN's ASCII-safe path class
        // *terminates* at `…` (by design, so CJK prose around a path never leaks in), so the reference
        // is not even matched as one path — only the ASCII `...` form links the last filename. This pins
        // that boundary: the `…` form must stay unlinked (the fragment before `…` is just a dir).
        assertTrue(linked("libra-statis/…/listener/ChannelConfigSyncListener.java").isEmpty())
    }

    @Test
    fun testInlineTruncationStillNotLinked() {
        // An inline abbreviation truncates a *component*; its tail is unreliable, so nothing links.
        // These must stay empty (existing behaviour — the tail is not a real file).
        assertTrue(linked("/Users/me/Proj...name").isEmpty())
        assertTrue(linked("src/main/kotlin/com/cnshar…/real/AgentExtenderConfigurable.kt").isEmpty())
    }

    @Test
    fun testWrappedPlaceholderTruncationLinksFilenameOnContinuation() {
        // The path wraps mid-filename AND contains a `...` placeholder: the head (`.../listener/ChannelConfigSync`)
        // must NOT be a dead half-link, and the continuation (`Listener.java`) must carry the filename link.
        val (head, tail) = linkedBothLines(
            "libra-statis/.../listener/ChannelConfigSync",
            "Listener.java"
        )
        assertTrue("truncated head must not be linked as a dead half-link", head.isEmpty())
        assertEquals(listOf("Listener.java"), tail)
    }

    @Test
    fun testWrappedPlaceholderTruncationFilenameNotSplit() {
        // The `...` placeholder is in the middle but the whole filename lands on the continuation line.
        val (head, tail) = linkedBothLines(
            "libra-statis/.../listener",
            "ChannelConfigSyncListener.java"
        )
        assertTrue("truncated head (directory fragment) must not be linked", head.isEmpty())
        assertEquals(listOf("ChannelConfigSyncListener.java"), tail)
    }
}
