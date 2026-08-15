# Jskair Development Worklog

## Current Project Status
- Android/Kotlin application named Jskair (Gradle root project: AirControl) with two modules: `:app` and `:gesture-engine`.
- UI stack uses Jetpack Compose + Material 3. App module uses Hilt, CameraX, MediaPipe, DataStore, Navigation Compose, Coroutines, Timber, and Compose UI tests.
- Target/compile SDK is 36; Java/Kotlin target is 17; Gradle wrapper is 8.11.1.
- Android instrumentation tests are present for accessibility, DataStore migration, permissions, service lifecycle, gesture remapping, onboarding, and settings persistence.
- Main functional architecture centers on accessibility gesture control, camera/hand tracking, gesture engine FSM, cursor overlay/controller, settings persistence, and background services.
- Latest repository commit observed: `8500af6` (2026-08-05), documentation/test-report update. GitHub commit status currently reports no status entries, so repository-level status cannot be treated as proof of CI success.
- Existing static test report dated 2026-08-05 claimed 10 cursor/gesture bugs; source inspection shows those fixes are already present in the current main branch.
- Runtime APK/device testing has not been performed in this connector session; do not claim it has.

## Current Goals / Completed Modifications / Verification
### Completed in current phase
- Audited repository structure and Android configuration.
- Verified the project is Android/Kotlin and uses Compose rather than a web stack.
- Verified the previously reported cursor animator stacking fix exists: `CursorDotView` tracks and cancels scale/glow animators.
- Verified smoother defaults in `GestureControlAccessibilityService` are aligned to 1.0 / 0.007.
- Verified low-confidence cursor smoothing hint is now 2.0f.
- Verified pinch cooldown is checked before entering `PINCH_START` and redundant pinch-center calculation was removed.
- Verified cursor overlay pulse uses internal `CursorDotView` scaling rather than View-level scaling.
- Verified hover reset and `IDLE_TIMEOUT_MS` usage fixes exist.
- Identified a current QA/CI gap: `.github/workflows/build-apk.yml` only runs `assembleDebug`; it does not run unit tests or lint, despite those test surfaces existing in the repository.
- Identified that manual/device runtime verification remains outstanding in this connector environment.

### Verification performed
- Inspected `app/build.gradle.kts`, `settings.gradle.kts`, Gradle wrapper configuration, manifest, accessibility-service XML, CI workflow, and representative Android source/tests.
- Reviewed recent commit history and confirmed the 10-bug fix commit exists before the latest documentation commit.
- Reviewed current source to ensure the older static report's listed bugs are not simply still present.

## Unresolved Issues / Risks / Next Priorities
1. **P1 — CI QA hardening:** Make GitHub Actions execute unit tests and lint in addition to the debug APK build. The current workflow proves compilation but not test/lint health.
2. **P1 — Runtime QA:** Build/install/launch the APK on an emulator or physical Android device when such an environment is available; exercise onboarding, permissions, accessibility service, camera startup, cursor movement, pinch/drag, settings persistence, rotation, and recovery flows.
3. **P1 — Instrumentation reliability:** Confirm Android tests can actually run in CI; instrumentation requires a device/emulator and may need a managed test action or separate job.
4. **P2 — Cursor event wiring:** Current service explicitly wires gesture dispatch to `cursorOverlay?.pulse()`. `notifyTap()` / `notifyHover()` are exposed, but their real production triggering path should be verified to ensure interaction feedback matches intent.
5. **P2 — Stale comments:** `GestureControlAccessibilityService` still contains older cursor smoother comments mentioning 0.7/0.08 while the actual constants are 1.0/0.007. Documentation drift should be cleaned up to reduce maintenance risk.
6. **P2 — CI/release verification:** Release signing is environment-dependent; do not claim a signed release APK until the relevant keystore/CI secrets are verified.
7. **P3 — Broader automated regression coverage:** Add focused tests around cursor overlay animation lifecycle, pinch hysteresis/cooldown timing, low-confidence smoothing hints, and event-to-action visual feedback where practical.
