package com.cnsharp.yolo.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [TypeLinkFilter.apply] — the pure link-matching / gating logic — covering project-type links
 * and the bare source-file-name fallback added for Kotlin file facades (e.g. `YoloNavigation`, a file of
 * top-level functions rather than a class).
 *
 * The click handler (resolution) is intentionally NOT invoked, so these tests never touch the filesystem
 * or PSI indices — they only assert *what* got linked. A fixed [YoloProjectTypes.Snapshot] is injected via
 * [TypeLinkFilter.typesProvider] so the gating decision is deterministic and the test needs no live
 * project (keeping `:test` offline, like [FileLinkFilterTest] / [StackTraceLinkFilterTest]).
 */
class TypeLinkFilterTest {

    private val types = YoloProjectTypes.Snapshot(
        simple = setOf("YoloToolWindowFactory", "AgentExtenderSettings", "TypeLinkFilter", "DeductPaymentServiceImpl"),
        files = setOf("YoloNavigation", "Main"),
    )

    private fun linked(text: String): List<String> {
        val filter = TypeLinkFilter(null, { _ -> types })
        return filter.apply(text)
            ?.items
            ?.map { text.substring(it.startOffset, it.endOffset) }
            .orEmpty()
    }

    @Test
    fun testFileFacadeIsLinked() {
        // `YoloNavigation` is a Kotlin file of top-level functions (no class): the file-name fallback must
        // link it even though it is absent from the class sets.
        assertEquals(listOf("YoloNavigation"), linked("open YoloNavigation now"))
    }

    @Test
    fun testProjectClassStillLinked() {
        // A project class present in the simple-name set must still be linked (regression guard for the
        // primary type-link path).
        assertEquals(listOf("YoloToolWindowFactory"), linked("see YoloToolWindowFactory here"))
    }

    @Test
    fun testQualifiedProjectTypeLinked() {
        assertEquals(
            listOf("com.cnsharp.yolo.panel.TypeLinkFilter"),
            linked("defined in com.cnsharp.yolo.panel.TypeLinkFilter"),
        )
    }

    @Test
    fun testUnknownCapitalizedWordNotLinked() {
        // Ordinary capitalized words that are not project types must stay plain text (noise gating).
        assertTrue(linked("class FooBar created").isEmpty())
    }

    @Test
    fun testJdkTypeNotLinked() {
        // Ubiquitous JDK types are excluded so they are not painted blue everywhere.
        assertTrue(linked("use String and List here").isEmpty())
    }

    @Test
    fun testExternalQualifiedTypeNotLinked() {
        // Library types (e.g. JediTerm's `HyperlinkFilter`) are excluded by design, even when fully qualified.
        assertTrue(linked("implements com.jediterm.terminal.model.hyperlinks.HyperlinkFilter").isEmpty())
    }

    @Test
    fun testTrailingExtensionNotLinkedByTypeFilter() {
        // `YoloNavigation.kt` carries a recognized extension, so it is a *file* link (FileLinkFilter's job),
        // not a type link — TypeLinkFilter must not also link the bare `YoloNavigation` portion, which would
        // collide with the file link.
        assertTrue(linked("edit YoloNavigation.kt please").isEmpty())
    }

    @Test
    fun testSimpleTypeWithLineLinked() {
        // An agent "caller" citation like `调用方: DeductPaymentServiceImpl:182` must link the *whole*
        // `DeductPaymentServiceImpl:182` span (so clicking lands on line 182, not just the class declaration).
        assertEquals(listOf("DeductPaymentServiceImpl:182"), linked("调用方: DeductPaymentServiceImpl:182"))
    }

    @Test
    fun testQualifiedTypeWithLineLinked() {
        // A qualified name with a trailing `:line` must also link the full span.
        assertEquals(
            listOf("com.cnsharp.yolo.panel.TypeLinkFilter:182"),
            linked("see com.cnsharp.yolo.panel.TypeLinkFilter:182 here"),
        )
    }

    @Test
    fun testTypeWithLineAndColumnLinked() {
        // Optional `:col` after `:line` is included in the link span.
        assertEquals(listOf("DeductPaymentServiceImpl:182:13"), linked("at DeductPaymentServiceImpl:182:13"))
    }

    @Test
    fun testBareTypeStillLinkedDespiteLineLookahead() {
        // Regression guard for the `(?!:\d)` lookahead added to TYPE_NAME_PATTERN: a `TypeName` *not*
        // followed by `:digits` must still link to the class declaration as before.
        assertEquals(listOf("DeductPaymentServiceImpl"), linked("see DeductPaymentServiceImpl here"))
    }

    @Test
    fun testUnknownTypeWithLineNotLinked() {
        // A `TypeName:line` whose type is not a known project type must stay plain text (noise gating),
        // exactly like the bare-type path.
        assertTrue(linked("caller FooBar:182").isEmpty())
    }

    @Test
    fun testFileFacadeWithLineLinked() {
        // `Main` is only a source-file facade (not a class), so the type gate misses it — but the file-name
        // fallback must still link `Main:182` and open the file at that line. Regression guard: a bare `Main`
        // was already linked before the `TypeName:line` feature; it must not disappear behind a `:line`.
        assertEquals(listOf("Main:182"), linked("open Main:182 now"))
    }
}
