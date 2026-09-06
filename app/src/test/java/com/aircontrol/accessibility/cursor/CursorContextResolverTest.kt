package com.aircontrol.accessibility.cursor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cursor context classification (spec §27): clickable/editable detection,
 * ambiguous fallback, disabled handling, never-guess resize.
 */
class CursorContextResolverTest {

    @Test
    fun `editable flag resolves to ibeam`() {
        val icon = CursorContextResolver.resolve(
            CursorNodeSnapshot(className = "android.view.View", isEditable = true),
        )
        assertEquals(CursorIcon.IBEAM, icon)
    }

    @Test
    fun `edittext class resolves to ibeam even without flags`() {
        assertEquals(
            CursorIcon.IBEAM,
            CursorContextResolver.resolve(CursorNodeSnapshot(className = "android.widget.EditText")),
        )
        assertEquals(
            CursorIcon.IBEAM,
            CursorContextResolver.resolve(CursorNodeSnapshot(className = "androidx.appcompat.widget.AppCompatEditText")),
        )
    }

    @Test
    fun `static text stays arrow`() {
        // Non-editable TextView with text must NOT become an I-beam (spec §9).
        assertEquals(
            CursorIcon.ARROW,
            CursorContextResolver.resolve(CursorNodeSnapshot(className = "android.widget.TextView")),
        )
    }

    @Test
    fun `enabled clickable resolves to hand`() {
        assertEquals(
            CursorIcon.HAND,
            CursorContextResolver.resolve(
                CursorNodeSnapshot(className = "android.widget.Button", isClickable = true, isEnabled = true),
            ),
        )
    }

    @Test
    fun `click action alone resolves to hand`() {
        // Some views are not marked clickable but expose ACTION_CLICK.
        assertEquals(
            CursorIcon.HAND,
            CursorContextResolver.resolve(
                CursorNodeSnapshot(className = "android.view.View", hasClickAction = true),
            ),
        )
    }

    @Test
    fun `disabled clickable stays arrow`() {
        assertEquals(
            CursorIcon.ARROW,
            CursorContextResolver.resolve(
                CursorNodeSnapshot(className = "android.widget.Button", isClickable = true, isEnabled = false),
            ),
        )
    }

    @Test
    fun `plain text node is not a hand`() {
        // Text alone must never trigger HAND (spec §10).
        assertEquals(
            CursorIcon.ARROW,
            CursorContextResolver.resolve(
                CursorNodeSnapshot(className = "android.widget.TextView", hasClickAction = false),
            ),
        )
    }

    @Test
    fun `ambiguous container falls back to arrow`() {
        assertEquals(
            CursorIcon.ARROW,
            CursorContextResolver.resolve(
                CursorNodeSnapshot(className = "android.view.ViewGroup", isEnabled = true),
            ),
        )
    }

    @Test
    fun `null or empty class name is safe`() {
        assertEquals(CursorIcon.ARROW, CursorContextResolver.resolve(CursorNodeSnapshot()))
        assertEquals(
            CursorIcon.ARROW,
            CursorContextResolver.resolve(CursorNodeSnapshot(className = "")),
        )
    }

    @Test
    fun `generic divider classes never guess resize`() {
        // RecyclerView/LinearLayout dividers don't carry resize semantics —
        // correctness over coverage (spec §11): fall back to ARROW.
        assertEquals(
            CursorIcon.ARROW,
            CursorContextResolver.resolve(
                CursorNodeSnapshot(className = "androidx.recyclerview.widget.RecyclerView"),
            ),
        )
        assertEquals(
            CursorIcon.ARROW,
            CursorContextResolver.resolve(CursorNodeSnapshot(className = "android.widget.LinearLayout")),
        )
    }

    @Test
    fun `explicit resize handle classes resolve to resize cursors`() {
        assertEquals(
            CursorIcon.RESIZE_HORIZONTAL,
            CursorContextResolver.resolve(
                CursorNodeSnapshot(className = "com.example.ResizeHandleHorizontal"),
            ),
        )
        assertEquals(
            CursorIcon.RESIZE_VERTICAL,
            CursorContextResolver.resolve(CursorNodeSnapshot(className = "com.example.ResizeHandleVertical")),
        )
        assertEquals(
            CursorIcon.RESIZE_DIAGONAL_1,
            CursorContextResolver.resolve(CursorNodeSnapshot(className = "com.example.ResizeHandle")),
        )
    }

    @Test
    fun `editable wins over clickable`() {
        // An editable text field that is also clickable (most are) is an
        // I-beam, not a hand.
        assertEquals(
            CursorIcon.IBEAM,
            CursorContextResolver.resolve(
                CursorNodeSnapshot(
                    className = "android.widget.EditText",
                    isClickable = true,
                    isEditable = true,
                ),
            ),
        )
    }
}
