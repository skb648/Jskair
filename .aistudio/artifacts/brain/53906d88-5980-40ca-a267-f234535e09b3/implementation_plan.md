# AirControl Pro: Zero-Latency Predictive Pipeline & Production Readiness Upgrade

This upgrade transforms AirControl from a CPU-bound 24–30 FPS gesture tracker into a **hardware-accelerated, 60 FPS capture + 120Hz VSYNC predictive spatial control system** with zero-allocation hot paths, selectable response profiles, an interactive latency test arena, live photon-to-cursor telemetry, and an Android Quick Settings Tile.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> All core architectural and product preferences were confirmed during our interactive discovery session:

- **Confirmed Decision 1 — Hardware Acceleration & 60 FPS Tier**: Enable automatic **MediaPipe GPU Delegate** initialization with deterministic fallback to `Delegate.CPU` (plus runtime health monitoring) and unlock a **60 FPS Ultra-Low-Latency** camera analysis tier alongside `15`, `24`, `30`, and `60` FPS options.
- **Confirmed Decision 2 — Predictive Zero-Latency Motion Engine**: Eliminate the cascaded double-smoothing lag between `OneEuroFilter` and `CursorOverlay`, introduce **kinematic velocity lead compensation (`leadMs`)** to cancel sensor-to-display latency, and expose selectable **Cursor Response Profiles** (*Zero-Latency Instant*, *Balanced Pro*, *Precision Stabilized*).
- **Confirmed Decision 3 — Zero-Allocation Hot Path & Urgent Display Priority**: Elevate the camera analysis executor thread to `Process.THREAD_PRIORITY_URGENT_DISPLAY`, reuse `Canvas` and `Paint` instances without redundant full-frame clear passes, and eliminate per-frame object boxing (`Pair<Float, Float>` and duplicate landmark list allocations).
- **Confirmed Decision 4 — Production Polish & System Integration**:
  1. **Android Quick Settings Tile**: System-wide notification shade tile to toggle AirControl or switch response modes from any app in one tap.
  2. **Live Photon-to-Cursor Latency HUD**: Real-time telemetry readout displaying active delegate (`GPU` / `CPU`), actual capture FPS, inference time (`ms`), and end-to-end pipeline age.
  3. **Interactive Cursor Latency Test Arena**: Built-in target reflex & tracking arena right on the Home screen so users can immediately test and feel 0-latency cursor responsiveness, pinch clicks, and dwell/blink precision.

---

### 1. Overview & Core Concept

- **What It Does**: Delivers an instantaneous, hardware-accelerated touchless control experience for Android where hand and eye movements translate into sub-pixel 120Hz cursor motion with near-zero perceptual latency.
- **Target Audience / Persona**: Power users, accessibility users, tablet/foldable desk setups, and hands-free media/navigation users who expect spatial tracking as responsive as a physical mouse or Apple Vision Pro pointer.
- **Key Value**: Removes the ~35–50 ms "rubber-band" phase delay caused by CPU inference bottlenecks and double low-pass filtering, replacing it with GPU inference (~4–8 ms), 60 FPS frame ingestion, kinematic lead prediction, and instant system-level controls.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Instant Power & Live Telemetry Flow**: On the Home screen, the user taps the animated power button (or toggles the **AirControl Quick Settings Tile** from the Android notification shade). Within milliseconds, the **Live Pipeline Telemetry Bar** lights up with the active hardware delegate badge (`GPU ACCELERATED` or `CPU SAFE`), real-time FPS (`60 FPS`), inference time (`5.2 ms`), and photon-to-cursor latency (`11 ms`).
  2. **Response Profile Switching**: From either the Home screen or Settings, the user switches between three precision-tuned **Response Profiles**:
     - **Instant (0-Latency)**: High `beta`, tight `minCutoff`, 14 ms kinematic velocity lead prediction, and zero overlay lag — engineered for rapid navigation and gaming-grade responsiveness.
     - **Balanced Pro**: Optimal blend of immediate saccade/sweep response and rock-solid rest stabilization.
     - **Precision Stabilized**: Enhanced micro-tremor damping for hitting dense UI controls or small text links at a distance.
  3. **Interactive Latency & Target Test Arena**: Directly on the Home screen below the Biometric Hand HUD, the user can expand or interact with the **Live Target Arena** — a responsive canvas with glowing precision targets that react instantaneously to cursor hover, pinch-click, blink-click, or dwell, measuring hit reaction time and demonstrating zero-latency tracking in real time.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: High-precision Tactical Biometric HUD ("Iron Man / VisionOS Pro" dark telemetry aesthetic with crisp light-mode adaptability).
  - *Color Palette & Mood*: Deep obsidian canvas (`NavyBackground #0D1117`, `DarkSurface #161B22`) paired with high-contrast `ElectricBlue (#2F81F7)` active vectors, `SuccessGreen (#3FB950)` for sub-15ms GPU telemetry badges, `WarningOrange (#D29922)` for thermal/power-save advisories, and `ErrorRed (#FF6B62)` for critical alerts.
  - *Typography & Hierarchy*: Bold geometric headlines for status and latency readouts paired with tabular monospace-style numeric chips for live FPS/ms telemetry so rapidly updating numbers never jitter horizontally.
  - *Component Styling & Layout*: Rounded 16dp cards with subtle borders (`OutlineDark`), segmented pill selectors for FPS (`15 / 24 / 30 / 60 FPS`) and Response Profiles (`Instant / Balanced / Precision`), and a custom-drawn `Canvas` Target Arena with spring-animated hit ripples.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Auto GPU Delegate with Deterministic CPU Fallback**
  - *Chosen Approach*: Attempt `Delegate.GPU` first during `HandTracker` and `FaceTracker` initialization (when hardware acceleration is enabled), wrapped in a safe verification guard that automatically falls back to `Delegate.CPU` if the OEM OpenGL/Vulkan driver rejects initialization or faults, and records the active delegate in `PerfTelemetry`.
  - *Why*: GPU inference cuts landmark extraction time by 65–80%, which is essential to sustain 30–60 FPS without thermal throttling or frame queue buildup, while automatic fallback preserves 100% compatibility across all devices.
  - *Alternatives Considered*: Hardcoding GPU-only (rejected because certain budget OEM drivers fail on background service contexts) or staying CPU-only (rejected because CPU inference caps practical throughput around 20–25 FPS).

- **Decision 2: Replacing Cascaded Double-Filtering with Kinematic Velocity Lead + VSYNC Sub-Frame Interpolation**
  - *Chosen Approach*:
    1. Upgrade `CursorSmoother` (`OneEuroFilter`) with **kinematic velocity lead extrapolation** (`predicted = filtered + velocity * leadSec`, clamped to prevent overshoot) and response profile presets.
    2. Upgrade `CursorOverlay` so that when a new camera sample arrives, it calculates the exact velocity vector toward the target and uses the 120Hz `Choreographer` loop strictly for **frame-interval sub-stepping** rather than applying a second sluggish exponential low-pass filter on top of `OneEuroFilter`. In *Instant* mode, target updates snap with zero secondary lag.
  - *Why*: Applying an exponential filter (`tau ≈ 18ms`) in `CursorOverlay` *after* `OneEuroFilter` added ~25–35 ms of unnecessary phase delay. Removing the cascaded lag while maintaining 120Hz VSYNC sub-pixel translation gives the user immediate 1:1 physical lock with their hand or eyes.

- **Decision 3: Zero-Allocation Frame Transport & Urgent Display Thread Priority**
  - *Chosen Approach*: Boost the `aircontrol-analysis` thread to `Process.THREAD_PRIORITY_URGENT_DISPLAY` (`-8`), cache the `Canvas` and `Paint` objects per leased bitmap slot in `CameraService`, remove the redundant `PorterDuff.Mode.CLEAR` pass prior to opaque full-frame overwrite, pack `(x, y)` coordinates into primitive 64-bit `Long` values (`Float.floatToRawIntBits`), and eliminate intermediate landmark list copies between `HandFrame` and `HandInput`.
  - *Why*: Eliminates young-generation GC pauses and OS scheduler preemption during active cursor movement.

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

#### Architecture & Component Diagram

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                   CAMERA & HARDWARE INFERENCE LAYER (60 FPS)                  │
│                                                                               │
│  [CameraX RGBA_8888] ──► [aircontrol-analysis (THREAD_PRIORITY_URGENT_DISPLAY)]│
│                                     │                                         │
│                     Zero-Clear Cached Canvas Transform                        │
│                                     ▼                                         │
│                      [SlotPool (5 Leased Bitmaps)]                            │
│                        ├──► HandTracker (GPU ──► CPU Fallback)                │
│                        └──► FaceTracker (GPU ──► CPU Fallback)                │
└─────────────────────────────────────┬─────────────────────────────────────────┘
                                      │ Zero-Allocation Primitive / Reused Frame
                                      ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│                 PREDICTIVE MOTION & GESTURE ENGINE LAYER                      │
│                                                                               │
│  [GestureEngine / Eye Pipeline]                                               │
│         │                                                                     │
│         ▼                                                                     │
│  [CursorSmoother: OneEuro + Kinematic Lead Extrapolation (x + v·dt_lead)]     │
│   ├─ Profile: INSTANT (0-Latency Lead, High Beta, Direct VSYNC Lock)          │
│   ├─ Profile: BALANCED (Adaptive Ballistic Boost + 120Hz Glide)               │
│   └─ Profile: PRECISION (Micro-Tremor Clamp + Fine Target Lock)               │
└─────────────────────────────────────┬─────────────────────────────────────────┘
                                      │ Atomic Packed-Long LatestWinsTransport
                                      ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│              120Hz VSYNC OVERLAY & SYSTEM INTEGRATION LAYER                   │
│                                                                               │
│  ┌──────────────────────────┐  ┌──────────────────────────┐                   │
│  │ CursorOverlay (120Hz)    │  │ AirControlTileService    │                   │
│  │ Sub-Pixel GPU Translation│  │ Quick Settings Toggle    │                   │
│  └──────────────────────────┘  └──────────────────────────┘                   │
│  ┌──────────────────────────┐  ┌──────────────────────────┐                   │
│  │ Live Latency Telemetry   │  │ Interactive Latency      │                   │
│  │ (FPS / GPU / Photon Age) │  │ Target Test Arena (Home) │                   │
│  └──────────────────────────┘  └──────────────────────────┘                   │
└───────────────────────────────────────────────────────────────────────────────┘
```

#### Data Model & State Extensions

- **`CursorResponseProfile` Enum**:
  - `INSTANT` (`minCutoff = 1.85f`, `beta = 28.0f`, `leadMs = 14f`, `directVsyncSnap = true`)
  - `BALANCED` (`minCutoff = 1.25f`, `beta = 16.0f`, `leadMs = 8f`, `directVsyncSnap = false`)
  - `PRECISION` (`minCutoff = 0.75f`, `beta = 8.0f`, `leadMs = 0f`, `directVsyncSnap = false`)
- **`UserPreferences` & `SettingsRepository`**:
  - Add `cursorResponseProfile: CursorResponseProfile = CursorResponseProfile.INSTANT`
  - Add `gpuAccelerationEnabled: Boolean = true`
  - Support `analysisFps` up to `60` (`15`, `24`, `30`, `60` FPS tiers) in `AdaptiveFpsController` and `CameraService` (raising `EYE_MODE_FPS_CAP` to `60` when GPU delegate is active).
- **`PerfTelemetry` Extensions**:
  - Track `handDelegate: String` (`"GPU"` / `"CPU"`), `faceDelegate: String` (`"GPU"` / `"CPU"`), and live `endToEndLatencyMs: Long` (photon-to-cursor age) exposed via a lightweight state snapshot for the Home screen Live Telemetry HUD and Debug screen.

#### Interactive Component & State Mapping

1. **Home Screen Live Telemetry & Response Selector**:
   - Displays a sleek **Live Pipeline Telemetry Card** when tracking is active: active delegate chip (`GPU` / `CPU`), live capture FPS, inference latency (`ms`), and end-to-end cursor latency (`ms`).
   - Provides a 1-tap **Cursor Response Profile** selector (*Instant 0-Lag*, *Balanced*, *Precision*) that immediately updates `SettingsRepository` and hot-reloads `GestureControlAccessibilityService` filter parameters without restarting the camera.
2. **Home Screen Interactive Latency Test Arena**:
   - An expandable interactive card featuring a target practice canvas where glowing bullseye targets spawn inside the arena.
   - Moving the AirControl cursor over a target and clicking (via pinch, blink, dwell, or tap) registers a hit, triggers haptic + visual ripple feedback, and computes average target acquisition time (`ms`) and accuracy.
3. **Android Quick Settings Tile (`AirControlTileService`)**:
   - Registered in `AndroidManifest.xml` with `BIND_QUICK_SETTINGS_TILE`.
   - Reflects real-time `CameraService.serviceState` (`STATE_ACTIVE` when tracking, `STATE_INACTIVE` when paused/off) and allows toggling tracking on/off from any screen.
4. **Settings Screen Enhancements**:
   - Adds `60 FPS` to the Analysis FPS segmented selector.
   - Adds the **GPU Hardware Acceleration** toggle and **Cursor Response Profile** selector under the Cursor & Performance sections.
