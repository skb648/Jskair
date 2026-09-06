# Native-like Cursor — Same-Device Overlay Pointer

**This is NOT the Android OS-native pointer.** It is the Jskair accessibility
overlay redrawn to look and behave as close to a physical-mouse pointer as the
public accessibility APIs allow. Correct terminology: **"Native-like cursor"** /
"physical-mouse-style cursor". (The separate Bluetooth HID POC — see
`NativeHidMouse-POC.md` — is the experiment that targets the *real* native
cursor on a second device; the two are deliberately not connected.)

## What changed (Round 8)

| Area | Before | After |
|---|---|---|
| Glyph | 28dp-wide arrow (~38dp tall) + hover/motion scale breathing | Desktop-scale arrow (~11.6×20.4dp), crisp outline, NO scale breathing |
| Hotspot | tip formula mixed into window math | Explicit hotspot model (`CursorGeometry`): every glyph's semantic point drawn at one anchor; window = logical − anchor |
| States | single arrow | ARROW / HAND / IBEAM / 4 resize states (model+renderer; resolver is conservative) |
| Context | none (pointer never changed) | `CursorContextResolver` + `CursorHitTester` + `CursorHoverMonitor`: debounced accessibility node-under-cursor lookup |
| Click visual | ripple + tap scale | small short press + small ripple (reduced-motion aware), hotspot never obscured |
| Dwell visual | arc drawn OVER arrow | same thin arc, drawn UNDER the glyph, centred on the hotspot, only while progressing |
| Permission | no content read | `canRetrieveWindowContent` + `flagRetrieveInteractiveWindows` (READ-ONLY; adds no action capability) |

## Architecture

```
hand/gaze (unchanged pipelines, One Euro smoothing unchanged)
  → GestureControlAccessibilityService cursor paths (unchanged math)
  → CursorOverlay.updatePosition  (normalized → px, clamp, NaN-guard)
      ├─ window relayout: pos = logical − hotspot   (≤60 Hz, coalesced)
      └─ onPositionApplied(logicalX, logicalY)
           → CursorHoverMonitor (policy: move ≥8dp AND ≥120 ms, never stationary)
               → CursorHitTester (Dispatchers.Default, single-flight, ≤300 node visits)
                   snapshot: className/isClickable/isEnabled/isEditable/ACTION_CLICK
               → CursorContextResolver (pure rules)
               → main thread: overlay.setCursorIcon(HAND/IBEAM/…)
```

## Resolution rules (conservative)

- Disabled node → ARROW (never HAND over disabled UI).
- Editable (`isEditable` or EditText-like class) → IBEAM. Static text stays ARROW.
- Enabled + (`isClickable` or exposes `ACTION_CLICK`) → HAND. Text alone never → HAND.
- Explicit resize-handle class names → resize cursors; generic containers never
  guess — ARROW fallback. WAIT/BUSY: not implemented (no reliable signal).
- No snapshot (no window content / lock screen / our own overlays) → ARROW.

## Hotspot correctness

`CursorGeometry` (pure, JVM-tested): window position = `logical − hotspot`,
recomputed from floats each frame (never int→float accumulation). All glyphs
draw their semantic point (arrow tip, index fingertip, beam centre, resize
centre) at the SAME anchor, so icon switches never move the point, and the
click/hover coordinate always equals the visible arrow tip. Edges: the window
may extend off-screen but the hotspot never leaves the logical point; logical
coordinates are clamped to screen bounds; NaN/∞ are rejected before layout.

## Performance

- Hit-tests: ≤ ~8/s, only while moving, single-flight, off-main, bounded walk
  (≤300 visits, ≤28 depth, bounds-pruned). Stationary cursor → zero scans.
- Renderer: one reused `Path`/`Paint`s; icon switch = one `invalidate()`.
- Position path unchanged: 60 Hz coalesced `updateViewLayout`, no added smoothing.
- Node safety: only scalar facts leave the walker (no `AccessibilityNodeInfo`
  retention; no recycle calls → no use-after-recycle crashes; GC handles nodes).

## Leak audit (LeakCanary context)

New references: service → overlay (existing pattern), service → monitor
(cancelled in `removeOverlays()`/`detachHoverMonitor()`), overlay → view
(removed with window). Monitor holds no View/Activity/node — only the service
scope and a provider lambda owned by the service. `onPositionApplied` is
nulled on detach. The pre-existing LeakCanary report
(`IAccessibilityServiceClientWrapper.mContext` → service, ~9.9 KB) is untouched
by this change: nothing new retains the Service; no exclusions were added.

## Manual test procedure (real device)

1. Enable the accessibility service; enable the cursor (Settings).
2. Move the hand slowly: arrow follows at pointer speed with no bounce/glow.
3. Hover a **button** (e.g. any Settings row): pointer becomes a **hand**;
   move off: back to **arrow** (no flicker, no jump of the tip).
4. Hover a **text field** (e.g. search box): **I-beam**. Static labels: arrow.
5. Disabled control: arrow (not hand).
6. Click (pinch/dwell/blink): tiny press+ripple at the tip; tip stays on the
   exact point that was tapped.
7. Dwell: thin arc grows under the pointer around the tip; pointer shape stays.
8. Drag (pinch-drag): pointer tints blue (unchanged).
9. Edges/corners: push the cursor to all 4 corners — tip stays usable, no jump.
10. Rotate to landscape: cursor stays within bounds; icon logic unaffected.
11. Matrix: empty background, button, text, text input, checkbox, switch,
    slider, list, link, image, disabled button, other apps → expect ARROW /
    HAND / IBEAM per the rules above; ambiguous → ARROW.

## Known limitations

- It is an overlay: apps that draw custom Web/framework cursors won't be
  mimicked per-element (WebView inner links → HAND only when the web node
  exposes click semantics); resize handles are almost never identifiable on
  Android, so resize cursors will be rare in practice.
- Hover icon refresh is movement-driven: a UI change directly UNDER a
  stationary pointer updates on the next small movement (by design — no
  stationary polling).
- No WAIT/busy state (no reliable signal). No sound/beam-shadow animations.
