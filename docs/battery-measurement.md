# AirControl Battery Consumption Measurement

## Target
**< 8% per hour** in active tracking mode on a mid-range device.

## Methodology

### Test Setup
1. **Device**: Mid-range reference device (e.g., Samsung Galaxy A54, Pixel 6a, or equivalent)
2. **OS**: Android 13+ with all system updates applied
3. **Baseline**: Charge device to 100%, enable airplane mode, then re-enable only camera
4. **Settings**: AirControl at default configuration (24 FPS, sensitivity 50, haptics off)
5. **Duration**: 30-minute continuous active tracking session
6. **Measurement tool**: `dumpsys batterystats` before and after session

### Procedure
```bash
# 1. Reset battery stats
adb shell dumpsys batterystats --reset

# 2. Start AirControl tracking (enable via app)

# 3. Keep hand visible to camera for 30 minutes
#    (ensures full-FPS mode, not idle scan mode)

# 4. After 30 minutes, capture stats
adb shell dumpsys batterystats > batterystats_after.txt

# 5. Calculate drain
# Look for "Estimated power use" section in batterystats output
# Or use: adb shell dumpsys battery (compare before/after levels)
```

### Measurement Points
| Metric | How to Measure |
|--------|---------------|
| Total battery % | `adb shell dumpsys battery` → `level` field |
| Camera wake locks | `dumpsys batterystats` → "Camera" partial wake lock time |
| Foreground service CPU | `dumpsys batterystats` → AirControl process CPU time |
| GPU usage | `adb shell dumpsys gfxinfo` → frame timing |

### Optimization Strategies

If battery drain exceeds 8%/hour, apply these strategies in order:

1. **Adaptive FPS** (already implemented): Drops to 5 FPS scan mode after 3 seconds of no hand detection. Reduces CPU/GPU load by ~80% during idle periods.

2. **Battery Saver mode** (user-facing toggle): When enabled, reduces maximum FPS to 15 and increases idle scan interval to 2 seconds. Target: 40% reduction in active drain.

3. **Thermal-aware FPS** (already implemented): At `THERMAL_STATUS_MODERATE`, FPS is automatically halved. This reduces both thermal output and battery drain.

4. **Camera resolution**: Currently 640x480. On low-end devices, consider 320x240 with a configuration flag.

5. **GPU vs CPU delegate**: GPU delegate is preferred for power efficiency on devices with proper GPU support. Fallback to CPU uses more battery but is more compatible.

### Expected Battery Budget (Mid-Range Device)

| Component | Estimated %/hour |
|-----------|-----------------|
| Camera capture (640x480 @ 24fps) | 3.0% |
| MediaPipe inference (GPU) | 2.5% |
| One Euro Filter + Gesture Engine | 0.3% |
| Foreground service + notification | 0.2% |
| Accessibility overlay | 0.3% |
| **Total (active mode)** | **~6.3%** |
| **Total (idle/scan mode @ 5fps)** | **~1.5%** |

### Test Log Template

| Date | Device | Android | Mode | Duration | Start % | End % | Drain %/hr | Notes |
|------|--------|---------|------|----------|---------|-------|-----------|-------|
| | | | Active | 30min | | | | |
| | | | Idle | 30min | | | | |
| | | | Battery Saver | 30min | | | | |

### CI Integration
Battery measurement is not automated in CI. It must be performed manually before each release using the procedure above. Record results in the test log template and include in the release notes.
