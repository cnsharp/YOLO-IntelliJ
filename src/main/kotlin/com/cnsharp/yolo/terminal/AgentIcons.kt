package com.cnsharp.yolo.terminal

import com.cnsharp.yolo.Yolo
import com.cnsharp.yolo.settings.AgentRegistry
import com.intellij.ide.ui.LafManagerListener
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import com.intellij.util.IconUtil
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.image.BufferedImage
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
     * Whether the active look-and-feel is dark. Used to lighten monochrome/dark brand glyphs (e.g. cline)
     * that ship no `_dark` variant and would otherwise vanish on a dark background.
     */
    fun isDarkTheme(): Boolean = ColorUtil.isDark(UIUtil.getPanelBackground())

    /** Light-gray used to recolor dark monochrome marks on dark themes (matches the bundled `_dark` convention). */
    private val DARK_TINT = JBColor(0xBDBDBD, 0xBDBDBD)

    init {
        // Icons are cached per theme; drop the cache when the L&F changes so the tint is re-evaluated.
        ApplicationManager.getApplication().messageBus.connect().subscribe(LafManagerListener.TOPIC, object : LafManagerListener {
            override fun lookAndFeelChanged(source: com.intellij.ide.ui.LafManager) = clearCache()
        })
    }

    /**
     * Get the icon for a tool.
     *
     * @param agentId  tool id, used to match the bundled official icon
     * @param iconPath absolute path of the icon file specified by the user; empty means unspecified
     */
    @Synchronized
    fun forAgent(agentId: String, iconPath: String = ""): Icon {
        val dark = isDarkTheme()
        val key = "${agentId.lowercase()}|$iconPath|${if (dark) "d" else "l"}"
        cache[key]?.let { return it }

        val icon = loadUserIcon(iconPath)
            ?: AgentRegistry.iconFor(agentId)?.let { loadBundled(it) }
            ?: DEFAULT

        // On a dark theme, monochrome/dark glyphs (black wordmarks like cline) are invisible; lighten them.
        // Colored brand marks (copilot, grok) read fine and keep their brand colour, so they are skipped.
        val resolved = if (dark && isDarkMonochrome(icon)) IconUtil.colorize(icon, DARK_TINT) else icon

        cache[key] = resolved
        return resolved
    }

    /** Called after the user changes config, so the next icon fetch re-reads from disk. */
    @Synchronized
    fun clearCache() {
        cache.clear()
    }

    /**
     * True when the icon is a dark/near-black mark on a transparent background — i.e. it would disappear on
     * a dark theme. Decided by rendering the glyph and averaging the luminance of its *opaque* pixels, so
     * colored logos (whose opaque pixels are bright) are never mistaken for monochrome dark marks.
     */
    private fun isDarkMonochrome(icon: Icon): Boolean {
        val w = icon.iconWidth
        val h = icon.iconHeight
        if (w <= 0 || h <= 0) return false
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            icon.paintIcon(null, g, 0, 0)
        } finally {
            g.dispose()
        }
        var opaque = 0
        var lumSum = 0.0
        // Average saturation of the opaque pixels. A genuinely monochrome dark mark (black / gray /
        // dark-navy) has ~0 saturation; a brand-coloured mark (e.g. poolside's blue) is saturated and
        // must keep its colour on a dark theme rather than being flattened to gray.
        var satSum = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val rgb = img.getRGB(x, y)
            if ((rgb ushr 24 and 0xFF) < 128) continue
            opaque++
            val r = rgb ushr 16 and 0xFF
            val gc = rgb ushr 8 and 0xFF
            val b = rgb and 0xFF
            lumSum += (0.2126 * r + 0.7152 * gc + 0.0722 * b) / 255.0
            val max = Math.max(r, Math.max(gc, b))
            satSum += if (max == 0) 0.0 else (max - Math.min(r, Math.min(gc, b))) / max.toDouble()
        }
        if (opaque < w * h * 0.02) return false
        return lumSum / opaque < 0.55 && satSum / opaque < 0.25
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
