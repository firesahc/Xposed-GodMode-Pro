#!/usr/bin/env sh

# android-emulator-runner invokes each script input line in a separate shell.
# Keep the complete test-and-diagnostics sequence in this process so the Gradle
# status is preserved and artifacts are available before the emulator is closed.
set +e

./gradlew :app:connectedDebugAndroidTest :engine:connectedDebugAndroidTest
gradle_status=$?

adb logcat -d -v threadtime > instrumentation-logcat.txt || true

result_roots="app/build/outputs/androidTest-results/connected engine/build/outputs/androidTest-results/connected"
skipped=0
for result_root in $result_roots; do
    if [ ! -d "$result_root" ]; then
        echo "::warning::Instrumentation result directory is missing: $result_root"
        continue
    fi
    root_skipped=$(find "$result_root" -type f -name '*.xml' \
        -exec grep -h -o '<skipped' {} + 2>/dev/null | wc -l)
    skipped=$((skipped + root_skipped))
done
echo "Assume-skipped instrumentation cases: $skipped"
if [ "$skipped" -gt 0 ]; then
    echo "::warning::${skipped} instrumentation case(s) were assumption-skipped; IPC bridge paths are NOT verified on CI"
fi

exit "$gradle_status"
