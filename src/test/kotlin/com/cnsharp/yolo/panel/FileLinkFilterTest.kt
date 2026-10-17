package com.cnsharp.yolo.panel

import com.intellij.openapi.project.Project
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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

    // ---- Regression: `.sql` (and other SQL-family) references must link ----
    // PROGRAMMING_EXT previously omitted `sql`, so a `.sql` reference — single line or hard-wrapped — was
    // never recognized as a file: the standalone line produced no link, and a wrapped reference left the head
    // row a dead fragment while the continuation (the actual filename) stayed unlinked. The user reported
    // exactly this ("第二行没链接上") for a wrapped `/Users/.../some_table.sql`.

    @Test
    fun testSqlPathLinksOnOneLine() {
        assertEquals(listOf("foo/bar/some_table.sql"), linked("see foo/bar/some_table.sql here"))
    }

    @Test
    fun testSqlPathHardWrapReconstructed() {
        // Mirrors the reported case: `/Users/.../some_` wraps, the indented continuation `table.sql`
        // must link as the tail of the reconstructed full path (not be dropped).
        val tail = "    table.sql"
        val linked = linkedWithState(
            "/Users/cnsharp/IdeaProjects/foo/bar/some_",
            tail
        )
        assertEquals(listOf("table.sql"), linked)
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
    fun testThreeLineHardWrapReconstructed() {
        // A path that wraps across THREE physical lines (terminal width) must still reconstruct into one
        // link on the final filename, and — crucially — the reconstructed path must keep its leading
        // directories (the earlier rows' accumulated prefix), not collapse to a relative fragment. This is
        // the regression: a 3-line wrap used to corrupt `pendingPath` on the middle row and navigate to the
        // wrong file (or none).
        val state = WrapState()
        val filter = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        val l0 = filter.apply("/Users/admin/IdeaProjects/account-system/account-biz")
            ?.items?.map { "/Users/admin/IdeaProjects/account-system/account-biz".substring(it.startOffset, it.endOffset) }.orEmpty()
        val l1 = filter.apply("  /src/main/java/com/cnsharp/account/mq/tdmq/producer/Deduct")
            ?.items?.map { "  /src/main/java/com/cnsharp/account/mq/tdmq/producer/Deduct".substring(it.startOffset, it.endOffset) }.orEmpty()
        val l2 = filter.apply("  SuccessProducer.java")
            ?.items?.map { "  SuccessProducer.java".substring(it.startOffset, it.endOffset) }.orEmpty()
        assertEquals("head row must be highlighted", listOf("/Users/admin/IdeaProjects/account-system/account-biz"), l0)
        assertEquals("intermediate wrap row must be highlighted", listOf("/src/main/java/com/cnsharp/account/mq/tdmq/producer/Deduct"), l1)
        assertEquals("final filename must link the reconstructed path", listOf("SuccessProducer.java"), l2)
        // The reconstructed target must be the FULL absolute path, with leading dirs preserved.
        assertEquals(
            "reconstructed path must keep leading directories",
            "/Users/admin/IdeaProjects/account-system/account-biz/src/main/java/com/cnsharp/account/mq/tdmq/producer/DeductSuccessProducer.java",
            state.lastCompletedPath?.path
        )
    }

    @Test
    fun testFourLineHardWrapReconstructed() {
        // Four physical lines: every head row must still navigate to the same completed file (chain-wide
        // completion), not just the row immediately preceding the filename.
        val state = WrapState()
        val filter = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        val head = "/Users/admin/IdeaProjects/account-system/account-biz"
        val r0 = filter.apply(head)?.items?.map { head.substring(it.startOffset, it.endOffset) }.orEmpty()
        val mid = "  /src/main/java/com/cnsharp/account/mq"
        val r1 = filter.apply(mid)?.items?.map { mid.substring(it.startOffset, it.endOffset) }.orEmpty()
        val mid2 = "  /tdmq/producer/Deduct"
        val r2 = filter.apply(mid2)?.items?.map { mid2.substring(it.startOffset, it.endOffset) }.orEmpty()
        val tail = "  SuccessProducer.java"
        val r3 = filter.apply(tail)?.items?.map { tail.substring(it.startOffset, it.endOffset) }.orEmpty()
        assertEquals(listOf(head), r0)
        assertEquals(listOf("/src/main/java/com/cnsharp/account/mq"), r1)
        assertEquals(listOf("/tdmq/producer/Deduct"), r2)
        assertEquals(listOf("SuccessProducer.java"), r3)
        assertEquals(
            "/Users/admin/IdeaProjects/account-system/account-biz/src/main/java/com/cnsharp/account/mq/tdmq/producer/DeductSuccessProducer.java",
            state.lastCompletedPath?.path
        )
    }

    @Test
    fun testWrapKeepsCompletionAfterFollowingOutput() {
        // Regression: a wrapped path's completion must survive the ordinary lines the agent prints *after*
        // it. The earlier row links navigate via `lastCompletedPath`, so if it were cleared on every line
        // only the final filename would stay clickable — the user reported exactly that.
        val state = WrapState()
        val filter = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        filter.apply("/Users/admin/IdeaProjects/account-system/account-biz")
        filter.apply("  /src/main/java/com/cnsharp/account/mq/tdmq/producer/Deduct")
        filter.apply("  SuccessProducer.java")
        assertEquals(
            "completion must be present right after the wrap",
            "/Users/admin/IdeaProjects/account-system/account-biz/src/main/java/com/cnsharp/account/mq/tdmq/producer/DeductSuccessProducer.java",
            state.lastCompletedPath?.path
        )
        // Agent keeps printing normal output afterwards (no blank line, no new path).
        filter.apply("done")
        filter.apply("see above file for details")
        assertEquals(
            "completion must survive following ordinary output",
            "/Users/admin/IdeaProjects/account-system/account-biz/src/main/java/com/cnsharp/account/mq/tdmq/producer/DeductSuccessProducer.java",
            state.lastCompletedPath?.path
        )
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
    fun testWrapKeepsCompletionAfterBlankLine() {
        // Regression for the user's "还是不行" after a completed multi-line wrap: agent output usually
        // follows a wrapped path with normal text — frequently including blank lines. A blank line must
        // clear the *pending prefix* (so a later line isn't mistaken for a continuation) but must NOT clear
        // `lastCompletedPath`, or the wrapped path's earlier rows become dead links. This pins that the
        // completion survives a trailing blank line.
        val state = WrapState()
        val filter = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        filter.apply("/Users/admin/IdeaProjects/account-system/account-biz")
        filter.apply("  /src/main/java/com/cnsharp/account/mq/tdmq/producer/Deduct")
        filter.apply("  SuccessProducer.java")
        assertEquals(
            "completion must be present right after the wrap",
            "/Users/admin/IdeaProjects/account-system/account-biz/src/main/java/com/cnsharp/account/mq/tdmq/producer/DeductSuccessProducer.java",
            state.lastCompletedPath?.path
        )
        // A blank line appears after the completed wrap (normal agent output).
        filter.apply("")
        assertEquals(
            "pending prefix must be cleared by the blank line",
            "",
            state.pendingPath
        )
        assertEquals(
            "completion must survive the blank line",
            "/Users/admin/IdeaProjects/account-system/account-biz/src/main/java/com/cnsharp/account/mq/tdmq/producer/DeductSuccessProducer.java",
            state.lastCompletedPath?.path
        )
        // More ordinary output after the blank line — completion must still be alive.
        filter.apply("done")
        assertEquals(
            "completion must survive following output after the blank line",
            "/Users/admin/IdeaProjects/account-system/account-biz/src/main/java/com/cnsharp/account/mq/tdmq/producer/DeductSuccessProducer.java",
            state.lastCompletedPath?.path
        )
    }

    // ---- Bare-name (path-less) hard-wrap reconstruction ----
    // A file name with no directory prefix that the terminal hard-wrapped mid-name, e.g.
    //   AccountD
    //   ataMigrationDispatcher.java:18
    // is matched by STACK_BARE_PATTERN (not PATH_PATTERN), so it needs its own reconstruction. The head
    // fragment (AccountD) must also become clickable and navigate to the reconstructed name.

    @Test
    fun testBareNameTwoLineHardWrapReconstructed() {
        val state = WrapState()
        val filter = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        val head = "AccountD"
        val headLinked = filter.apply(head)
            ?.items?.map { head.substring(it.startOffset, it.endOffset) }.orEmpty()
        assertEquals("the bare head fragment must be highlighted", listOf("AccountD"), headLinked)
        val tail = "ataMigrationDispatcher.java:18"
        val tailLinked = filter.apply(tail)
            ?.items?.map { tail.substring(it.startOffset, it.endOffset) }.orEmpty()
        assertEquals("the continuation must be highlighted", listOf("ataMigrationDispatcher.java:18"), tailLinked)
        assertEquals(
            "reconstructed name must be the full joined file name",
            "AccountDataMigrationDispatcher.java",
            state.lastCompletedBare?.path
        )
        assertEquals(18, state.lastCompletedBare?.line)
    }

    @Test
    fun testBareNameThreeLineHardWrapReconstructed() {
        val state = WrapState()
        val filter = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        val h0 = filter.apply("AccountData")?.items?.map { "AccountData".substring(it.startOffset, it.endOffset) }.orEmpty()
        val mid = "MigrationDis"
        val h1 = filter.apply(mid)?.items?.map { mid.substring(it.startOffset, it.endOffset) }.orEmpty()
        val tail = "patcher.java:18"
        val h2 = filter.apply(tail)?.items?.map { tail.substring(it.startOffset, it.endOffset) }.orEmpty()
        assertEquals(listOf("AccountData"), h0)
        assertEquals(listOf("MigrationDis"), h1)
        assertEquals(listOf("patcher.java:18"), h2)
        assertEquals(
            "every row must navigate to the same reconstructed name",
            "AccountDataMigrationDispatcher.java",
            state.lastCompletedBare?.path
        )
    }

    @Test
    fun testBareNameWrapKeepsCompletionAfterBlankLine() {
        // Like the path case: a blank line after a completed bare wrap must clear the pending fragment but
        // keep lastCompletedBare alive, or the head row turns into a dead link.
        val state = WrapState()
        val filter = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        filter.apply("AccountD")
        filter.apply("ataMigrationDispatcher.java:18")
        assertEquals("AccountDataMigrationDispatcher.java", state.lastCompletedBare?.path)
        filter.apply("") // blank line
        assertEquals("pending fragment must be cleared", "", state.pendingBareName)
        assertEquals("completion must survive the blank line", "AccountDataMigrationDispatcher.java", state.lastCompletedBare?.path)
        filter.apply("done")
        assertEquals("completion must survive following output", "AccountDataMigrationDispatcher.java", state.lastCompletedBare?.path)
    }

    @Test
    fun testPathHeadLineStillLinkedDespiteBareHeadGuard() {
        // A path head like `/foo/Bar` (no extension) must NOT be clobbered by the bare-head branch: the
        // line contains a '/', so the bare-head branch is skipped and the path head remains clickable.
        val state = WrapState()
        val filter = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        val head = "/src/main/java/com/cnsharp/order/biz/service/Bar"
        val linked = filter.apply(head)
            ?.items?.map { head.substring(it.startOffset, it.endOffset) }.orEmpty()
        assertEquals(listOf(head), linked)
    }

    @Test
    fun testFilePathWrapBeforeFileSegment() {
        // User-reported real case: a path-prefixed reference that hard-wraps right before the file name.
        //   src/test/kotlin/com/cnsharp/yolo/panel/
        //   FileLinkFilterTest.kt
        // Both rows must navigate to the reconstructed file; neither may be a dead link.
        val state = WrapState()
        val f = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        val head = "src/test/kotlin/com/cnsharp/yolo/panel/"
        val h = f.apply(head)
        val hLinked = h?.items?.map { head.substring(it.startOffset, it.endOffset) }.orEmpty()
        val cont = "FileLinkFilterTest.kt"
        val c = f.apply(cont)
        val cLinked = c?.items?.map { cont.substring(it.startOffset, it.endOffset) }.orEmpty()
        println("HEAD linked=$hLinked")
        println("CONT linked=$cLinked")
        println("lastCompletedPath=${state.lastCompletedPath}")
        // The head row should be highlighted.
        assertEquals(listOf(head), hLinked)
        // The continuation row's file name should be highlighted.
        assertEquals(listOf("FileLinkFilterTest.kt"), cLinked)
        assertEquals(
            "src/test/kotlin/com/cnsharp/yolo/panel/FileLinkFilterTest.kt",
            state.lastCompletedPath?.path
        )
    }

    @Test
    fun testSingleLineFullPathLinkedAsOneSpan() {
        // A full path on one line must be ONE link spanning the whole path, not split into the directory
        // part + the file-name part by an overlapping filter.
        val f = FileLinkFilter(null, "/tmp/agent-working-dir")
        val p = "src/test/kotlin/com/cnsharp/yolo/panel/FileLinkFilterTest.kt"
        val linked = f.apply(p)?.items?.map { p.substring(it.startOffset, it.endOffset) }.orEmpty()
        println("SINGLE-LINE linked=$linked")
        assertEquals("full path must be one link", listOf(p), linked)
    }

    @Test
    fun testSingleLineFullPathEmbeddedLinkedAsOneSpan() {
        val f = FileLinkFilter(null, "/tmp/agent-working-dir")
        val line = "see src/test/kotlin/com/cnsharp/yolo/panel/FileLinkFilterTest.kt here"
        val linked = f.apply(line)?.items?.map { line.substring(it.startOffset, it.endOffset) }.orEmpty()
        println("EMBEDDED linked=$linked")
        assertEquals(listOf("src/test/kotlin/com/cnsharp/yolo/panel/FileLinkFilterTest.kt"), linked)
    }

    @Test
    fun testFilePathWrapInsideFileSegment() {
        // Variant: the wrap splits the file name itself (...panel/File | LinkFilterTest.kt).
        val state = WrapState()
        val f = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        val head = "src/test/kotlin/com/cnsharp/yolo/panel/File"
        val h = f.apply(head)?.items?.map { head.substring(it.startOffset, it.endOffset) }.orEmpty()
        val cont = "LinkFilterTest.kt"
        val c = f.apply(cont)?.items?.map { cont.substring(it.startOffset, it.endOffset) }.orEmpty()
        println("HEAD2 linked=$h")
        println("CONT2 linked=$c")
        assertEquals(listOf(head), h)
        assertEquals(listOf("LinkFilterTest.kt"), c)
        assertEquals(
            "src/test/kotlin/com/cnsharp/yolo/panel/FileLinkFilterTest.kt",
            state.lastCompletedPath?.path
        )
    }

    @Test
    fun testBareWrapDiagnostic() {
        val state = WrapState()
        val f = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        val h = f.apply("AccountD")
        assertEquals("head must link AccountD", listOf("AccountD"),
            h?.items?.map { "AccountD".substring(it.startOffset, it.endOffset) }.orEmpty())
        assertEquals("pendingBareName must be set on head", "AccountD", state.pendingBareName)
        val cont = "  ataMigrationDispatcher.java:18"
        val c = f.apply(cont)
        assertEquals("continuation must link the whole tail",
            listOf("ataMigrationDispatcher.java:18"),
            c?.items?.map { cont.substring(it.startOffset, it.endOffset) }.orEmpty())
        assertTrue("continuationSpan must be set", state.continuationSpan != null)
        assertEquals("lastCompletedBare must be the full name",
            "AccountDataMigrationDispatcher.java", state.lastCompletedBare?.path)
    }

    @Test
    fun testStackTraceSkipsBareWrapTail() {
        // Diagnostic: after FileLinkFilter reconstructs a bare wrap, StackTraceLinkFilter (registered AFTER
        // FileLinkFilter, so it would win overlapping spans) must skip the partial-name match on the
        // continuation line, otherwise its dead `ataMigrationDispatcher.java` link overrides the good one.
        val state = WrapState()
        val fileFilter = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        fileFilter.apply("AccountD")
        fileFilter.apply("ataMigrationDispatcher.java:18")
        assertTrue("FileLinkFilter must set continuationSpan", state.continuationSpan != null)
        val stackFilter = StackTraceLinkFilter(null, "/tmp/agent-working-dir", state)
        val result = stackFilter.apply("ataMigrationDispatcher.java:18")
        assertTrue(
            "StackTraceLinkFilter must skip the overlapping partial-name match (got $result)",
            result == null || result.items.isEmpty()
        )
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

    // ===== Regression: the full pipeline must link a path-prefixed file reference as ONE span, even when
    // the file's base name is also a known project type. MemberLinkFilter used to match `Name.kt` as a
    // `Class.member` reference (treating the extension as a member) and, being registered after
    // FileLinkFilter, win the overlap — splitting `src/.../Name.kt` into `src/.../` + `Name.kt`. =====
    @Test
    fun testPipelineSingleLinePathIsOneSpan() {
        val dir = "/tmp/agent-working-dir"
        // Simulate runIde where `FileLinkFilterTest` is a real project type (a Kotlin class).
        val types = YoloProjectTypes.Snapshot(
            simple = setOf("FileLinkFilterTest"),
            files = setOf("FileLinkFilterTest"),
        )
        val typesProvider: (Project?) -> YoloProjectTypes.Snapshot = { types }
        val state = WrapState()
        val filters = listOf<HyperlinkFilter>(
            FileLinkFilter(null, dir, state),
            MemberLinkFilter(null, state, typesProvider),
            TypeLinkFilter(null, typesProvider, state),
            StackTraceLinkFilter(null, dir, state),
            UrlLinkFilter(),
        )
        // JediTerm merge: later filters win overlapping positions.
        val p = "src/test/kotlin/com/cnsharp/yolo/panel/FileLinkFilterTest.kt"
        val owner = Array(p.length) { -1 }
        val itemText = Array(p.length) { "" }
        filters.forEachIndexed { idx, f ->
            f.apply(p)?.items?.forEach { it2 ->
                for (q in it2.startOffset until it2.endOffset) {
                    owner[q] = idx
                    itemText[q] = p.substring(it2.startOffset, it2.endOffset)
                }
            }
        }
        val spans = mutableListOf<String>()
        var i = 0
        while (i < p.length) {
            val o = owner[i]
            if (o < 0) { i++; continue }
            var j = i
            while (j < p.length && owner[j] == o && itemText[j] == itemText[i]) j++
            spans.add(itemText[i])
            i = j
        }
        assertEquals("path must be a single link, not split before the file name", listOf(p), spans)
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

    // ---- Regression: a repo-relative path whose leading segment is itself a content root/module must
    // still resolve. The agent prints `foo-migrate/src/.../Foo.java`; when `foo-migrate` is opened
    // as its own module/content root, the naive `contentRoot/foo-migrate/...` does not exist, so the
    // link appeared but navigated nowhere. `anchorCandidates` now drops the redundant leading segment. ----

    @Test
    fun testAnchorCandidatesDropsRedundantLeadingModuleSegment() {
        val root = Files.createTempDirectory("yolo-link-test").toFile()
        try {
            // Simulate a module/content root named exactly like the path's leading segment.
            val moduleDir = File(root, "foo-migrate")
            val realFile = File(moduleDir, "src/main/java/com/cnsharp/foo/migrate/fbservice/accrued/BarService.java")
            realFile.parentFile.mkdirs()
            realFile.writeText("// marker")
            val anchors = listOf(moduleDir) // the only content root
            val raw = "foo-migrate/src/main/java/com/cnsharp/foo/migrate/fbservice/accrued/BarService.java"

            // Without the drop, only `moduleDir/foo-migrate/...` is tried — it does not exist.
            val naive = listOf(File(moduleDir, raw))
            assertTrue("sanity: naive resolution must miss", naive.none { it.isFile })

            // With the drop, `moduleDir/...` (segment stripped) is found.
            val candidates = anchorCandidates(raw, anchors)
            val hit = candidates.firstOrNull { it.isFile }
            assertEquals("the redundant leading segment must be stripped so the file resolves", realFile, hit)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun testAnchorCandidatesKeepsNormalRelativePath() {
        // A path whose leading segment is NOT a content-root name still resolves via `anchor/raw`.
        val root = Files.createTempDirectory("yolo-link-test2").toFile()
        try {
            val anchor = File(root, "repo")
            val realFile = File(anchor, "src/main/java/com/x/Foo.kt")
            realFile.parentFile.mkdirs()
            realFile.writeText("// marker")
            val raw = "src/main/java/com/x/Foo.kt"
            val hit = anchorCandidates(raw, listOf(anchor)).firstOrNull { it.isFile }
            assertEquals(realFile, hit)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun testUserWrappedModuleRelativePathReconstructsAndLinksTail() {
        // Exact reported case: a repo-relative module path hard-wrapped mid-name with indentation.
        //   foo-migrate/src/main/java/com/cnsharp/foo/migrate/fbservice/accrued/Acc
        //       ruedFeeDataMigrationService.java
        // The wrap reconstruction must keep the full path and link the continuation's tail.
        val state = WrapState()
        val filter = FileLinkFilter(null, "/tmp/agent-working-dir", state)
        val head = "foo-migrate/src/main/java/com/cnsharp/foo/migrate/fbservice/accrued/Acc"
        filter.apply(head)
        assertEquals("head fragment must be stored for continuation", head, state.pendingPath)
        val tail = "    ruedFeeDataMigrationService.java"
        val tailLinked = filter.apply(tail)
            ?.items
            ?.map { tail.substring(it.startOffset, it.endOffset) }
            .orEmpty()
        assertEquals(listOf("ruedFeeDataMigrationService.java"), tailLinked)
        assertEquals(
            "reconstructed path must keep the full repo-relative path",
            "foo-migrate/src/main/java/com/cnsharp/foo/migrate/fbservice/accrued/AccruedFeeDataMigrationService.java",
            state.lastCompletedPath?.path
        )
    }

    @Test
    fun testAnchorCandidatesStripsMultipleLeadingSegments() {
        // Generic, segment-by-segment strip (not keyed to the anchor name): the anchor is a *nested* module
        // directory and the raw path repeats both leading segments. Dropping just the anchor name would miss;
        // progressive stripping must drop two segments to land on the real file.
        val root = Files.createTempDirectory("yolo-link-test3").toFile()
        try {
            val anchor = File(root, "module/sub")
            val realFile = File(anchor, "src/main/java/com/x/Foo.kt")
            realFile.parentFile.mkdirs()
            realFile.writeText("// marker")
            val raw = "module/sub/src/main/java/com/x/Foo.kt"

            // Naive `anchor/raw` doubles the whole prefix and misses.
            assertTrue("sanity: naive resolution must miss", listOf(File(anchor, raw)).none { it.isFile })

            // Progressive stripping drops `module/`, then `sub/`, resolving at `anchor/src/...`.
            val hit = anchorCandidates(raw, listOf(anchor)).firstOrNull { it.isFile }
            assertEquals(realFile, hit)
        } finally {
            root.deleteRecursively()
        }
    }
}
