package com.aircontrol.accessibility.cursor

/**
 * Visual states for the Native-like Cursor (same-device accessibility overlay).
 *
 * This is NOT the OS-native pointer — it is an overlay that mimics one as
 * closely as the public accessibility APIs allow (see NativeLikeCursor.md).
 *
 * WAIT/BUSY is intentionally absent: stock Android accessibility exposes no
 * reliable "app is busy" signal, and the spec forbids states that cannot be
 * reliably determined. Resize states exist in the model/renderer and are
 * resolved only when a node explicitly identifies itself as a resize control
 * (see [CursorContextResolver]); otherwise they fall back to [ARROW].
 */
enum class CursorIcon {
    /** Default pointer for normal content. Hotspot: arrow tip (top-left of the glyph). */
    ARROW,

    /** Interactive/clickable enabled UI. Hotspot: index fingertip. */
    HAND,

    /** Editable text field. Hotspot: centre of the beam. */
    IBEAM,

    /** Horizontal resize (↔). Hotspot: centre. */
    RESIZE_HORIZONTAL,

    /** Vertical resize (↕). Hotspot: centre. */
    RESIZE_VERTICAL,

    /** Diagonal resize along NW-SE (↖↘). Hotspot: centre. */
    RESIZE_DIAGONAL_1,

    /** Diagonal resize along NE-SW (↗↙). Hotspot: centre. */
    RESIZE_DIAGONAL_2,
}
