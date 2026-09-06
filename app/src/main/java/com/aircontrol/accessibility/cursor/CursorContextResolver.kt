package com.aircontrol.accessibility.cursor

/**
 * Immutable, framework-free snapshot of the accessibility node under the
 * cursor. [CursorHitTester] builds it from an AccessibilityNodeInfo and
 * RELEASES the node immediately — snapshots are the only thing retained.
 */
data class CursorNodeSnapshot(
    val className: String = "",
    val isClickable: Boolean = false,
    val isEnabled: Boolean = true,
    val isEditable: Boolean = false,
    val hasClickAction: Boolean = false,
)

/**
 * Pure classifier: accessibility node facts → most appropriate [CursorIcon].
 *
 * Rules (conservative by design — ambiguous UI must fall back to ARROW):
 *  1. Disabled nodes → ARROW (a real pointer doesn't change over disabled UI).
 *  2. Editable text (isEditable, or a known editable class) → IBEAM.
 *     Static text is NOT IBEAM — only UI that accepts input.
 *  3. Enabled + (isClickable or exposes ACTION_CLICK) → HAND.
 *     Text alone never triggers HAND.
 *  4. Everything else → ARROW. Resize cursors are never guessed: stock
 *     widgets do not expose resize semantics, so the resolver only returns
 *     them for classes that explicitly name themselves as resize handles.
 */
object CursorContextResolver {

    /** Class names (lowercase, suffix-insensitive) that mean "text input". */
    private val EDITABLE_CLASS_HINTS = listOf(
        "edittext",
        "autocompletetextview",
        "multilineedittext", // Compose
        "searchedittext", // Material
    )

    /** Class names that explicitly identify a resize handle/divider control. */
    private val RESIZE_CLASS_HINTS = listOf(
        "resizehandle",
        "resizer",
        "resize_handle",
        "sash", // Eclipse/SWT-style resize sash
    )

    fun resolve(snapshot: CursorNodeSnapshot): CursorIcon {
        if (!snapshot.isEnabled) return CursorIcon.ARROW

        val cls = snapshot.className.lowercase()
        if (snapshot.isEditable || EDITABLE_CLASS_HINTS.any { cls.contains(it) }) {
            return CursorIcon.IBEAM
        }

        // Only when the control explicitly names itself as a resize handle —
        // never inferred from generic container/list classes.
        if (RESIZE_CLASS_HINTS.any { cls.contains(it) }) {
            return when {
                cls.contains("vertical") -> CursorIcon.RESIZE_VERTICAL
                cls.contains("horizontal") -> CursorIcon.RESIZE_HORIZONTAL
                else -> CursorIcon.RESIZE_DIAGONAL_1
            }
        }

        if (snapshot.isClickable || snapshot.hasClickAction) {
            return CursorIcon.HAND
        }

        return CursorIcon.ARROW
    }
}
