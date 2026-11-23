package com.aircontrol.accessibility

import org.junit.Assert.assertTrue
import org.junit.Test

class SystemGestureSafetyTest {
    private val w = 1080
    private val h = 2400

    @Test
    fun `horizontal scroll near side edge never starts in the back gesture zone`() {
        val left = ActionDispatcher.safeScrollPath(5f, 1200f, 1f, 0f, w, h)
        val right = ActionDispatcher.safeScrollPath(1075f, 1200f, -1f, 0f, w, h)
        assertTrue(left[0] >= w * 0.25f)
        assertTrue(right[0] <= w * 0.75f)
        assertTrue(left[2] in 0f..w.toFloat() && right[2] in 0f..w.toFloat())
    }

    @Test
    fun `scroll up near bottom edge never starts in the home gesture zone`() {
        val p = ActionDispatcher.safeScrollPath(540f, 2395f, 0f, -1f, w, h)
        assertTrue(p[1] <= h * 0.70f)
        assertTrue(p[3] < p[1])
    }

    @Test
    fun `scroll down near top edge never starts in the notification zone`() {
        val p = ActionDispatcher.safeScrollPath(540f, 2f, 0f, 1f, w, h)
        assertTrue(p[1] >= h * 0.25f)
        assertTrue(p[3] > p[1])
    }

    @Test
    fun `drag start is moved out of edge gesture zones`() {
        val density = 2.75f
        val s = ActionDispatcher.safeGestureStart(0f, 2400f, w, h, density)
        assertTrue(s[0] >= 48f * density - 0.01f)
        assertTrue(s[1] <= h - 64f * density + 0.01f)
        val mid = ActionDispatcher.safeGestureStart(540f, 1200f, w, h, density)
        assertTrue(mid[0] == 540f && mid[1] == 1200f)
    }
}
