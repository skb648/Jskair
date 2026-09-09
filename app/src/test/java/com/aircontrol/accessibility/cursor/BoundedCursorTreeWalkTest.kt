package com.aircontrol.accessibility.cursor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 9 acceptance: bounded traversal with overlay exclusion, deepest-last-child
 * preference and snapshot-only returns — exercised on synthetic pathological trees.
 */
class BoundedCursorTreeWalkTest {

    private class FakeNode(
        private val label: String,
        val boundsRect: CursorRect?,
        private val children: List<FakeNode> = emptyList(),
    ) : CursorHitNodeSource {
        override val bounds: CursorRect? get() = boundsRect
        override val childCount: Int get() = children.size
        override fun childAt(index: Int): CursorHitNodeSource? = children[index]
        override fun snapshot(): CursorNodeSnapshot = CursorNodeSnapshot(className = label)
    }

    private class FakeWindow(
        override val isOverlayLike: Boolean,
        override val root: CursorHitNodeSource?,
    ) : CursorHitWindowSource

    private fun rect(l: Int, t: Int, r: Int, b: Int) = CursorRect(l, t, r, b)

    @Test
    fun `returns deepest node containing the point`() {
        val child2 = FakeNode("deep", rect(20, 20, 30, 30))
        val child1 = FakeNode("mid", rect(10, 10, 90, 90), listOf(child2))
        val root = FakeNode("root", rect(0, 0, 100, 100), listOf(child1))
        val hit = BoundedCursorTreeWalk.hitTest(listOf(FakeWindow(false, root)), 25f, 25f)
        assertNotNull(hit)
        assertEquals("deep", hit!!.className)
    }

    @Test
    fun `prefers the last overlapping child (topmost drawing order)`() {
        val a = FakeNode("a", rect(10, 10, 60, 60))
        val b = FakeNode("b", rect(20, 20, 70, 70))
        val root = FakeNode("root", rect(0, 0, 100, 100), listOf(a, b))
        val hit = BoundedCursorTreeWalk.hitTest(listOf(FakeWindow(false, root)), 40f, 40f)
        assertEquals("b", hit!!.className)
    }

    @Test
    fun `skips overlay-like windows entirely`() {
        val appRoot = FakeNode("app", rect(0, 0, 100, 100))
        val overlayRoot = FakeNode("overlay", rect(0, 0, 100, 100))
        val windows = listOf(FakeWindow(true, overlayRoot), FakeWindow(false, appRoot))
        val hit = BoundedCursorTreeWalk.hitTest(windows, 50f, 50f)
        assertEquals("app", hit!!.className)
    }

    @Test
    fun `nothing hit outside bounds returns null`() {
        val root = FakeNode("root", rect(0, 0, 100, 100))
        assertNull(BoundedCursorTreeWalk.hitTest(listOf(FakeWindow(false, root)), 500f, 500f))
        // Invalid coordinates never even start the walk.
        assertNull(BoundedCursorTreeWalk.hitTest(listOf(FakeWindow(false, root)), Float.NaN, 50f))
    }

    @Test
    fun `broken root and broken bounds are pruned not thrown`() {
        val broken = FakeNode("broken", null, listOf(FakeNode("child", rect(0, 0, 10, 10))))
        assertNull(BoundedCursorTreeWalk.hitTest(listOf(FakeWindow(false, broken)), 5f, 5f))
        assertNull(BoundedCursorTreeWalk.hitTest(emptyList(), 5f, 5f))
    }

    @Test
    fun `pathologically deep tree terminates within the depth budget`() {
        var node = FakeNode("leaf", rect(10, 10, 20, 20))
        repeat(200) { i ->
            node = FakeNode("n$i", rect(0, 0, 100, 100), listOf(node))
        }
        // Must terminate; deepest-eligible node (within budget) is returned.
        val hit = BoundedCursorTreeWalk.hitTest(listOf(FakeWindow(false, node)), 15f, 15f)
        assertNotNull(hit)
        assertTrue(hit!!.className.length > 0)
    }

    @Test
    fun `pathologically wide tree terminates within the visit budget`() {
        var childVisits = 0
        val wideRoot = object : CursorHitNodeSource {
            override val bounds: CursorRect? = rect(0, 0, 100, 100)
            override val childCount: Int = 10_000
            override fun childAt(index: Int): CursorHitNodeSource? {
                childVisits++
                return FakeNode("child$index", rect(10, 10, 20, 20))
            }
            override fun snapshot(): CursorNodeSnapshot = CursorNodeSnapshot(className = "root")
        }
        val hit = BoundedCursorTreeWalk.hitTest(listOf(FakeWindow(false, wideRoot)), 15f, 15f)
        // Terminated, and it never walked anywhere near the full 10,000.
        assertNotNull(hit)
        assertTrue(childVisits <= BoundedCursorTreeWalk.MAX_VISITS + 1)
    }
}
