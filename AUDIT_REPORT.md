# AirControl — Full Production Readiness Audit

**Project:** AirControl (com.aircontrol)
**Date:** 2026-08-02
**Scope:** Every source file, build config, CI/CD, resources, tests, and docs

---

## Executive Summary

AirControl is an Android app that enables hands-free device control via front-camera hand gesture recognition using MediaPipe. The architecture is well-structured (Hilt DI, MVVM, Compose UI, multi-module), the codebase is heavily commented, and many bugs have already been fixed in prior iterations. However, **the project is NOT production-ready**. There are critical gaps in release signing, several real bugs that will crash or misbehave on real devices, significant CI/CD inconsistencies, missing production infrastructure (crash reporting, analytics), and numerous code quality issues that will hurt maintainability.

**Verdict: ~65% production-ready. Requires 2–4 weeks of focused work before real users can safely use it.**

---

## 🔴 CRITICAL ISSUES (Must fix before any release)

### C-01: No Release Signing Configuration
**File:** `app/build.gradle.kts` (lines 41–48, commented out)

The entire release signing block is commented out with a TODO. You **cannot**:
- Publish to Google Play Store
- Distribute a signed release APK
- Use App Signing by Google Play properly
- Maintain upgrade continuity (unsigned → signed = different signing key = users must uninstall)

```kotlin
// TODO: Configure release signing before production release
// signingConfigs {
//     create("release") { ... }
// }
```

**Impact:** No release build is possible. This is a hard blocker.

---

### C-02: DataStore Migration Test Will FAIL — Entry Count Mismatch
**File:** `app/src/androidTest/java/com/aircontrol/DataStoreMigrationTest.kt`

```kotlin
@Test
fun defaultGestureMapHasExpectedEntryCount() = runTest {
    val config = settingsRepository.gestureMapConfig.first()
    assertEquals(9, config.entries.size)  // ❌ WRONG
}
```

The actual default entry count in `GestureMapConfig.defaultEntries()` is **10** (includes `pose_pinch_hold`). Similarly, `defaultGestureMapContainsAllExpectedKeys` expects 9 keys but misses `pose_pinch_hold`. These tests will **fail on CI and on device**, blocking any release pipeline.

---

### C-03: State Machine Test Contradicts Production Code — PINCH Should NOT Trigger EXECUTING
**File:** `gesture-engine/src/test/kotlin/com/aircontrol/gesture/statemachine/GestureStateMachineTest.kt`

```kotlin
@Test
fun `ARMED triggers execution on PINCH`() {
    armSystem()
    val result = stateMachine.process(Pose.PINCH, true, 2000L)
    assertEquals(GestureEngineState.EXECUTING, stateMachine.currentState)  // ❌
    assertTrue(result.shouldExecute)
}
```

But the production code in `GestureStateMachine.processArmed()` explicitly **excludes** PINCH:
```kotlin
if (pose != Pose.NONE && pose != Pose.OPEN_PALM && pose != Pose.FIST &&
    pose != Pose.PINCH && pose != Pose.POINTING &&  // PINCH is excluded!
    pose != lastExecutedPose
)
```

This test **will fail**. The comment in production code says PINCH has its own lifecycle (START/MOVE/END) managed by `GestureEngine.processPinch()`, so the test is wrong.

---

### C-04: Gesture Map Race Condition — `clear()` + `putAll()` Creates Empty Window
**File:** `app/src/main/java/com/aircontrol/accessibility/ActionDispatcher.kt`

```kotlin
scope.launch {
    settingsRepository.gestureMapConfig.collect { config ->
        val newMap = ConcurrentHashMap<String, GestureAction>()
        config.entries.forEach { entry ->
            newMap[entry.key] = entry.action
        }
        gestureMap.clear()    // ← Map is EMPTY here
        gestureMap.putAll(newMap)  // ← Entries restored
    }
}
```

If `dispatch()` is called between `clear()` and `putAll()` (which runs on the Main thread via `Dispatchers.Main.immediate`), the gesture map is momentarily empty and the dispatch silently fails. This can cause **dropped gestures during settings changes**.

**Fix:** Use atomic swap:
```kotlin
private val gestureMap = AtomicReference(ConcurrentHashMap<String, GestureAction>())
```

---

### C-05: CI/CD SDK Version Mismatch
**Files:**
- `app/build.gradle.kts`: `compileSdk = 36`
- `.gitlab-ci.yml`: `ANDROID_COMPILE_SDK: "35"`

The GitLab CI pipeline tries to compile against SDK 35 but the project requires SDK 36. The CI build **will fail** with a "missing platform" error. The GitHub Actions CI doesn't install the SDK at all (it relies on the Gradle plugin to auto-download), which may work but is fragile.

---

### C-06: `CursorOverlay`/`StatusOverlay` Screen Dimensions Cached at Creation
**File:** `app/src/main/java/com/aircontrol/accessibility/StatusOverlay.kt`

```kotlin
private val screenWidth = context.resources.displayMetrics.widthPixels
private val screenHeight = context.resources.displayMetrics.heightPixels
```

These are read **once** at construction time. On foldable devices, screen resolution changes, or display scaling, these values become stale. The cursor/drag bounds will be wrong.

---

## 🟠 HIGH-PRIORITY ISSUES (Will cause bad user experience)

### H-01: No Crash Reporting or Error Telemetry
The app strips all debug/info logs in release builds (ProGuard `assumenosideeffects`) and has no `INTERNET` permission. When a real user experiences a crash or ANR, there is **zero visibility** into what happened. For production you need at minimum:
- Firebase Crashlytics (requires INTERNET permission)
- Or ACRA (sends crash reports via email/HTTP)
- Or a self-hosted crash endpoint

Without this, you're flying blind on production issues.

---

### H-02: Boot Recovery UX is Broken
**File:** `app/src/main/java/com/aircontrol/receiver/BootCompletedReceiver.kt`

On Android 14+ (most devices in 2026), the boot receiver only posts a notification asking the user to open the app. But:
1. The notification has `setSilent(true)` — it won't buzz or beep
2. There's no direct "Resume" action button — tapping opens MainActivity
3. Users who enabled "Start on Boot" will think the feature is broken

**User experience:** "I turned on Start on Boot, rebooted, and nothing happened."

---

### H-03: `CameraService` Manual DI is Fragile
**File:** `app/src/main/java/com/aircontrol/camera/CameraService.kt`

```kotlin
(applicationContext as? com.aircontrol.AirControlApp)?.let { app ->
    val entryPoint = com.aircontrol.di.AccessibilityServiceEntryPoint.getFromApplication(app)
    handTracker = entryPoint.handTracker()
    settingsRepository = entryPoint.settingsRepository()
} ?: run {
    Timber.e("Application is not AirControlApp — cannot inject HandTracker")
}
```

If the cast fails (e.g., multi-process, custom Application subclass in tests), the service silently starts with uninitialized `handTracker` and `settingsRepository`, and the `startTracking()` method calls `stopSelf()` with only a log message. The user sees the foreground service notification but tracking doesn't work.

---

### H-04: `CursorController` Interface is Unused Dead Code
**File:** `app/src/main/java/com/aircontrol/control/CursorController.kt`

The `CursorController`/`CursorControllerImpl` is bound via Hilt in `TrackingModule` and exposed through the `AccessibilityServiceEntryPoint`, but the actual cursor rendering is done by `CursorOverlay` (a View-based overlay in the accessibility service). The `CursorController` state flow is never collected anywhere in the codebase.

This means:
- Dead code that adds complexity
- DI graph includes an unused singleton
- Confusing for new developers ("where is the cursor actually controlled?")

---

### H-05: `AirControlService` Can Conflict with `CameraService`
**File:** `app/src/main/java/com/aircontrol/service/AirControlService.kt`

Both `AirControlServiceImpl` and `CameraService` call `handTracker.initialize()`. If both are somehow active (e.g., UI tests + real service), MediaPipe will be initialized twice, causing undefined behavior or crashes.

The comment says "callers should not run this at the same time" but there's no enforcement.

---

### H-06: `GestureDetectorImpl.updateSensitivity()` Throws Away All State
**File:** `app/src/main/java/com/aircontrol/gestures/GestureDetector.kt`

```kotlin
override fun updateSensitivity(sensitivity: Int) {
    val oldEngine = engineRef.get()
    val newEngine = GestureEngine(GestureEngineConfig(sensitivity = clamped))
    engineRef.set(newEngine)
    oldEngine.stop()
    engineEventsJob?.cancel()
    collectEngineEvents()
    resetStateFlows()  // Resets all flows to DISARMED/NONE/0
}
```

Every time the user adjusts the sensitivity slider, the entire gesture engine is destroyed and recreated. Any in-progress arming, pinch, or swipe is lost. The user experience: they adjust sensitivity while their hand is in an "open palm" arming pose → the system disarms and they must start over.

**Fix:** Make sensitivity a mutable property on the engine/config rather than recreating the engine.

---

### H-07: `OneEuroFilter` and `HandFrameFilter` are Dead Code
**Files:** `app/src/main/java/com/aircontrol/tracking/OneEuroFilter.kt`

The comments in `HandTracker` explicitly say the landmark-level filter was removed, but the classes still exist and are compiled into the APK. The `CursorSmoother` (which IS used) is in the same file, so this isn't a huge waste, but the dead classes are confusing.

---

### H-08: `ActionDispatcher` Creates Unscoped CoroutineScope (Memory Leak Risk)
**File:** `app/src/main/java/com/aircontrol/accessibility/ActionDispatcher.kt`

```kotlin
@Singleton
class ActionDispatcher @Inject constructor(...) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
```

This scope is never cancelled. As a `@Singleton`, it lives for the entire application lifetime, which is fine — but it means the three `collect` blocks in `init` can never be cleaned up. If `ActionDispatcher` were ever not a singleton (e.g., in tests), this would leak.

More importantly, if the settings flows emit rapidly (e.g., during gesture map editing), these collectors can pile up work on the Main thread.

---

## 🟡 MEDIUM-PRIORITY ISSUES

### M-01: Duplicated Service Start/Stop Logic
**Files:** `HomeViewModel.kt`, `SettingsViewModel.kt`

Both ViewModels have identical `startTrackingService()` and `stopTrackingService()` methods. There's even a TODO about extracting to a shared `ServiceManager`. This is a maintenance hazard — if the start logic changes, it must change in two places.

---

### M-02: `CameraService` Static Companion Object State
**File:** `app/src/main/java/com/aircontrol/camera/CameraService.kt`

```kotlin
companion object {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused
}
```

Static state flows on the companion object mean:
- If the service process is killed and recreated, the flows still show the old values briefly
- Tests can't reset this state
- If multiple CameraService instances are created (shouldn't happen, but...), they share state

---

### M-03: `MainActivity` Loading Screen Has No Theme
**File:** `app/src/main/java/com/aircontrol/MainActivity.kt`

```kotlin
if (preferences == null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
    return  // ← No AirControlTheme wrapping!
}
```

While preferences are loading (DataStore read), the loading spinner is shown without the app's dark theme. Users will see a brief flash of the default light theme before the dark theme appears.

---

### M-04: No `INTERNET` Permission Means No Dynamic Content
This is a privacy feature, but it means:
- No remote configuration (feature flags, A/B tests)
- No in-app update checks
- No license verification
- No remote model updates for MediaPipe
- No terms-of-service updates

For a v1.0.0 this is fine, but it severely limits future iteration.

---

### M-05: `SettingsRepositoryImpl` Uses `org.json` (Not Multiplatform-Safe)
**File:** `app/src/main/java/com/aircontrol/data/repository/SettingsRepositoryImpl.kt`

The custom gesture and gesture map serialization uses `org.json.JSONObject`/`JSONArray`. While this works on Android, it's:
- Not null-safe (throws on missing keys)
- Has no schema validation
- Manual serialization is error-prone (and indeed there are multiple bug fixes for serialization issues)

Consider using Kotlinx Serialization for type-safe, validated JSON.

---

### M-06: Accessibility Service Config Only Listens to `typeWindowsChanged`
**File:** `app/src/main/res/xml/accessibility_service_config.xml`

```xml
android:accessibilityEventTypes="typeWindowsChanged"
```

The service only receives window-changed events but performs gestures. This is actually correct since the app doesn't need to observe UI events — it just dispatches gestures. But it means the `onAccessibilityEvent()` callback fires very rarely and cannot be used for any future event-driven features.

---

### M-07: GitLab CI Uses Deprecated `only:` Syntax
**File:** `.gitlab-ci.yml`

```yaml
only:
  - merge_requests
  - main
```

The `only`/`except` syntax is deprecated in favor of `rules:`. Modern GitLab versions may warn or ignore this.

---

### M-08: Calibration Assumes Fixed Hand-to-mm Conversion
**File:** `app/src/main/java/com/aircontrol/ui/calibration/CalibrationViewModel.kt`

```kotlin
val handSizeMm = (avgHandSizeNorm / 0.20f) * 95f
```

The conversion from normalized landmarks to millimeters assumes a fixed ratio (0.20 normalized units = 95mm). This doesn't account for:
- Camera distance variation
- Different phone camera FOVs
- Different hand sizes

The calibration gives a false sense of precision.

---

## 🔵 LOW-PRIORITY / CODE QUALITY ISSUES

### L-01: `kotlin.jvm.Volatile` Import Used Instead of `kotlin.concurrent.Volatile`
Several files import `kotlin.jvm.Volatile` which is fine but deprecated in newer Kotlin versions in favor of `kotlin.concurrent.Volatile`. Not a bug, but generates warnings.

---

### L-02: Hardcoded Colors in Compose Code
Many Compose files use hardcoded colors like `Color(0xFF4CAF50)` instead of the centralized `Color.kt` theme values. This makes theme changes error-prone.

---

### L-03: `allWarningsAsErrors = false` in Both Modules
Both `app/build.gradle.kts` and `gesture-engine/build.gradle.kts` suppress warnings-as-errors. For production code, enabling this (even partially) catches issues early.

---

### L-04: No Lint Baseline
The lint config disables several checks (`MissingTranslation`, `ExtraTranslation`, etc.) but has no baseline file. As the project grows, lint issues will accumulate silently.

---

### L-05: `HomeViewModel` Uses `delay(100)` as a Synchronization Hack
**File:** `app/src/main/java/com/aircontrol/ui/home/HomeViewModel.kt`

```kotlin
permissionsManager.refreshAllPermissions()
kotlinx.coroutines.delay(100)  // ← Fragile race-condition workaround
val perms = permissionStates.value
```

This 100ms delay is a hack to wait for the permission state flow to update. It's not guaranteed and could fail on slow devices.

---

### L-06: `build.gradle.kts` Uses `add("ksp", ...)` Syntax
```kotlin
add("ksp", libs.hilt.android.compiler)
```
This string-based configuration access is fragile. The proper way is to use the KSP plugin and its configuration block.

---

### L-07: No `android:dataExtractionRules` for Android 12+
**File:** `app/src/main/AndroidManifest.xml`

`android:allowBackup="false"` is set, but Android 12+ expects `android:dataExtractionRules` for explicit backup rules. Missing this generates a lint warning.

---

### L-08: No `android:localeConfig` for Per-app Language Preferences
For production, Android 13+ per-app language support requires a `localeConfig` XML. Missing this means the app doesn't appear in the system language picker.

---

### L-09: `ProGuard` Strips Info-Level Timber Logs
```proguard
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
```

Stripping `Timber.i()` in release means you lose informational logs that could help diagnose user-reported issues. Consider keeping `i()` and only stripping `d()` and `v()`.

---

### L-10: `GestureEngine` Public `start(inputFlow)` and `processFrame(input)` Are Two APIs for the Same Thing
The engine can be used in flow-collection mode (`start()`) or push mode (`processFrame()`). The app uses push mode (via `GestureDetectorImpl`), but the `start()` method exists and could confuse users of the engine module.

---

## 📊 TEST COVERAGE ASSESSMENT

| Area | Unit Tests | Instrumented Tests | Assessment |
|------|-----------|-------------------|------------|
| Gesture State Machine | ✅ Good | ❌ None | State transitions well-tested |
| Pose Classifier | ✅ Good | ❌ None | Works |
| Dynamic Gesture Detector | ✅ Good | ❌ None | Works |
| Finger Extension Detector | ✅ Good | ❌ None | Works |
| One Euro Filter | ✅ Good | ❌ None | Works |
| ActionDispatcher (coord mapping) | ✅ Good | ✅ Partial | Tests are thorough |
| Settings Repository | ✅ Partial | ✅ Partial | Missing custom gesture tests |
| Gesture Engine (integration) | ✅ Basic | ❌ None | Only basic tests |
| HomeScreen/Settings UI | ❌ None | ✅ Basic (mocked) | Tests don't use real ViewModels |
| Onboarding | ❌ None | ✅ Stub | Tests are placeholder/mocked |
| CameraService | ❌ None | ❌ None | Zero test coverage |
| CursorOverlay | ❌ None | ❌ None | Zero test coverage |
| ThermalMonitor | ❌ None | ❌ None | Zero test coverage |
| PermissionsManager | ❌ None | ✅ Partial | Basic flow test |

**Major gap:** No tests for the camera pipeline, thermal throttling, cursor overlay, or the accessibility service integration — which are the most complex and failure-prone parts.

---

## 🏗️ ARCHITECTURE CONCERNS

1. **Two Service Layers:** `CameraService` (foreground, production) and `AirControlService` (lightweight, for UI/tests) both manage the hand tracker. This duality is confusing and error-prone.

2. **State Management Fragmentation:** Gesture engine state lives in `GestureEngine`, `GestureDetectorImpl`, `ActionDispatcher`, `GestureControlAccessibilityService`, and `CursorOverlay` — five places tracking overlapping state.

3. **No Error Boundary:** There's no global error handler, no `CoroutineExceptionHandler`, and no user-facing error screens. If the gesture engine crashes, the user just sees the cursor stop moving with no explanation.

4. **Tight Coupling Between Modules:** The `app` module's `ActionDispatcher` directly imports from `gesture-engine` model classes and has detailed knowledge of pinch phases, swipe directions, etc. The abstraction boundary is thin.

---

## 📝 DOCUMENTATION GAPS

1. **No API documentation** (KDoc is good but no generated docs)
2. **No architecture diagram** showing how Camera → HandTracker → GestureDetector → ActionDispatcher → Accessibility flow works
3. **No setup guide** for new developers (how to get the MediaPipe model file, etc.)
4. **No contribution guidelines**
5. **No code of conduct**
6. **Changelog** exists but only has one entry (1.0.0) — will become stale

---

## ✅ WHAT'S DONE WELL

1. **Privacy-first architecture** — No network access, all on-device processing, clear privacy policy
2. **Comprehensive KDoc comments** — Almost every class and method is documented
3. **Hilt DI** — Proper dependency injection throughout
4. **Compose UI** — Modern, well-structured Material 3 UI with proper theming
5. **Thermal management** — Graduated degradation from LIGHT → MODERATE → SEVERE → CRITICAL
6. **Adaptive FPS** — Battery-conscious frame rate management
7. **Pinch hysteresis** — Well-thought-out solution for pinch misfire prevention
8. **One Euro Filter** — Professional-grade cursor smoothing with dead-zone
9. **Data migration** — Schema versioning for gesture map config with backward compatibility
10. **Test structure** — Good unit test coverage for the gesture engine (pure Kotlin module)
11. **ProGuard rules** — Comprehensive keep rules for MediaPipe, Hilt, and serialization
12. **Accessibility** — Content descriptions, semantics, screen reader support in Compose UI

---

## 🎯 PRIORITIZED FIX ROADMAP

### Week 1: Critical Fixes
- [ ] Configure release signing
- [ ] Fix the failing tests (entry count, PINCH state transition)
- [ ] Fix CI/CD SDK version mismatch
- [ ] Fix gesture map race condition (atomic swap)

### Week 2: High-Priority UX
- [ ] Add crash reporting (Firebase Crashlytics or ACRA)
- [ ] Improve boot recovery UX (louder notification, resume action button)
- [ ] Fix sensitivity change to not recreate the engine
- [ ] Add `AirControlTheme` to the loading screen
- [ ] Remove dead code (`CursorController`, unused filter classes)

### Week 3: Hardening
- [ ] Fix screen dimension caching for foldables
- [ ] Extract shared service manager (remove duplication)
- [ ] Add `CoroutineExceptionHandler` for uncaught exceptions
- [ ] Add CameraService tests
- [ ] Add error state UI (what happens when tracking fails)

### Week 4: Production Polish
- [ ] Add per-app language support (localeConfig)
- [ ] Enable `dataExtractionRules` for Android 12+
- [ ] Migrate from `org.json` to Kotlinx Serialization
- [ ] Add architecture documentation
- [ ] Add lint baseline
- [ ] Enable `allWarningsAsErrors` for new code

---

## Final Scorecard

| Category | Score | Notes |
|----------|-------|-------|
| Code Quality | 7/10 | Well-structured, good comments, some dead code |
| Architecture | 6/10 | Good patterns but state fragmentation and service duality |
| Testing | 5/10 | Engine tests good; camera/service/overlay untested |
| Security | 8/10 | No network, no data collection, minimal attack surface |
| Privacy | 10/10 | Best-in-class privacy posture |
| Release Readiness | 3/10 | No signing, failing tests, no crash reporting |
| UX Polish | 6/10 | Good Compose UI, but boot flow and error states need work |
| Documentation | 7/10 | Good KDoc, missing architecture/setup docs |
| CI/CD | 5/10 | GitHub Actions works, GitLab CI has SDK mismatch |
| **Overall** | **6.0/10** | Strong foundation, needs hardening for production |
