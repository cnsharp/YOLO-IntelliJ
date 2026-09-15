package com.cnsharp.yolo.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [StackTraceLinkFilter.apply] — the pure link-matching logic — against the reported
 * false-link regression: a path the agent abbreviated with `…` in the *middle* (e.g.
 * `Read(src/main/kotlin/com/cnshar…entExtenderConfigurable.kt)`) must NOT have its tail fragment
 * (`entExtenderConfigurable.kt`) linked as a bare file. The click handler (resolution) is intentionally
 * NOT invoked, so these tests never touch the filesystem or PSI indices — they only assert *what* got
 * linked.
 *
 * Runs without the IntelliJ `BasePlatformTestCase` fixture: [StackTraceLinkFilter] only captures
 * `project` for the deferred click handler, so `apply()` is fully testable with a `null` project (the
 * link's `Runnable` is constructed but never executed here). This keeps `:test` offline — it reuses the
 * local IDEA platform classpath used for compilation instead of the plugin's `testFramework(...)` helper.
 */
class StackTraceLinkFilterTest {

    private fun linked(text: String): List<String> {
        val filter = StackTraceLinkFilter(null, "/tmp/agent-working-dir")
        return filter.apply(text)
            ?.items
            ?.map { text.substring(it.startOffset, it.endOffset) }
            .orEmpty()
    }

    @Test
    fun testTruncatedMiddleEllipsisTailIsNotLinked() {
        // The agent abbreviated the path with `…` in the middle; the tail is not a real file.
        assertTrue(linked("Read(src/main/kotlin/com/cnshar…entExtenderConfigurable.kt)").isEmpty())
    }

    @Test
    fun testTruncatedMiddleAsciiDotTailIsNotLinked() {
        // Same, with a three-dot ASCII run instead of `…`.
        assertTrue(linked("Read(src/main/kotlin/com/cnshar...entExtenderConfigurable.kt)").isEmpty())
    }

    @Test
    fun testTruncatedMiddleTailWithLineIsNotLinked() {
        // Even when the truncated tail carries a `:line`, it must not link — it is still a fragment.
        assertTrue(linked("Read(src/main/kotlin/com/cnshar…entExtenderConfigurable.kt:42)").isEmpty())
    }

    @Test
    fun testRealBareFileNameStillLinks() {
        // A genuine bare file name (no preceding `…`) must still be linked.
        assertEquals(listOf("plugin.xml"), linked("updated plugin.xml successfully"))
    }

    @Test
    fun testPossessiveTrailingSNotLinkedAsBareExtension() {
        // `s` is a recognized (assembly) extension, but the trailing `s` of a possessive/contraction has
        // no dot, so it must not link (regression: `web's` linked the lone `s`).
        assertTrue(linked("the web's page").isEmpty())
        assertTrue(linked("it's fine").isEmpty())
    }

    @Test
    fun testBareWordThatIsAnExtensionNotLinkedWithoutDot() {
        // `go` is a Go source extension, but a standalone English word has no dot and must not link.
        assertTrue(linked("let's go").isEmpty())
    }

    @Test
    fun testDotfileAndDottedNameStillLink() {
        // A required base name must not break true dotfiles or ordinary dotted names.
        assertEquals(listOf(".gitignore"), linked("see .gitignore here"))
        assertEquals(listOf("index.js"), linked("see index.js here"))
        assertEquals(listOf("prod.env"), linked("see prod.env here"))
    }

    @Test
    fun testBareExtensionWithoutBaseNameNotLinked() {
        // A lone `.ext` (no file name) is not a file the agent printed; only a name-carrying `foo.ext` links.
        assertTrue(linked("load .env now").isEmpty())
        assertTrue(linked("read .json here").isEmpty())
    }

    @Test
    fun testStackTraceFrameStillLinks() {
        // A real stack-trace frame is not a truncation tail and must link.
        assertEquals(listOf("Bar.java:123"), linked("at com.foo.Bar.method(Bar.java:123)"))
    }

    @Test
    fun testBareNamePrecededByNonTruncationCharStillLinks() {
        // Only `…`/`...` suppresses a bare name; an ordinary (non-separator) preceding char must not.
        assertEquals(listOf("Bar.java"), linked("shown in (Bar.java) frame"))
    }

    @Test
    fun testHyphenatedPathTailIsNotBareFrame() {
        // `service.xml:387` is the *tail* of the hyphenated file `dubbo-service.xml`, not a standalone
        // stack-trace frame. The `-` before `service` is part of the file name, so it must not be linked as
        // a bare frame — otherwise it overlaps the full-path link from FileLinkFilter and the reference
        // renders split at the 2nd hyphen (the reported `app/biz/service-impl/.../dubbo-service.xml:387` bug).
        assertTrue(linked("app/biz/service-impl/src/main/resources/dubbo-service.xml:387").isEmpty())
    }

    @Test
    fun testHyphenatedBareFileNameStillLinks() {
        // A genuine bare frame whose file name itself contains a hyphen is still a real frame and must link.
        // Here the char before the name is `(` / a space (not `-`), so the lookbehind passes.
        assertEquals(listOf("dubbo-service.xml:387"), linked("at com.foo.Bar.run(dubbo-service.xml:387)"))
        assertEquals(listOf("dubbo-service.xml"), linked("see dubbo-service.xml now"))
    }

    @Test
    fun testHyphenatedPathTailIsNotBareNameFile() {
        // `batch-timing_uat_...tsv` is the *tail* of the hyphenated file
        // `venus_unused_app_order-batch-timing_uat_...tsv`, not a standalone bare file name. The `-` before
        // `batch` is part of the file name, so the bare-name pattern must not link it — otherwise it overlaps
        // FileLinkFilter's full-path link and the reference splits at the 1st `-` (the reported
        // `.venus/venus_unused_app_order-batch-timing_uat_...tsv` case, which has no `:line`).
        assertTrue(linked(".venus/venus_unused_app_order-batch-timing_uat_20260820_144426.tsv").isEmpty())
    }

    @Test
    fun testHyphenatedBareFileNameWithHyphensStillLinks() {
        // A genuine bare file name that itself contains hyphens must still link as a whole.
        assertEquals(
            listOf("venus_unused_app_order-batch-timing_uat_20260820_144426.tsv"),
            linked("see venus_unused_app_order-batch-timing_uat_20260820_144426.tsv now")
        )
    }

    @Test
    fun testWrapTailSuppressedWhenContinuationSpanSet() {
        // When FileLinkFilter has already linked a continuation tail, StackTraceLinkFilter must not
        // create a second (incorrect) bare-name link for the same span.
        val state = WrapState()
        state.continuationSpan = 8 until 30   // simulates FileLinkFilter linking "rTransitionContext.java"
        val filter = StackTraceLinkFilter(null, "/tmp", state)
        assertTrue(filter.apply("        rTransitionContext.java").let {
            it == null || it.items.isEmpty()
        })
    }

    @Test
    fun testWrapTailLinkedNormallyWithoutState() {
        // Without shared state (no wrapState), a fragment that happens to look like a bare file
        // is still linked — we only suppress when FileLinkFilter explicitly set continuationSpan.
        assertEquals(listOf("rTransitionContext.java"), linked("see rTransitionContext.java now"))
    }
}
