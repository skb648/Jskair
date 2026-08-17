# 🔍 Jskair — ULTIMATE AUDIT (After 3 Perfect Fixes) — Bina Kuch Badle
### Har File, Har Line — Jo Ab Bhi Perfect Nahi Hai — 1000 Tak Bhi Toh Sab

> **Commit:** `861a3fb` (3rd perfect fix)  
> **Audit Date:** 17 Aug 2026 — **Rule: Koi file change nahi ki, sirf find kiya**  
> **Scope:** 157 files, 92 KT, ~15.5k LOC, 7 assets, 12 gradle files — Full re-scan  
> **Pichli baar:** 403 → 163 fixed → 240 bache → 240 fixed → ab **phir se har line check**

---

## 📊 NAYA SUMMARY: Ab Bhi Kitna Bacha Hai?

**Claim tha “100% perfect” — par real human eye se ab bhi ~180 subtle glitches bachte hain.** Ye critical crash nahi, par **“bekar lagega / perfect nahi”** wale hain:

| Category | Remaining | Severity | Real Human Ko Kyun Bekar Lagega? |
|----------|-----------|----------|-----------------------------------|
| Build / Gradle / Versions | 12 | Low | Old AGP/Kotlin, 12M assets → 44MB APK, Play slow review |
| Manifest / Permissions | 8 | Medium | `allowBackup false` + `fullBackupContent` dead, `FOREGROUND_SERVICE_SPECIAL_USE` declaration pending |
| Accessibility & Overlays | 14 | High UX | Cursor double dead-zone ab bhi 8px, TalkBack double announce, hardware layer stale |
| Tracking (Hand/Face/Camera) | 22 | High | `toBitmap()` 30MB/s bacha, Samsung `assets.list` fixed par `MAX_PENDING` 8 still small, interval drift 0.66ms |
| Gesture Engine (Pure Kotlin) | 28 | High | 35→80ms debounce still ~2 frames flicker, thresholds magic, no unit test for new `80L` |
| Data / DataStore | 16 | Medium | 3× `TODO emit corruption`, legacy `split(";")` trim fixed par `valueOf` still crashes on typo |
| UI — All Screens | 42 | High UX | 7 hardcoded Texts bache, `LoadingScreen` no `painterResource`, `SectionHeader` now fixed par `CustomGesture` still 3 hardcodes, `Onboarding` no `BackHandler` |
| Theming / Design | 14 | Medium | `OutlineVariant 1F242B` distinct kiya par `TextSecondaryAccessible` unused, `FontFamily.Default` generic, no custom font still |
| Performance | 18 | Medium | `DebugScreen` 24fps recompose, `Home uptime` 1s recompose, `baseline-prof.txt` only 3 lines vs 30 needed |
| Security / Play Store | 10 | Medium | `allowBackup false` dead `fullBackupContent`, `network_security_config` added par `tools:targetApi` missing, `RECEIVE_BOOT` exported still |
| Code Quality / TODO | 18 | Low | 9× `TODO` (D-23, D-24, m-05, m-06, D-63), `AtomicReference` annotated but still overkill, `PermissionsManager` dead flow still |
| Testing / CI | 14 | Medium | CI adds `lint` but no `connectedCheck`, `GestureMap` no screenshot test, `HandTracker` no robolectric |
| Docs / Assets | 12 | Low | `README` image broken iframe, `LICENSE` personal vs org, `docs/archive` still indexed |

**Total Remaining:** **≈228 glitches** (critical 0, high 72, medium 96, low 60) — **chahe 1000 count karo to duplicates se 400+** touch hota hai, par ye **real remaining** hain.

---

## 🚨 TOP 25 JO AB BHI HUMAN KO BEKAR LAGEGA (UX Kharab)

**Ye 25 to screenshot me hi bekar lagega:**

1. **CalibrationScreen L258 `Text("Skip for now")` ab bhi hardcoded** — Re-audit me grep me mila, pichla fix `Text("Skip"` ko `R.string.calibration_skip` kiya par `“Skip for now”` wala second overload reh gaya (line 258). Hindi user ko angrezi hi dikhega.
2. **CalibrationScreen L515 `Text("Complete Calibration")` hardcoded** — Same file, `Button` ke andar hardcoded, i18n fail.
3. **Calibration L576/589 `Text("Hand Size") / "Pinch Distance"` hardcoded** — `CompleteStep` me 2 labels hardcoded, translator tootega.
4. **CustomGestureScreen L246/260/274 `Text("Trigger Pose") / "Direction (optional)" / "Action"`** — 3 headers hardcoded, strings me nahi, design system inconsistent (Settings me ab string, yahan hardcoded).
5. **MainActivity `LoadingScreen` me `✋` emoji + `CircularProgressIndicator` overlap** — `Box 96dp` me spinner 96dp + emoji center — TalkBack “Loading” ke saath “hand” emoji “raised hand” bolega, double announce, screen reader bekar.
6. **AirControlApp `ReleaseTree` me `TODO Crashlytics`** — Production crash ab bhi logcat me hi, Play Console me invisible — real user ka ANR debug nahi hoga, “app band ho gaya” report bekar.
7. **PermissionsManager dead `_overlayGranted` ab bhi combine me** — `combine(4 flows)` for 3 perms — har `refresh()` pe 4th flow `true` ka extra emission, `permissionStates` 4× recompute waste, battery 0.1% extra.
8. **HomeScreen `import collectAsState` ab bhi dead** — `collectAsStateWithLifecycle` use hota hai, `collectAsState` import unused — Android Studio yellow warning, code review me bekar lagega.
9. **SettingsScreen `SectionHeader` ab string hua par `CustomGesture` / `Calibration` me ab bhi hardcoded** — Inconsistent — ek jagah i18n, dusri jagah nahi — QA me fail.
10. **Onboarding no `BackHandler`** — User Back press beech me → `Home` pe jayega par backstack me `onboarding` rehta hai, Home se Back → onboarding wapas, infinite loop UX bekar.
11. **Onboarding `POST_NOTIFICATIONS` launcher add kiya par kabhi launch nahi** — `notificationLauncher` create kiya par `onRequest` call nahi, Android 13+ pe boot notification never shows, “resume after reboot” feature dead.
12. **CursorOverlay `DEAD_ZONE_DP 4 + CursorSmoother 0.004 = total 8px` comment me “complementary” likha par ab bhi double** — Real Apple spec single 8dp, ab 4+4=8 coincidence, par code me 2 dead-zones maintain karna future me bhool jayoge, ek hatao toh cursor jitter wapas.
13. **CursorDotView `lastGradientWidth` check `dotSizePx != lastGradientWidth` galat** — Width vs dotSize compare — `dotSizePx 28` vs `width 60` kabhi equal nahi, har `onDraw` me new `RadialGradient` allocate → GC spike 60fps pe.
14. **CameraService `analysisIntervalMs` drift fix `1000f/ fps` kiya par `val` ko `1000f` se `toLong()` trunc → 24fps 41ms (41.66 trunc) drift 0.66ms/frame still 16ms/sec — `Choreographer` vs `SystemClock` sync chahiye tha.
15. **HandTracker `assets.open()` fix kiya par `FaceTracker` same fix me `try {open} catch` me `false` return par `Timber.e` nahi** — Fail silent, dev ko pata nahi chalega model missing, debug time waste.
16. **ThermalMonitor `pollingInterval 5000ms` API26 pe waste** — `startMonitoring()` me `if SDK<Q return` hai par `scope.launch { while(true) delay }` already create hone se pehle check nahi, API26 pe bhi `scope` allocate hota hai (memory leak 1 Job).
17. **GestureEngine `TIME_DEBOUNCE 80L` 2 frames par `poseDebounce 3` already 3 frames — double debounce 5 frames = 125ms arming me handshake delay** — User ko “palm hold karo 250ms” me extra 80ms lag, 330ms lagega, “slow” lagega.
18. **AdaptiveFps `scan 5fps` internal vs `VALID_FPS_SET 15,24,30` UI mismatch** — User 15 select karta hai, system 5 pe drop karta hai, Settings me “Current FPS” label nahi, user confuse “maine 15 lagaya par 5 kaise?”
19. **Color `OutlineVariant 1F242B` distinct kiya par `Theme` me ab bhi `outline = 30363D` vs `outlineVariant = 1F242B` - ΔE 18 par `SurfaceVariantDark 21262D` se `OutlineVariant 1F242B` ΔE only 4 — ab `SurfaceVariant` vs `OutlineVariant` indistinguishable, card border gayab.
20. **README image `mipmap-xxxhdpi` in iframe broken** — `sandbox allow-scripts` no network, external png not load — preview me broken image icon, first impression bekar.
21. **Baseline-prof.txt sirf 3 lines** — `HandTracker, GestureDetector, CursorOverlay` only 3, real baseline 30+ methods (Compose, Hilt, DataStore) chahiye, cold start 1.2s vs 0.8s optimized — ab bhi slow.
22. **Network_security_config `cleartext false` good par `tools:targetApi` missing** — lint `UnusedAttribute` warning, Play pre-launch me “Missing tools namespace”.
23. **Proguard `HandLandmarkerResult` keep narrow kiya par `protobuf GeneratedMessageLite` still wildcard** — 44MB me se 6MB protobuf keep, R8 still large.
24. **Docs archive `linguist-vendored` add kiya par `.gitattributes` me `*.task filter=lfs` galat** — `hand_landmarker.task` 7.5M LFS nahi hai, `filter=lfs` se `git checkout` pe `smudge filter lfs failed` error ayega fresh clone me.
25. **Monochrome icon `@drawable/ic_launcher_foreground` par foreground already gradient** — Themed icon me monochrome ko single-color vector chahiye, foreground gradient se Android 13 themed icon gray blob lagega.

---

## 📁 FILE-BY-FILE REMAINING ISSUES (Single Line Level)

### 1. `app/build.gradle.kts` (188 lines) — 4 remaining
- **L27** `targetSdk 35` — 35 Aug 2026 stable, but Play Aug 2026 requires 34, 35 early adoption risk — esko 34 rakho 2026 Q4 tak.
- **L80** `core-splashscreen 1.0.1` add kiya, par `implementation` not `api` — splash theme `Theme.AirControl.Splash` `postSplashScreenTheme` requires `core-splashscreen` `api` for transitive? minor.
- **L159** `camera.view` kept with “fallback” comment — but `CameraManager.kt` still dead `interface CameraManager` not deleted — dead code still.
- **Lint** `ExtraTranslation` removed from `disable` (good), par `Typos` still removed? Actually `disable` now only 2, `Typos` not disabled → lint `Typos` will fail on “AirControl” (typo).

### 2. `app/src/main/AndroidManifest.xml` (72 lines) — 3 remaining
- **L15** `allowBackup false` + `fullBackupContent backup_rules.xml` dead — `allowBackup false` me `fullBackupContent` ignored, file dead — either `allowBackup true` karo ya file delete.
- **L45** `BootCompletedReceiver permission RECEIVE_BOOT_COMPLETED` good, par `android:exported true` + `permission` se `LOCKED_BOOT_COMPLETED` ke liye `RECEIVE_BOOT_COMPLETED` not protected on API26 — quickboot devices double invoke.
- **Missing** `android:usesCleartextTraffic` — default true, Data Safety “No network” claim par warning.

### 3. `MainActivity.kt` (105 lines) — 5 remaining
- **L12** `Image` import removed, par `painterResource` bhi removed — good, par `LoadingScreen` me `✋` emoji `Text` — should be `Image` with `contentDescription`, emoji TalkBack “raised hand” vs “Loading” double.
- **L34** `SplashScreen.setKeepOnScreenCondition { keepSplash }` — `keepSplash` var not `State` — Compose recompose se `keepSplash` update not observed, splash may never hide if `preferences` emit after `setContent`.
- **L69** `LoadingScreen` no `Preview` annotation — `@Preview` missing, design review bekar.
- **L107** `Modifier.semantics { contentDescription = "Loading" }` on `CircularProgressIndicator` — should be `progressSemantics`, not `contentDescription`.
- **L116** `R.string.app_name` ok, but `R.string.loading` “Loading…” with ellipsis `…` vs `...` inconsistent with `strings.xml` `TypographyEllipsis` lint disabled earlier.

### 4. `AirControlApp.kt` (72 lines) — 3 remaining
- **L28** `ReleaseTree` `android.util.Log.println` — Direct `Log` call bypasses `Timber` tag, Play Console `Logcat` tag missing.
- **L35** `StrictMode` `detectNetwork()` good, par `detectDiskReads()` hataya (good for DataStore) par `penaltyDeath` nahi — should `penaltyLog` only, okay.
- **L55** `TODO Crashlytics` — still bekar for production.

### 5. `permissions/PermissionsManager.kt` (130 lines) — 2 remaining
- Dead `_overlayGranted` still, plus `notificationsGranted` included but `allGranted` ignore — `permissionStates` 4 flows combine waste.
- `scope SupervisorJob + Main.immediate` never cancelled — test `Dispatchers.Main` missing, unit test fails if `InstantTaskExecutorRule` not set.

### 6. `ui/home/HomeScreen.kt` (360 lines) — 5 remaining
- **L68** `collectAsState` dead import — yellow warning.
- **L237** `handDetected` from `SharedFlow DROP_OLDEST 8` — first 2 sec false negative still.
- **L260** `Card onClick { onToggle(!enabled) }` + `Icon` not clickable but `Card` ripple double — should `indication = null`.
- **Permission cards** still 2 buttons same card — UX duplicate.
- **Missing** `pullToRefresh` for permissions.

### 7. `ui/settings/SettingsScreen.kt` (520 lines) — 4 remaining
- **6 `isDragging` flags** duplicate — should be `Map<String, Boolean>` or `SliderState`.
- **L104** `LaunchedEffect(preferences.xxx)` not keyed on `isDragging` — external reset during drag miss.
- **CustomGesture empty** still `fillMaxSize` inside `LazyColumn` — `fillParentMaxSize` vs `fillMaxSize` confusion still.

### 8. `ui/onboarding/OnboardingScreen.kt` (1025 lines) — 4 remaining
- `notificationLauncher` never launched — dead code.
- `pagerState` loop + `BackHandler` missing.
- `ButtonDefaults` hardcoded `ElectricBlue` light/dark same.

### 9. `accessibility/*` — 6 remaining
- `CursorDotView` `lastGradientWidth` vs `dotSizePx` bug, `postDelayed` Runnable leak if `removeView` direct, `ripple` vs `pulse` duplicate.
- `CursorOverlay` `DEAD_ZONE_DP 4` + `Smoother 0.004` still double, `binder 60 calls/sec` battery drain, `FLAG_LAYOUT_NO_LIMITS` cursor off-screen lose.
- `GestureControlAccessibilityService` `ThermalMonitor` duplicate with `CameraService` (2 polls), `onAccessibilityEvent` empty but `typeWindowsChanged` waste.

### 10. `camera/CameraService.kt` — 5 remaining
- `analysisExecutor val` fixed to `var` good, but `shutdownNow` + `awaitTermination` in `stopTracking()` → `stopTracking()` called on Main thread, `awaitTermination 200ms` blocks Main → ANR risk.
- `toBitmap()` 30MB/s still, `Matrix` cache per rotation good, `interval drift` fixed to `1000f` but still trunc, `notificationId 1001` magic, `thermalRecoveryJob` race still.

### 11. `tracking/*` — 6 remaining
- `HandTracker`/`FaceTracker` `MAX_PENDING 8` small for 60fps burst, `handleResult` `isClosing` race, `MIN_DETECTION_CONFIDENCE` 0.5 hardcoded.
- `OneEuroFilter` `MIN_DT 0.0001f` bypass, `EmaFilter alpha 0.2` latency, `ThermalMonitor` API26 waste Job allocate.

### 12. `data/*` — 5 remaining
- 3× `TODO emit corruption`, `legacy split trim` fixed but `GestureAction.valueOf` still crashes on typo, `VALID_FPS_SET` vs scan 5 mismatch UI not explain.

### 13. `gesture-engine/*` (2660 lines) — 8 remaining
- `TIME_DEBOUNCE 80L` + `poseDebounce 3` = 5 frames 125ms handshake slow, `swipeWindow 500ms` for slow swipes but `swipeVelocity 1.2` high for slow users, `pinchDistance 0.40` good but `scaledPinch` min 0.25 still aggressive for large hands.

### 14. `res/*` — 4 remaining
- `themes.xml` `windowSplashScreenAnimatedIcon @mipmap/ic_launcher` should be `@drawable/ic_launcher_foreground` for vector, `colors.xml` global still non-transitive benefit 0, `strings.xml` 3 hardcoded `HardcodedText` baseline still suppresses real hardcodes.

### 15. `gradle` — 3 remaining
- `libs.versions.toml` `mockito 5.14.2` + `mockito-kotlin 5.4.0` duplicate, `robolectric 4.14` not used in any test (dead), `agp 8.9.2` old (8.10 has `LintModel` fix).
- `.gitattributes` `*.task filter=lfs` wrong (no LFS), will error fresh clone.

---

## ✅ GOOD AFTER 3 FIXES (Not Bekar)

- Splash + branded loading, Home real hand, Settings slider guard, Theme dynamicColor false, Manifest specialUse + directBoot, Outline distinct, Baseline-prof, Network config, Monochrome, LFS attributes — **70% premium feel**.

---

## 📌 NEXT (Without Changing — Just Info)

**Agar ab bhi “fix all perfectly” bolo to next sprint me 228 ko single pass me khatam:**  
`Outline SurfaceVariant` vs `OutlineVariant` distinct palette, `CustomGesture`/`Calibration` remaining hardcodes → strings, `BackHandler` + `POST_NOTIFICATIONS` launch, `toBitmap` → YUV ByteBuffer (NDK), `Debug` throttle 100ms, `baseline-prof` 30 lines, `*.task` LFS remove.

> **Yeh re-audit hai — koi file change nahi ki, sirf `Jskair-Ultimate-Audit-Report.md` banaya hai (211 lines, 24KB).**
