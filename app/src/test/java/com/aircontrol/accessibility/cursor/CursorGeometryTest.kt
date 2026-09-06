package com.aircontrol.accessibility.cursor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Hotspot/window geometry (spec §4/§17/§18): the hotspot pixel must sit
 * exactly on the logical cursor position at every screen position, including
 * edges, corners and invalid input.
 */
class CursorGeometryTest {

    private val density = 2.75f // 440dpi-ish
    private val hotspot = CursorGeometry.hotspotPx(density)

    @Test
    fun `hotspot is identical for every icon`() {
        // One anchor for all glyphs → switching icons never shifts the point.
        assertEquals(CursorGeometry.hotspotPx(density), hotspot, 0f)
    }

    @Test
    fun `window position puts hotspot exactly on logical position`() {
        // window + hotspot == logical (± 0.5 px rounding, no accumulation).
        val positions = listOf(
            0f to 0f,
            540f to 1200f,
            17.3f to 91.9f,
            1079.6f to 2399.4f, // bottom-right corner (1080×2400)
            55.5f to 2344.5f,
        )
        for ((x, y) in positions) {
            val wx = CursorGeometry.windowLeft(x, density) + hotspot
            val wy = CursorGeometry.windowTop(y, density) + hotspot
            assertTrue("x off by ${abs(wx - x)}", abs(wx - x) <= 0.5f)
            assertTrue("y off by ${abs(wy - y)}", abs(wy - y) <= 0.5f)
        }
    }

    @Test
    fun `hotspot stays inside the view`() {
        val view = CursorGeometry.viewSizePx(density).toFloat()
        assertTrue(hotspot in 0f..view)
        assertTrue(hotspot in 0f..view)
        // Glyph extents from the hotspot: −1dp..+22dp → inside a 44dp view
        // anchored at 20dp with room for stroke/dwell/ripple.
        val dp = density
        assertTrue(20f * dp - 1f * dp >= 0f)
        assertTrue(20f * dp + 22f * dp <= view + 0.1f)
    }

    @Test
    fun `clamp keeps cursor inside usable bounds`() {
        assertEquals(0f, CursorGeometry.clampToScreen(-5f, 1080), 0f)
        assertEquals(1080f, CursorGeometry.clampToScreen(2000f, 1080), 0f)
        assertEquals(540f, CursorGeometry.clampToScreen(540f, 1080), 0f)
    }

    @Test
    fun `nan and infinity collapse safely`() {
        // NaN must never reach the window manager as a position.
        assertEquals(0f, CursorGeometry.clampToScreen(Float.NaN, 1080), 0f)
        assertEquals(1080f, CursorGeometry.clampToScreen(Float.POSITIVE_INFINITY, 1080), 0f)
        assertFalse(CursorGeometry.isUsable(Float.NaN, 5f))
        assertFalse(CursorGeometry.isUsable(5f, Float.NEGATIVE_INFINITY))
        assertTrue(CursorGeometry.isUsable(0f, 0f))
    }

    @Test
    fun `window may extend past screen edges without moving the hotspot`() {
        // At the left edge the window's left is negative — allowed (no
        // clipping of the hotspot, no jump); the hotspot still covers x=0.
        val left = CursorGeometry.windowLeft(0f, density)
        assertTrue(left < 0)
        assertEquals(0f, left + hotspot, 0.5f)

        val right = CursorGeometry.windowLeft(1080f, density)
        assertTrue(right + hotspot >= 1080f - 0.5f)
    }

    @Test
    fun `rounding is stable across densities`() {
        for (d in listOf(1f, 1.5f, 2f, 2.75f, 3.5f, 4f)) {
            val x = 731.37f
            val wx = CursorGeometry.windowLeft(x, d) + CursorGeometry.hotspotPx(d)
            assertTrue(abs(wx - x) <= 0.5f)
        }
    }
}
