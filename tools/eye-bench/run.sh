#!/bin/bash
# Compile and run tools/eye-bench/EyeBench.kt against the REAL production gaze sources.
# Same compiler the project declares, same source subset as tools/kj.sh; nothing here is a
# stand-in for the code under measurement.
set -eu
REPO=${REPO:-$(cd "$(dirname "$0")/../.." && pwd)}
TOOLS=/var/tmp/tools
KOTLINC=$TOOLS/kotlinc/bin/kotlinc
M=$TOOLS/gradle-home/caches/modules-2/files-2.1
CO=$(find "$M" -name 'kotlinx-coroutines-core-jvm-*.jar' | head -1)
JSON=$(find "$M" -name 'json-*.jar' | head -1)
OUT=/var/tmp/eye-bench
rm -rf "$OUT"; mkdir -p "$OUT"
[ -x "$KOTLINC" ] || { echo "run: bash tools/provision.sh"; exit 2; }
GAZE=$(for f in FaceLandmarkFrame EyeFeatureExtractor HeadPoseEstimator HeadPoseNormalizer \
        GazeCalibrationFeatureVector GazeCalibration PersonalizedGazeCalibration \
        PersonalizedGazeCalibrationSerializer OneEuroFilter EmaFilter BlinkDetector \
        GazeSmoothingMetrics RawIrisGaze GazeEligibility GazeJumpPolicy GazeDiagnostics; do
        echo "$REPO/app/src/main/java/com/aircontrol/tracking/$f.kt"; done)
KOTLIN_HOME=$TOOLS/kotlinc "$KOTLINC" -nowarn -cp "$CO:$JSON" -d "$OUT" $GAZE "$REPO/tools/eye-bench/EyeBench.kt"
java -XX:+UseSerialGC -Xms256m -Xmx512m -cp "$TOOLS/kotlinc/lib/kotlin-stdlib.jar:$CO:$JSON:$OUT" com.aircontrol.bench.EyeBenchKt
