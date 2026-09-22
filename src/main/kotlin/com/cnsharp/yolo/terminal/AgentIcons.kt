package com.cnsharp.yolo.terminal

import com.cnsharp.yolo.Yolo
import com.cnsharp.yolo.settings.AgentRegistry
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.IconLoader
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.io.File
import javax.swing.Icon
import kotlin.math.max

/**
 * Icons displayed for custom tools in the dropdown list.
 *
 * Three-level fallback; failure at any level will not make the dropdown entry disappear:
 *   1. Icon file specified by the user in Settings (iconPath, local absolute path)
 *   2. Official icon bundled with the plugin (looked up from AgentRegistry by agent id)
 *   3. Generic icon DEFAULT
 *
 * Bundled agent logos are genuine brand assets (symbol-only; never hand-traced). They are loaded through
 * [IconLoader.getIcon], which automatically selects a `_dark` variant on dark themes. Monochrome/black
 * brand symbols that are invisible on a dark background ship a `_dark` variant recolored to light gray
 * (`#BDBDBD`). Currently only `command-code` (plus the pre-existing `codex`) ships one; other near-black
 * marks were reviewed and kept without a `_dark` because they read fine on dark. Colored symbols (copilot,
 * grok) keep their brand colors — they stay visible on dark themes, so no recolor is applied. `cursor`
 * keeps its base (the white arrow glyph is visible on dark). `kimi` and `opencode` ship the real favicon
 * as a `.png` (no genuine SVG symbol exists upstream). The Skip-permissions "y" mark and the Resume replay
 * mark also keep `_dark` variants.
 * Uniform size 16x16.
 */
object AgentIcons {

    private val LOG = Logger.getInstance(AgentIcons::class.java)

    /** Standard edge length for dropdown icons, aligned with built-in claude-code.svg / codex.svg. */
    private const val SIZE = 16


    /** y icon for the Skip permissions checkbox (off/on). */
    val SKIP_Y_OFF: Icon by lazy { loadBundled("/icons/skipY.svg") }
    val SKIP_Y_ON: Icon by lazy { loadBundled("/icons/skipYOn.svg") }

    /** Replay icon for the Resume-session checkbox (off/on). */
    val RESUME_OFF: Icon by lazy { loadBundled("/icons/resume.svg") }
    val RESUME_ON: Icon by lazy { loadBundled("/icons/resumeOn.svg") }

    /** Used by custom tools that have no dedicated icon. */
    val DEFAULT: Icon = AllIcons.Actions.Lightning

    /** Cache key includes iconPath, so changing the path naturally triggers a reload. */
    private val cache = HashMap<String, Icon>()

    /**
     * Get the icon for a tool.
     *
     * @param agentId  tool id, used to match the bundled official icon
     * @param iconPath absolute path of the icon file specified by the user; empty means unspecified
     */
    @Synchronized
    fun forAgent(agentId: String, iconPath: String = ""): Icon {
        val key = "${agentId.lowercase()}|$iconPath"
        cache[key]?.let { return it }

        val icon = loadUserIcon(iconPath)
            ?: AgentRegistry.iconFor(agentId)?.let { loadBundled(it) }
            ?: DEFAULT

        cache[key] = icon
        return icon
    }

    /** Called after the user changes config, so the next icon fetch re-reads from disk. */
    @Synchronized
    fun clearCache() {
        cache.clear()
    }

    /** Load from local file; on failure return null to let the caller fall back, no exception thrown. */
    private fun loadUserIcon(iconPath: String): Icon? {
        val path = iconPath.trim()
        if (path.isEmpty()) return null

        val file = File(path)
        if (!file.isFile) {
            LOG.warn("${Yolo.NAME}: icon file does not exist, ignored: $path")
            return null
        }
        return try {
            val raw = IconLoader.findIcon(file.toURI().toURL())
            if (raw == null) {
                LOG.warn("${Yolo.NAME}: unrecognized icon format, ignored: $path")
                return null
            }
            // User icons may not be 16x16; scale to standard size to avoid breaking the dropdown row height
            fitToSize(raw)
        } catch (e: Exception) {
            LOG.warn("${Yolo.NAME}: icon load failed, ignored: $path", e)
            null
        }
    }

    private fun loadBundled(path: String): Icon =
        try {
            val raw = IconLoader.getIcon(path, AgentIcons::class.java.classLoader)
            fitToSize(raw)
        } catch (e: Exception) {
            LOG.warn("${Yolo.NAME}: bundled icon load failed: $path", e)
            DEFAULT
        }

    /**
     * Force any icon (SVG or bitmap, any source size) to render at exactly [SIZE]x[SIZE], scaled to fit.
     *
     * [com.intellij.util.IconUtil.resizeSquared] leaves large-source SVGs/PNGs at their native size in some
     * IntelliJ versions (e.g. the 248x248 `claude.svg` / large `hermes.png` showed up oversized in the
     * dropdown), so we wrap the source and do the scaling ourselves in [paintIcon].
     */
    private fun fitToSize(icon: Icon): Icon {
        val w = icon.iconWidth
        val h = icon.iconHeight
        if (w <= 0 || h <= 0) return icon
        if (w == SIZE && h == SIZE) return icon
        // Scale to fit inside the SIZE x SIZE box, keeping the aspect ratio, and centre the result so
        // wide/tall logos (e.g. aider.svg 200x60) don't hug the top-left corner.
        val scale = SIZE.toDouble() / max(w, h)
        val dx = (SIZE - w * scale) / 2.0
        val dy = (SIZE - h * scale) / 2.0
        return object : Icon {
            override fun getIconWidth(): Int = SIZE
            override fun getIconHeight(): Int = SIZE
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.translate(x, y)
                    // Hard clip to the 16x16 box: a source icon that ignores the scale transform below is
                    // then truncated instead of spilling out and stretching the dropdown row / tab.
                    g2.clipRect(0, 0, SIZE, SIZE)
                    g2.translate(dx, dy)
                    g2.scale(scale, scale)
                    icon.paintIcon(c, g2, 0, 0)
                } finally {
                    g2.dispose()
                }
            }
        }
    }

}
