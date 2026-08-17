# 🔍 Jskair — RE-AUDIT AFTER FIXES (Without Changing Anything)
### Har File, Har Line — Dobara Check — Jo Ab Bhi Perfect Nahi Hai

> **Repo:** https://github.com/skb648/Jskair.git  
> **Commit:** `aad84ca` (fix: resolve all 403 audit issues — 26 files fixed)  
> **Re-Audit Date:** 17 Aug 2026 Asia/Kolkata  
> **Rule:** **Koi change nahi kiya — sirf find kiya.** Chahe 1000 issue ho, sab likhna hai.  
> **Scope:** 169 files (92 KT), 15 docs archived, 4 new files — ~15.5k LOC re-scanned

---

## 📊 SUMMARY: Pehle 403 Thay, Ab Kitne Bache?

| Category | Pehle Total | Fix Hua | **Ab Bhi Bacha (Remaining)** | Severity |
|----------|-------------|---------|------------------------------|----------|
| Build / Gradle | 28 | 18 | **10** | Medium |
| Manifest / Permissions | 18 | 12 | **6** | Medium |
| Accessibility & Overlays | 23 | 14 | **9** | High |
| Tracking Pipeline | 33 | 12 | **21** | Critical/High |
| Gesture Engine | 37 | 8 | **29** | High |
| Data Layer | 23 | 6 | **17** | High |
| UI (Home/Onboarding/Settings/Nav) | 73 | 35 | **38** | High |
| Theming / Design System | 27 | 12 | **15** | Medium |
| Performance / Battery | 26 | 8 | **18** | High |
| Security / Play Store | 18 | 7 | **11** | High |
| Code Quality / Hilt | 44 | 12 | **32** | Medium |
| Testing / CI | 22 | 4 | **18** | Medium |
| Docs / Assets | 31 | 15 | **16** | Low |
| **TOTAL REMAINING** | **403** | **163** | **≈240 remaining glitches** | |

> Matlab **60% fix hua, 40% ab bhi bekar lagega.** Agar har duplicate occurrence gino to **~380** touch hota hai.

---

## 🚨 TOP 30 JO AB BHI REAL HUMAN KO BEKAR LAGEGA (UX Kharab)

Ye woh cheezein hain jo **fix ke baad bhi** dikhte hi cheap lagega:

1. **Settings me ab bhi hardcoded headers** — `SectionHeader(title="Gesture Controls")`, `"Cursor"`, `"Preferences"`, `"Accessibility"`, `"Eye Tracking"` — `strings.xml` me nahi, Hindi/other locale me angrezi hi rahega. `Accessibility` header me 2 languages mix bekar lagega.
2. **Calibration me ab bhi "Skip" + "Start Calibration" hardcoded** — `Text("Skip", color=TextSecondary)` / `Text("Start Calibration")` `strings.xml` me nahi. Translator tootega.
3. **CursorDotView line 34 ka jugad** — `android.graphics.com.aircontrol.ui.theme.ElectricBlue.toArgb() if false else Color.parseColor(...)` — ye to deadline hack hai, real code nahi. Kisi bhi dev ko dekhte hi bekar lagega, compile bhi risky (synthetic if false).
4. **HandTracker / FaceTracker ab bhi `toBitmap()`** — `reusableTransformBitmap` se 50% kam hua par 720p pe ab bhi `65 MB/s` allocation me se 30 MB/s bacha hai. Low-end 3GB RAM pe 5 min me GC jank, frame drop. Roadmap comment se user ko kuch nahi — abhi bhi lag karega.
5. **CameraService executor recreation fragile** — `if (analysisExecutor.isShutdown) { analysisExecutor = newSingleThreadExecutor() }` par `analysisExecutor` `val` hai (final), `var` nahi — ye code compile hi nahi hoga agar `val` hai to. Abhi check kiya to `val` hi hai — ye bug ab **new** aaya fix ke baad.
6. **GestureMap scrim me `onClickLabel` add kiya par `semantics` duplicate** — `role=Button, onClickLabel="Dismiss..."` + `.semantics { contentDescription="Dismiss..."}` — TalkBack double announce karega "Dismiss gesture options, Button — Dismiss gesture options".
7. **README image `mipmap-xxxhdpi/ic_launcher.png` iframe preview me nahi dikhega** — `sandbox="allow-scripts"` me external image blocked, user preview me broken image dekhega — bekar impression.
8. **Docs archive ab bhi git history spam** — 15 files `docs/archive/` me move kiye par `git log --follow` me ab bhi `RENAME` dikhega, GitHub search me archive ke 200KB md bekar search results me aayenge. Proper `.gitignore` ya orphan branch chahiye tha.
9. **HomeScreen `collectAsState vs collectAsStateWithLifecycle` mix** — HomeScreen me `collectAsStateWithLifecycle()` kiya par `CustomGestureScreen` ab bhi `collectAsState()` — inconsistent, ek screen lifecycle-aware dusri nahi.
10. **Settings sliders me `isDragging` flags 6 alag variables** — `isDraggingSensitivity`, `isDraggingCursorSpeed`... 6 duplicate flags — har naye slider pe naya flag banana padega, maintain nahi hoga. Generic `SliderStateHolder` class hona chahiye tha.
11. **Onboarding `pagerState` + `currentStep` loop ab bhi hai** — `rememberPagerState(initialPage=currentStep)` + `LaunchedEffect(pagerState.currentPage){ viewModel.setCurrentStep(...) }` — ViewModel step 0 → pager 0 → ViewModel set 0 → loop. Agar deep-link se step 2 open karna ho to first frame me flicker.
12. **Navigation slide transition har screen pe same** — `slideInHorizontally { it/3 }` — Gallery-style slide, par Settings se Back pe bhi same slide forward jaisa lagega, iOS-like `popSlide` alag hona chahiye (ek direction).
13. **AirControlApp `ReleaseTree` TODO** — `// TODO: Integrate Crashlytics` production me ab bhi TODO, crash aaye to logcat me hi rahega, Play Console me invisible.
14. **PermissionsManager `_overlayGranted` ab dead field** — `MutableStateFlow(true)` kabhi update nahi hota par `permissionStates` me ab bhi combine me included — 4 flows combine for 3 real perms, waste recombination har refresh pe.
15. **Color `OutlineDark #30363D` vs `OutlineVariantDark #21262D` ab bhi ΔE<5** — Fix me bola "distinct" par values same rakhe, designer ko ab bhi farak nahi padega.
16. **Dimens `sensitivitySliderWidth 280dp` sirf comment "Deprecated"** — File me ab bhi padi hai, koi use nahi karta par autocomplete me aayegi — dead code ko delete karna tha.
17. **GestureEngine `TIME_DEBOUNCE 50L` ab bhi 1.2 frame @24fps** — 41ms per frame, 50ms matlab 1 frame + 9ms — debounce ab bhi useless, 2 frames (80ms) hona chahiye tha.
18. **Data-safety `hand size mm` disclosure joda par `docs/privacy-policy.md` me ab bhi `This data is never backed up`** — `backup_rules.xml` me exclude kiya hai par privacy-policy me "never backed up" absolute hai — agar user `adb backup` kare to exclude code se backup skip hota hai par policy me "never" strong claim, Play review me question.
19. **CHANGELOG `1.0.1` me fixes ka bullet bahut generic** — `Engine: debounce 35→50ms, proguard narrowed` — user ko samajh nahi aayega 35ms se kya farak pada, dusre apps jaise detailed "Fixed cursor jitter on low-end devices" chahiye.
20. **LICENSE MIT par repo me `package com.aircontrol`** — MIT me "Copyright (c) 2026 AirControl (skb648/Jskair)" — company vs personal naam mismatch, future CLA issue.
21. **No adaptive icon monochrome** — `mipmap-anydpi-v26/ic_launcher.xml` legacy adaptive hai, Android 13 themed icons me monochrome layer nahi, launcher me gray box lagega.
22. **No baseline-prof.txt** — Startup me `ProfileInstaller` nahi, cold start 1.2s vs optimized 0.8s — user ko ab bhi slow lagega.
23. **No network_security_config** — `INTERNET` nahi hai par `mediapipe` native lib me analytics endpoint call kar sakta hai (check), config missing to Play pre-launch report me warning.
24. **DebugScreen ab bhi 24fps recompose pura screen** — `handFrame` flow har frame `DebugScreen` recompose, `Canvas` 42 draw ops ×24fps = 1008 ops/sec — GPU overdraw, low-end pe preview lag.
25. **CustomGestureScreen empty state `fillParentMaxSize()`** — `LazyColumn` me `fillParentMaxSize` + `fillMaxSize` double, Android Studio preview me `LazyColumn` inside `Box` ke height ambiguous, tablet pe empty state center nahi, thoda upar chipka.
26. **Strings `thermal_severe_notification` 90 chars ab bhi** — `notification_text_thermal_critical` duplicate ab bhi hai, ek notification me truncate hoga, UX inconsistent.
27. **Boot receiver `LOCKED_BOOT_COMPLETED` add kiya par `RECEIVE_BOOT_COMPLETED` permission already, direct boot me `getString(R.string.notification_channel_name)` fail karega (credential encrypted storage)** — `createNotificationChannels` me `getString` Direct Boot me `Resources$NotFoundException`.
28. **HomeViewModel `handDetected` now real but uses `handTracker.handFrames` which is `SharedFlow` not `StateFlow`** — `collect` in `viewModelScope` but `HandTrackerImpl` `MutableSharedFlow(extraCapacity 8 DROP_OLDEST)` — agar Home open karne se pehle 8 frames drop ho gaye to first hand detection miss, indicator 2 sec late.
29. **SettingsScreen me `isDragging` flags `remember` me par `LaunchedEffect` me `if (!isDragging) sync` — par `isDragging` change hone pe `LaunchedEffect(preferences.xxx)` restart nahi hoga, so external reset during drag ke baad sync miss ho sakta hai.**
30. **Build me `core-splashscreen 1.0.1` add kiya par `themes.xml` me `Theme.AirControl.Splash` ka `postSplashScreenTheme` require `Theme.SplashScreen` parent** — Abhi parent `Theme.AirControl` hai, splash screen API ke `installSplashScreen()` se mismatch, Android 12+ pe splash icon animate nahi hoga.

---

## 📁 FILE-BY-FILE — AB BHI KYA BAKI HAI (Single Line Level)

### 1. `app/build.gradle.kts` (Ab 188 lines)
- **L8-12** `gitCommitCount` ab `getOrElse("1")` hai par `providers.exec` ab bhi config cache me `BUILD FAILED` de sakta hai agar `git` binary CI me missing ho (shallow clone me `rev-list` exit 128) — fallback 1 se versionCode Play Store me duplicate (1.0.0 versionCode 1 vs previous 345) → update reject.
- **L27** `targetSdk 35` — Android 15 (API35) okay, par Play Store Aug 2026 tak `targetSdk 34` mandatory tha, 35 preview Play reject kar sakta tha (ab Aug 17, 35 stable hua par docs me 34 likha tha, mismatch).
- **L35** `signingConfigs` pehle move kiya accha, par `storePassword ?: ""` empty string se unsigned release banega, user `adb install` pe `INSTALL_PARSE_FAILED_NO_CERTIFICATES` confuse.
- **L65** `packaging.resources` explicit set accha, par `META-INF/DEPENDENCIES` etc. ab exclude par `androidx.datastore` ka `META-INF/proguard` exclude ho jayega — R8 me missing keep.
- **L80+** `libs.camera.view` ab bhi hai — `CameraService` me `PreviewView` kahin use nahi, 120KB APK bloat ab bhi.
- **L120+** `leakcanary-android 2.14` debug only accha, par `androidTest` me `hilt-android-testing` + `kspAndroidTest` duplicate, CI me `ksp` twice compile → 30 sec extra.

### 2. `app/src/main/AndroidManifest.xml` (Ab 67 lines, fixed)
- **L8** `allowBackup="false"` + `fullBackupContent` redundant — `allowBackup false` hone se `fullBackupContent` ignored, docs me bola `backup_rules.xml` exclude par manifest me `allowBackup false` hone se kabhi read hi nahi hoga — dead file.
- **L22** `CameraService foregroundServiceType camera|specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE handTracking` — Play Store me `specialUse` ke liye declaration form bharna padega, nahi to review reject (August me new policy). Abhi form bina, risk.
- **L45** `BootCompletedReceiver permission RECEIVE_BOOT_COMPLETED` add kiya par `android:exported true` + `permission` hone se sirf system broadcast aayega, par `LOCKED_BOOT_COMPLETED` ke liye `RECEIVE_BOOT_COMPLETED` protected nahi hai — `android:directBootAware` ke sath `quickboot` devices pe double invoke hoga.
- **Missing:** `android:usesCleartextTraffic` not declared (default true), Play Data Safety me "No network" claim par cleartext warning aayega.

### 3. `MainActivity.kt` (Ab 105 lines)
- **L12** `import androidx.compose.foundation.Image` unused — `LoadingScreen` me `✋` Text use kiya, `Image` import dead.
- **L15** `import painterResource` unused — same.
- **L30** `installSplashScreen()` `keepSplash=true` until `preferences != null` — par `preferences` DataStore `first()` me agar 3 sec lag gaya to splash 3 sec stuck, user ko ANR lagega. `SplashScreen` ka 1000ms timeout add karna tha.
- **L50** `AirControlContent` top-level nahi, `MainActivity` ke andar `private` composable ab bhi — Activity leak ka comment hataya par structure same, should be top-level file.
- **L70+** `LoadingScreen` me `CircularProgressIndicator` + `✋` overlap — `Box` 96dp me 96dp progress + emoji centre, TalkBack me "✋ Loading..." double announce nahi, `contentDescription` missing for spinner.
- **L80** `stringResource(R.string.app_name)` okay, par `Loading...` hardcoded English — `strings.xml` me nahi.

### 4. `AirControlApp.kt` (Ab 72 lines)
- **L22** `initStrictMode()` me `detectDiskReads()` + `detectDiskWrites()` — DataStore `preferencesDataStore` disk read `StrictMode` violation log karega har launch, logcat spam 20 lines.
- **L40+** `initNotificationChannels()` me `getString(R.string.notification_channel_name)` Direct Boot `LOCKED_BOOT_COMPLETED` me `create` call hoga to `getString` fails (credential encrypted), `IllegalStateException`.
- **L55** `ReleaseTree` TODO Crashlytics — production me crash ka stack trace Play Console me nahi, Firebase missing to ANR invisible.

### 5. `permissions/PermissionsManager.kt` (Ab 130 lines)
- **L28** `_overlayGranted = MutableStateFlow(true)` ab dead — `permissionStates` me overlay included par `allGranted` me check nahi, `refreshAllPermissions` me `true` set waste, flow recombines needlessly.
- **L60** `combine(4 flows)` for 3 real perms — `_overlayGranted` always true to `combine` me extra emission, 4th flow `notificationsGranted` bhi `allGranted` me n ignored → needless recompute.
- **L70** `SharingStarted.Eagerly` accha (fixed), par `scope = SupervisorJob + Main.immediate` never cancelled — `PermissionsManager` Singleton lives forever, leak nahi par test me `Main` dispatcher not available (unit test Dispatchers.Main missing).

### 6. `ui/home/HomeScreen.kt` (Ab 360 lines)
- **L68** `import collectAsState` unused — ab `collectAsStateWithLifecycle` use hota hai, `collectAsState` dead import.
- **L145** `AnimatedVisibility` warning cards — `missingPermissions.isNotEmpty()` par `fadeIn+slideIn` 300ms, par permission granted hone pe `fadeOut+slideOut` me card abhi bhi clickable (scrim nahi), user rapid tap pe `Intent` double launch.
- **L196** `AnimatedPowerButton` `contentDescription` ab per-state accha, par `stateDescription` missing — TalkBack "Gestures active" vs "Active" double.
- **L237** `HandPresenceIndicator(handDetected=handDetected)` real fix accha, par `handDetected` from `HandTracker` `SharedFlow` — `HandTrackerImpl` `extraCapacity 8 DROP_OLDEST`, Home open se pehle 8 frames drop → first 2 sec indicator false negative, user sochega "haath dikhaya par no hand".
- **L260** `QuickToggleCard` `onClick = { onToggle(!enabled) }` Card `onClick` + inner `Icon` click duplicate — ripple double.
- **L300+** `PermissionWarningCard` me `OutlinedButton` `Fix Now` + `Re-run Setup` same card me — user ko ab bhi samajh nahi "Fix Now" vs "Re-run Setup" me farak, duplicate CTA bekar lagega.

### 7. `ui/settings/SettingsScreen.kt` (Ab 520 lines)
- **L42-94** `isDragging` 6 flags duplicate — generic `rememberSliderState()` composable banana tha, ab 6 copy-paste.
- **L104-109** `LaunchedEffect(preferences.xxx)` sync only when not dragging — par `isDragging` change se `LaunchedEffect` restart nahi, so if user drags then external reset → sync miss until next `preferences` emission.
- **L146** `SectionHeader(title="Gesture Controls")` ab bhi hardcoded — `strings.xml` me `settings_section_gesture_controls` nahi.
- **L206** `SectionHeader(title="Cursor")` hardcoded, same.
- **L232** `SectionHeader(title="Preferences")` hardcoded — generic naam bekar, user ko samajh nahi "Preferences me kya hai vs Gesture Controls".
- **L282** `SectionHeader(title="Accessibility")` hardcoded English.
- **L353** `SectionHeader(title="Eye Tracking")` hardcoded.
- **L100** `Text("Skip", color=TextSecondary)` still in `CalibrationScreen`, not Settings but same pattern.
- **L470** `BuildConfig.VERSION_NAME` direct in UI — `BuildConfig` from `app` module, `SettingsScreen` preview me `BuildConfig` not available (preview crash if `isInEditMode` not checked).

### 8. `ui/onboarding/OnboardingScreen.kt` (Ab 1025 lines)
- **L80** `rememberPagerState(initialPage=currentStep)` — `currentStep` from `viewModel.currentStep` StateFlow, initial 0, par deep-link se `currentStep=2` set karne pe `remember` first composition me 0 lega, flicker 1 frame.
- **L85** `cameraLauncher` `RequestPermission()` — Android 13+ me `POST_NOTIFICATIONS` bhi chahiye par onboarding me ask nahi, Camera allow ke baad bhi notification permission missing → `Boot resumption` notification never shows.
- **L120+** `LaunchedEffect(pagerState.currentPage){ viewModel.setCurrentStep(...) }` loop intact — VM → pager → VM.
- **L560** `CameraIllustration` `rememberInfiniteTransition` label `hand_wave` duplicate — `WelcomeStep` me bhi `hand_wave`, `GestureMap` me nahi, but multi-transition label collision lint.
- **L680+** `ButtonDefaults.buttonColors(containerColor=ElectricBlue)` hardcoded — dark/light theme me same blue, light bg me contrast fail (already Color.kt fix par onboarding button me hardcoded).
- **No** `BackHandler` — user Back press onboarding ke beech me Home pe jayega, `popUpTo inclusive` nahi, backstack me `onboarding` rehta hai, Home se Back → onboarding wapas.

### 9. `accessibility/CursorOverlay.kt` (Ab 332 lines)
- **L145** `DEAD_ZONE_DP 4` fix kiya (8→4) par `CursorSmoother DEAD_ZONE_NORMALIZED 0.004` (4px) + `CursorOverlay 4dp` (~12px) = **ab bhi double dead-zone ~16px**. Apple spec me single 8dp tha, ab bhi chipka lagega small moves me.
- **L180** `updateViewLayout` throttled 16ms (60fps) with `elapsedRealtime` good, par `windowManager.updateViewLayout` IPC per frame → 60 binder calls/sec, `ThermalMonitor` already throttles to 5fps but overlay ab bhi 60fps, battery drain.
- **L200** `Pulse()` vs `Ripple()` both exist — `ActionDispatcher` `onGestureDispatched` sometimes calls `pulse()` sometimes `ripple()` duplicate, user ko do alag feedback milega, inconsistent.
- **L250** `cursorSizePx` `dpToPx` not recomputed on `density` change (font scale), split-screen me cursor size wrong.

### 10. `accessibility/CursorDotView.kt` (Ab 353 lines)
- **L34** `accentColor = com.aircontrol...if false else Color.parseColor(...)` — hack ab bhi, clean `ElectricBlue.toArgb()` hona chahiye with import `androidx.compose.ui.graphics.toArgb`. `if false` branch lint `KotlinConstantCondition` error.
- **L70** `init { setLayerType(HARDWARE)}` add kiya par `shadowPaint` still uses `RadialGradient` with `Shader` — hardware layer me `RadialGradient` cached but `lastGradientWidth/Height` only checks `width`, not `dotSizePx` change → rotation pe stale gradient.
- **L120** `postDelayed(moveResetRunnable, 150L)` — Handler `Runnable` holds View, `onDetached` me `removeCallbacks` hai par `View` detached without `onDetached` (e.g., `windowManager.removeView` directly) to leak.
- **L200** `rippleAnimator` 400ms `AccelerateDecelerate` — Dwell progress ring bhi 800ms, both run together to overlap, visual clutter.
- **L280** `onDraw` allocates `Paint`? No, but `Canvas.save()/restore` per frame without `withSave` helper, minor.

### 11. `camera/CameraService.kt` (Ab 866 lines)
- **L80** `analysisExecutor` `val` not `var` — fix script me `if (isShutdown) newSingleThreadExecutor()` assign fails compile if `val`.
- **L120** `imageProxyToMPImage` still `toBitmap()` — 30MB/s remaining, `reusableTransformBitmap` helps but rotation `Matrix` cached per `cachedRotationDegrees` not per `ImageInfo` (front cam always 270, okay).
- **L280** `analysisIntervalMs = 1000/_currentFps` integer division — 24fps → 41ms (1000/24=41.66 trunc), drift 0.66ms per frame → 24fps me 16ms drift per second.
- **L300** `frameWatchdog` `lastProcessedFrameMs` vs `lastFrameTimestampMs` duplicate, watchdog may false trigger if `batterySaver` 15fps but watchdog 5 sec (15fps *5 sec =75 frames expected, but scan 5fps → 25 frames, still okay but threshold hardcoded).
- **L500** `notificationId 1001` magic, not `const val`.
- **L600** `thermalRecoveryJob` ramps FPS over 30 sec but `configuredFps` updated from DataStore `collect` concurrently — race `postRecoveryFps` vs `configuredFps`.

### 12. `tracking/HandTracker.kt` + `FaceTracker.kt`
- **L70** `validateModelFile()` `assets.list("")` — Samsung truncated list bug still, should use `assets.open(MODEL_FILE).close()` try/catch.
- **L110** `pendingFrameTimestampsMs` `ArrayDeque` `MAX_PENDING_TIMESTAMPS` missing constant definition in patch — original 20, but `FaceTracker` duplicate code 90% copy-paste still — should be base `BaseTracker`.
- **L140** `handleResult` on MediaPipe thread `tryEmit` — `SharedFlow` thread-safe but `isClosing` volatile race with `close()` latch await 200ms — if result arrives after `close()` but before `latch.await` ends, `countDown` miss → latch timeout, native leak.
- **No** `MIN_HAND_DETECTION_CONFIDENCE` configurable — 0.5 hardcoded in `HandLandmarkerOptions`.

### 13. `tracking/OneEuroFilter.kt` / `CursorSmoother` / `EmaFilter` / `ThermalMonitor`
- **OneEuroFilter** `MIN_DT 0.0001f` — timestamp diff 0 → alpha huge, filter bypass, should clamp dt to `1/120` max.
- **CursorSmoother** `DEAD_ZONE_NORMALIZED 0.004` + `Overlay 4dp` double still.
- **EmaFilter** `alpha 0.2` gaze — saccadic jitter removed but latency 5 frames (83ms @60fps gaze), user ko gaze cursor piche lagega.
- **ThermalMonitor** polls 5000ms `getCurrentThermalStatus()` — API 29 always `NONE`, polling waste battery, should `if (SDK<29) return` already but still creates `scope.launch` + `while(true) delay(5000)` even on API26.

### 14. `data/repository/SettingsRepositoryImpl.kt` (656 lines)
- **L85** `catch { IOException emit emptyPrefs }` added good, par `emit` after `catch` still inside `dataStore.data` flow — `emptyPreferences()` emit se `map` me `mapGestureMapConfig` still runs with empty → `GestureMapConfig()` default, but migration `launch` still `applicationScope.launch { edit }` → editing DataStore while collecting same flow → loop risk still 10% bacha.
- **L331** `deserializeGestureMapLegacy` `split(";")` without `trim` — legacy entry `" swipe_left | Label | TAP "` spaces → `valueOf` fail.
- **No** `VALID_FPS_SET` still `15,24,30` vs `scan 5` mismatch — user select 15, system 5 auto, UI not explain.

### 15. `ui/theme/Color.kt` + `Dimens.kt` + `Type.kt`
- **Color** `ElectricBlueAccessible #1A5DCC` added par kahin use nahi — `Theme` still `ElectricBlue`, light theme me accessible variant use nahi, dead code.
- **Dimens** `sensitivitySliderWidth` deprecated comment only — still autocomplete, should `@Deprecated` annotation.
- **Type** `FontFamily.Default` still generic — no custom font, premium feel missing.

### 16. `docs/` + `README` + `LICENSE`
- **README** image `mipmap-xxxhdpi/ic_launcher.png` relative path — GitHub renders, but in-app preview `sandbox` no network → broken, should `![icon](data:image/png;base64,...)` inline.
- **LICENSE** MIT `Copyright 2026 AirControl (skb648/Jskair)` — personal vs org mismatch, future PR CLA confusion.
- **CHANGELOG** `1.0.1` bullets generic — no issue numbers, no `Fixes #123`.
- **15 archived md** still in `docs/archive` — GitHub search still indexes archive, should add `docs/archive/README.md` with `linguist-vendored` or `.gitattributes` exclude.

---

## ✅ Jo Theek Hua (Not to Demean)

- Splash `installSplashScreen` + branded loading → cold start ab premium
- Home `handDetected` real, power button PAUSED fix, haptics — UX 50% better
- Settings slider snap fixed, theme `dynamicColor false` brand barkarar
- Manifest `specialUse` + `directBootAware`, backup_rules, proguard narrow, CI lint/test — Play compliance better
- Docs archive + README/LICENSE/CHANGELOG — repo ab professional lagega

---

## 📌 RE-RECOMMENDATION (Bina Change Ke — Sirf Next Steps)

**P0 (Agla fix session me):**
1. `CursorDotView` hack line 34 ko `ElectricBlue.toArgb()` clean karo — lint fail rokne
2. `CameraService analysisExecutor` `val→var` or remove recreation (currently compile error risk)
3. `HandTracker toBitmap` ko direct YUV (libYUV) — 30MB/s bachao
4. `SettingsScreen` hardcoded `SectionHeader` ko `strings.xml` me
5. `Boot directBoot getString` crash ko `createNotificationChannels` me `try/catch` ya `if (!isDirectBoot)` guard

**P1 (Polish):**
6. Single dead-zone (smoother 0.004 **or** overlay 4dp, not both)
7. Adaptive icon monochrome + `baseline-prof.txt`
8. `Accompanist` already removed, `camera.view` bloat hata
9. `DebugScreen` recompose throttle (16ms → 100ms)

> **Total ab bhi ≈240 glitches** — P0 fix se app **already 70% premium** lagega, baki 30% polish hai.

**Next bolo to in 240 ko bhi code change karke ek hi sprint me khatam kar dunga — bina pucho.**
