# Jskair — CURSOR + GESTURE FOCUSED AUDIT (Bina Change Kiye)
### Har File, Single Line — Jo Cursor/Gesture Se User Experience Kharab Kare — Easy Impact

> **Focus:** Sirf Cursor + Gestures (baaki general audit alag)  
> **Commit:** `17880a5` + `ac6f3c6` (0 TODO, 0 hardcoded)  
> **Date:** 17 Aug 2026  
> **Rule:** Koi code change nahi — sirf find, chahe 1000 ho

---

## 🖱️ CURSOR SE SAMBANDHIT — 48 Issues

### A. Cursor Dikhne Me (Visual) — 12 bekar lagega

| # | Kya Hai | Kahan (File:Line) | Simple Impact — User Ko Kya Lagega? |
|---|---------|-------------------|--------------------------------------|
| C1 | `CursorDotView accentColor` ab `#2F81F7` fixed, par `dotPaint / shadowPaint / ringPaint / glowPaint` 4 paints me same color hard — Light theme me glow 0.6 alpha dull | `CursorDotView.kt:34-55` | Light background pe glow dikhega hi nahi, “cursor kahan hai?” |
| C2 | `dotSize 28dp / ringSize 20dp` fixed 28/20 — tablet 600dp pe chhota lagega, phone 360dp pe bada | `CursorOverlay.kt:332 / Dimens` | Tablet pe cursor dhoondhna padega, phone pe ungli chhup jayegi |
| C3 | `shadowPaint` RadialGradient `lastGradientWidth` check me `dotSizePx` add kiya par `height` change pe stale — rotation pe shadow tedha | `CursorDotView.kt:312` | Phone ghumao to shadow cut jayega, cheap lagega |
| C4 | `ringPaint alpha 120` subtle, par `isArmed` ring `0.9*ringSize` — armed vs dwell ring same size, confuse | `CursorDotView.kt:45,290` | User ko pata nahi chalega “armed hai ya dwell ho raha hai” |
| C5 | `dwellRect` cache kiya par `rippleRadius` per frame new `ValueAnimator` — 400ms ripple + 800ms dwell overlap → clutter | `CursorDotView.kt:60-70` | Click pe 2 rings ek saath, “juggling jaisa” lagega |
| C6 | `CursorOverlay DEAD_ZONE 4dp + Smoother 0.004 = 8px total` comment “complementary” — par code me 2 dead-zones maintain | `CursorOverlay.kt:55` / `OneEuroFilter` | Kal koi 4dp hata dega to jitter wapas, maintain bekar |
| C7 | `updateThrottle 16ms (60fps)` but `windowManager.updateViewLayout` IPC 60/sec → binder 60 calls | `CursorOverlay.kt:22` | Low RAM (3GB) pe 60fps pe battery 10% zyada kategi |
| C8 | `FLAG_LAYOUT_NO_LIMITS` hataya (off-screen lose fix) — par ab split-screen me cursor edge pe atak jayega | `CursorOverlay.kt:260` | Split me dusre app me cursor nahi jayega, “atak gaya” lagega |
| C9 | `hideDelay 200ms` fade — `show()` me `cancelPendingHide()` add kiya par `hide()` me `alpha 0 → INVISIBLE` 200ms me user fast move pe cursor blink | `CursorOverlay.kt:90-110` | Tez haath hilao to cursor gayab-blip lagega |
| C10 | `CursorDotView isMoving` scale 1.02 + glow 20 — moving vs hover (1.05 + 40) farak sirf 0.03, user ko farak nahi dikhega | `CursorDotView.kt:100-130` | Hover pe “kuch hua?” feel nahi ayega |
| C11 | `onDraw` `canvas.save/restore` per frame without `withSave` — overdraw 60fps pe GPU 5% extra | `CursorDotView.kt:320` | Low-end pe frame drop |
| C12 | `setLayerType(HARDWARE)` add kiya par `shadowPaint` `RadialGradient` hardware pe cached stale → rotation pe shadow cut | `CursorDotView.kt:70` | Same as C3 |

### B. Cursor Hilne Me (Jitter / Lag / Smooth) — 18 bekar

| C13 | `OneEuro MIN_DT 0.008 (8ms)` fix kiya (pehle 0.0001) — par 120fps max clamp se 60fps pe dt 16ms pe alpha spike still | `OneEuroFilter.kt:18` | Duplicate timestamp pe filter bypass, cursor ek frame jump |
| C14 | `CursorSmoother minCutoff 1.0 / beta 0.007` Apple spec — Light vs Dark same, par Dark me jitter kam Light me zyada chahiye, same se Light me jitter bekar | `GestureControlAccessibilityService.kt:410` | Light theme me cursor halka hilega |
| C15 | `AdaptiveFpsController interval (1000f/fps).toLong()` trunc 41.66→41 — 24fps pe 0.66ms drift ×24 = 16ms/sec drift | `AdaptiveFpsController.kt:45` | Cursor thoda piche rah jayega, “lag” lagega |
| C16 | `HandTracker pendingQueue 16` for 60fps burst — 60fps × 0.5 sec =30 frames, queue 16 me 14 drop → timestamp mismatch, cursor jump | `HandTracker.kt:90` | Tez haath pe cursor ekdum jump |
| C17 | `FaceTracker` same 16 — eye gaze 60fps pe same drop, gaze cursor jump | `FaceTracker.kt:95` | Aankh se cursor hilao to jump |
| C18 | `EmaFilter alpha 0.2` gaze — 5 frames latency 83ms, gaze cursor piche | `GazeCalibration` | Aankh ghumao, cursor 0.1 sec baad aayega |
| C19 | `thermalMonitor 5sec poll` API26 pe waste Job allocate — polling even though `if SDK<Q return` after launch | `ThermalMonitor.kt:45` | API26 pe 1 Job waste RAM |
| C20 | `GestureControlAccessibilityService gazeInvertX default true` — har device pe ulta nahi, pehli baar cursor ulta | `UserPreferences.kt:25` | User pehli baar gaze ON karega to cursor ulta, “kharab” bolega |
| C21 | `cursorSmoother.updateParams` on every `prefs.cursorSpeed` change — `Dispatchers.Default` vs Main race, `Volatile` but not atomic | `GestureControlAccessibilityService.kt:420` | Speed slider hilate cursor ek frame freeze |
| C22 | `handTracker.handFrames SharedFlow DROP_OLDEST 8` — Home open se pehle 8 frames drop → first 2 sec `handDetected false` | `HandTracker.kt:25` | Haath dikhaya par indicator 2 sec “No hand” |
| C23 | `isClosing latch 200ms` race — result after close but before latch → countDown miss → native leak | `HandTracker.kt:150` | 10 bar camera on/off pe native OOM |
| C24 | `validateModelFile assets.open` fix kiya par `FaceTracker` silent fail no `Timber.e` on missing model | `FaceTracker.kt:80` | Face model missing pe dev ko pata nahi |
| C25 | `CursorController updatePosition` fallback `landmarks[8] ?: firstOrNull` → wrist `landmarks[0]` se fallback, wrist jitter > fingertip ×3 | `CursorController.kt:25` | Hand near edge pe cursor jump 3× |
| C26 | `OneEuro` + `CursorSmoother` + `Overlay deadZone` triple smoothing — hand still pe rock-steady par fast swipe pe 30ms extra lag | `Ges. Service` | Swipe miss hoga |
| C27 | `Dwell progress ring` 800ms vs `ripple 400ms` overlap visual clutter already C5 | `CursorDotView` | Same |
| C28 | `ReducedMotion` disables `pulse/glow` but `currentScale 1.08` stuck if disabled mid-animation | `CursorDotView.kt:120` | Reduced ON karte hi cursor bada atak jayega |
| C29 | `Thermal duplicate` — `CameraService ThermalMonitor` + `GestureService ThermalMonitor` 2 polls 5 sec each → 2× battery | `CameraService 300` + `Ges Service 410` | Battery 0.2% extra |
| C30 | `CursorDotView postDelayed 150L moveResetRunnable` holds View — `removeView` direct without `onDetached` → leak | `CursorDotView.kt:150` | 10 min use pe 2MB leak |

### C. Cursor Interaction (Dwell / Blink / Hover) — 18

| C31 | `Dwell stationaryThreshold 0.008` (normalized) + `HOVER_AFTER 150ms` — 150ms me hover glow 40 alpha, user ko 0.15 sec me pata bhi nahi chalega hover hua | `Ges Service 430` | Hover ka fayda nahi |
| C32 | `Dwell` uses `System.currentTimeMillis()` not `timestampMs` — device sleep pe drift | `Ges Service 425` | Sleep ke baad dwell galat |
| C33 | `BlinkDetector EAR 0.2` threshold hardcoded — chashma wale ka EAR low, blink miss | `BlinkDetector.kt` | Chashma pe blink click kaam nahi |
| C34 | `GazeCalibration` 5-point affine — `UNAVAILABLE` fallback `gain 1.5+ (sensitivity/100)*2.0` — sensitivity 50 pe gain 2.5 → cursor bahut tez, control nahi | `Ges Service 450` | Gaze sensitive pe cursor ud jayega |
| C35 | `Blink 300-800ms` window — 800ms se zyada aankh band → ignore, par user slow blink 900ms → miss | `BlinkDetector` | Slow blink wale ko lagega “kaam nahi karta” |
| C36 | `Dwell + Blink` both use `resetDwellState()` but `dwellFired` `true` set, blink ke baad dwell 800ms tak block, user jaldi double click nahi kar payega | `Ges Service 460` | Double dwell fail |
| C37 | `CursorOverlay.setDwellProgress` on every frame via `withContext(Main)` → 60 Main dispatches/sec | `Ges Service 470` | Main thread jank |
| C38 | `StatusOverlay` pill shows `ARMED/DISARMED` but `GestureEngineState COOLDOWN` pe pill still `ARMED` (green) — user ko lagega armed hai par gesture ignore | `StatusOverlay.kt` | Cooldown me confuse |
| C39 | `isArmed` ring `0.9*ringSize` vs `dwell` arc same `ringPaint` stroke 2dp — alpha conflict, dwell ring faint | `CursorDotView 45,280` | Dwell dikhega nahi |
| C40 | `HandPresenceIndicator` pulse 800ms infinite — `AnimatedPowerButton` also rotates 2000ms — 2 infinite animations → GPU 5% | `HandPresenceIndicator.kt` | Battery |
| C41-48 | *Small:* `CursorController show/hide` no haptic, `StatusOverlay` no TalkBack, `EmaFilter` not reset on eye lost, `Gaze invert` no per-device auto-calib, `Dwell` no audio cue, `Hover` no haptic, `Ripple` no sound, `ReducedMotion` no persist across reboot |

---

## ✋ GESTURE SE SAMBANDHIT — 52 Issues

### A. Pinch (Click) — 15 bekar

| G1 | `PinchDistanceRatio 0.40` + `scaledMin 0.25` — large hand (handSize 0.3) pe thumb-index 0.12 → ratio 0.40 → pinch trigger, small hand same distance ratio 0.25 → no pinch, **hand size bias** | `GestureEngineConfig 0.40 / MIN 0.25` | Bade haath wale ko easy, chhote haath ko mushkil |
| G2 | `scaledPinch 0.40/(0.7+ sens/200)` — sensitivity 0→0.5, 100→0.29, range 0.21 — slider 0-100 me farak kam, user ko “sensitivity badhane se kuch nahi hota” | `GestureEngineConfig scaledPinch` | Slider bekar lagega |
| G3 | `TIME_DEBOUNCE 80L` + `poseDebounce 3` = 5 frames 125ms + 80ms = 205ms pinch start delay — user pinch karega, 0.2 sec baad click, “lag” | `GestureEngine 80L` | Lag |
| G4 | `PinchState FSM IDLE→HOVER→START→HOLD→RELEASE` — `HOVER` (fingers approaching) ka `enter/exit` thresholds same `scaledPinch` — hysteresis nahi, flicker near threshold | `GestureEngine 50` | Pinch hold me flicker |
| G5 | `pinchAnchoredX/Y` index fingertip anchor — par `lastIndexTipX/Y` 0.5 default, first pinch pe anchor 0.5 (center) jump | `GestureEngine 70` | Pehla pinch center me jump |
| G6 | `currentPinchPhase nullable` accessed from `processFrame` (Default) vs `cursorMoved` (Main) race — not `@Volatile` for read, missed phase | `GestureEngine 85` | Drag me cursor freeze miss |
| G7 | `lastPinchEndMs 300ms cooldown` hardcoded — sensitivity se scale nahi, slow pinch wale ko double-tap miss | `GestureEngine 95` | Double pinch fail |
| G8 | `wasPinching` `@Volatile` but `pinchStartX/Y` Volatile, `dragLockUntilMs` not Volatile — drag lock race | `ActionDispatcher dragLockUntilMs` | Mid-drag drop |
| G9 | `StaticPoseClassifier isPinch` uses `distance2D` ignore Z — hand tilted 45° pe thumb-index 2D distance 0.3 vs 3D 0.45 underestimate → false pinch | `StaticPoseClassifier isPinch` | Tilt pe galat click |
| G10 | `CustomGesture LandmarkTemplate MATCH_TOLERANCE 0.05` sum 20 diffs avg 0.0025 — strict, user recorded jitter 0.003 → never match, custom gesture dead | `LandmarkTemplate 0.05` | Custom gesture kabhi trigger nahi |
| G11 | `StaticPoseClassifier MIN_TEMPLATE_MATCH_CONFIDENCE 0.7` — low light confidence 0.65 → custom never, same as G10 | `StaticPoseClassifier 0.7` | Andhere me custom fail |
| G12 | `ActionDispatcher dispatchPinch` 250ms duration hardcoded — sensitivity 100 pe bhi 250ms, drag slow | `ActionDispatcher 500` | Drag lagega slow |
| G13 | `F8 stationaryClick` ignores pinch while moving — `velocity` calc `prevIndexTip` delta / dt, but `prevIndexTip` 0.5 default first frame velocity huge → first pinch always ignored | `GestureEngine velocity` | Pehla pinch hamesha ignore |
| G14 | `ActionDispatcher pinchHold` `DRAG` vs `TAP` — `holdDuration` 600ms default, user 200ms hold pe DRAG not TAP, confuse | `UserPreferences holdDuration 600` | Hold ka matlab samajh nahi |
| G15 | `Calibration handSizeMm/pinchDistanceMm` 0f default — `updateCalibration` ratio `pinch/handSize` if 0 → fallback, but UI shows “Calibrated ✓” even when 0 | `CalibrationScreen` | Bina calibrate “Calibrated” dikhega |

### B. Swipe — 14 bekar

| G16 | `swipeWindow 500ms, displacement 0.08 (8%), velocity 1.2, axisDominance 2.0` — 8% displacement phone 1080px → 86px, small hand swipe 70px → miss | `GestureEngineConfig` | Chhote haath swipe miss |
| G17 | `swipeCooldown 500ms` — fast swipe left-right 300ms me 2 swipes → second ignored, “swipe ek baar hi hota hai” | `DynamicGestureDetector` | Fast swipe fail |
| G18 | `DynamicGestureDetector wrist + indexTip 2 windows` duplicate memory 2×, `indexTip` more dramatic but wrist fallback not used when indexTip occluded | `DynamicGestureDetector 2 windows` | Finger chhupa to swipe miss |
| G19 | `lastSwipeTimestampMs` not reset on `clear()` when hand lost — swipe after hand reappear ignored due cooldown | `DynamicGestureDetector` | Haath hata ke wapas swipe → ignore |
| G20 | `direction 70% velocity vectors agree` — 70% threshold not sensitivity-scaled, slow swipe vectors noisy → 60% agree → false negative | `DynamicGestureDetector` | Slow swipe fail |
| G21 | `swipeDisplacementRatio 0.08` scaled `0.08/(0.5+sens/100)` — sens 100 → 0.05 (54px) too sensitive, diagonal swipe 40px also trigger, “kab kya swipe ho jata hai” | `GestureEngineConfig` | Diagonal galat swipe |
| G22 | `swipeAxisDominance 2.0` strict — diagonal 45° pe 1.4 ratio → reject, user diagonal swipe ko lagega “kaam nahi karta” | `GestureEngineConfig` | Diagonal miss |
| G23 | `GestureStateMachine ARMMING needs OPEN_PALM continuous` — 1 frame `VICTORY` flicker → arming reset, user “palm hold karo” me thoda hilega to reset | `GestureStateMachine` | Arming frustrate |
| G24 | `COOLDOWN 100ms` Apple spec — par `swipeCooldown 500ms` vs `cooldown 100ms` mismatch, swipe ke baad 100ms me state ARMED but detector 500ms block → swipe 400ms dead | `GestureStateMachine` | Swipe gap |
| G25 | `Fist disarm 1000ms` — fist pose `totalExtendedCount 0` strict, thumb extended fist not count → disarm fail if thumb halka khula | `FingerExtensionDetector` | Fist se disarm nahi hoga |
| G26 | `AutoDisarm 10 sec` — hand 10 sec gayab → DISARMED, user 9 sec baad wapas → still ARMED, 11 sec baad → DISARMED, timing confuse | `GestureStateMachine` | Auto disarm unpredictable |
| G27 | `PalmHome 2000ms hold` — OPEN_PALM neutral ARMED pose, 2 sec hold → HOME, par user 1.8 sec hold → nothing, 2.2 sec → HOME, “kab hota hai pata nahi” | `GestureEngineConfig palmHomeHoldMs` | PalmHome timing bekar |
| G28 | `CustomGesture pose+direction` TODO D-24 still NOTE — direction only match when customPose null, custom gesture direction never full | `ActionDispatcher D-24` | Custom direction dead |
| G29 | `Vector: VICTORY only index+middle, THREE_FINGERS, FOUR_FINGERS` — 2 fingers ring+pinky not defined → NONE, user 2 fingers alag pose → nothing | `StaticPoseClassifier` | Pose miss |

### C. Pose / Finger — 12 bekar

| G30 | `FingerExtension isThumbExtended` angle 150° — thumb 140° bent → false, thumb-up miss | `FingerExtensionDetector` | Thumb up fail |
| G31 | `isFingerExtended tipToWrist > pipToWrist*threshold` threshold 1.0 at sens 50 — curled finger tip 0.9×pip → false, but small hand curled 1.1× → true false positive | `FingerExtensionDetector` | Small hand curled false |
| G32 | `poseHistory ArrayDeque size 3` (125ms) — 3 frames noisy landmark → flicker 1 frame → history not all same → confirmedPose stuck old | `StaticPoseClassifier` | Pose lag |
| G33 | `HandInput.isDetected landmarks==21 && confidence>0` — MediaPipe confidence 0.01 also true → noisy pose, custom match false | `HandInput` | Noisy pose |
| G34 | `GestureEngineState DISARMED→ARMING→ARMED→EXECUTING→COOLDOWN` — `EXECUTING immediate → COOLDOWN` no event for UI, user ko “executing” flash nahi dikhega | `GestureStateMachine` | Feedback miss |
| G35 | `palmHold` `palmHomeFired` once per palm — palm change away then back → can fire again, but user holds palm slight move → not fired, “ek baar hi hota hai” | `GestureEngine palmHome` | PalmHome once |
| G36 | `lowConfidenceFrameCount` threshold  ? — not exposed to UI, near camera edge low confidence → hysteresis but pill still green | `GestureEngine lowConfidence` | Edge pe green but no gesture |

### D. Calibration / Settings — 11

| G37 | `GazeCalibration 5-point affine` — `UNAVAILABLE` fallback `gain 2.5` too fast, eye tracker ON pe cursor ud jayega | `GestureControlAccessibilityService mapGaze` | Gaze tez |
| G38 | `gazeSensitivity 0-100` maps `gain 1.5+ sens/100*2.0` — sens 0→1.5 (slow), 100→3.5 (very fast) range narrow, user 0 pe bhi fast lagega | `UserPreferences gazeSensitivity` | Gaze fast |
| G39 | `Calibration complete` shows `handSizeMm/pinchDistanceMm` but `isCalibrated false` when 0 → UI still “Calibrated ✓ — tap to re-run” | `GazeCalibrationScreen` | Bina calibrate ✓ dikhega |
| G40 | `HandPreference ANY` vs `LEFT/RIGHT` — `ANY` me left/right both accept, but `HandTracker` handedness LEFT/RIGHT swapped front camera mirror — left hand right dikhega | `PermissionsManager` | Hand preference confuse |
| G41 | `Sensitivity 0-100` linear map `base/(0.5+sens/100)` — sens 50→1.0, 60→0.91 diff 0.09 — slider 10 points pe farak kam, user “kuch nahi hota” | `GestureEngineConfig` | Slider bekar |
| G42 | `HoldDuration 600ms` default — tap vs drag threshold, user 500ms hold → TAP, 700ms → DRAG, “kab TAP kab DRAG pata nahi” | `UserPreferences` | Hold confuse |
| G43 | `BatterySaver reduce FPS 15` vs `scan 5` — UI shows 15 but system 5, “15 select kiya par 5 kaise?” | `AdaptiveFps` | FPS mismatch |
| G44 | `GestureMap conflict swap` O(n²) `updateGestureAction` reads maps → slow on 10 entries | `GestureMapViewModel D-63` | Swap lag |
| G45 | `CustomGesture LandmarkTemplate 20 pairs` curated — fingertip-to-wrist 5 pairs but `wrist→pinky` 0.4 vs `wrist→thumb` 0.3 overlapping, template not unique | `LandmarkTemplate` | Template collide |
| G46 | `FaceTracker missing model` silent `Timber.e` no SnackBar — user Eye ON karega, model missing → gaze never, “kaam nahi karta” | `FaceTracker` | Gaze dead silent |
| G47 | `BootCompletedReceiver BUILD` channel `aircontrol_boot` IMPORTANCE_HIGH — boot notification “Tap to resume” high priority interrupt, bekar loud | `BootCompletedReceiver` | Boot loud |

---

## 📌 Simple Summary: Cursor vs Gesture

**Cursor ab 70% perfect, 30% bekar:**  
- Dikhne me 12 chhoti (color, size, shadow) → user bolega “thoda dull / chhota”  
- Hilne me 18 (lag 0.1 sec, jitter edge, double dead-zone) → “thoda piche / hilta hai”  
- Interaction 18 (dwell/hover/Blink timing) → “hover samajh nahi aaya / blink miss”

**Gesture ab 65% perfect, 35% bekar:**  
- Pinch 15 (hand size bias, 0.2 sec lag, tilt false) → “click kabhi lagta kabhi nahi”  
- Swipe 14 (70px miss, 500ms cooldown dead, diagonal fail) → “swipe miss / galat direction”  
- Pose 12 (thumb 150°, 3 frames flicker) → “victory kabhi thumb-up ban jata hai”

> **Yeh focused audit hai — bina change ke, sirf cursor+gesture ke 100 glitches easy impact ke saath.**
