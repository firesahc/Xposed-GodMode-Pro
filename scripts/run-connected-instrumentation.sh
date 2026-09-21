#!/usr/bin/env sh

# android-emulator-runner invokes each script input line in a separate shell.
# Keep the complete test-and-diagnostics sequence in this process so the Gradle
# status is preserved and artifacts are available before the emulator is closed.
set +e

./gradlew :app:assembleDebug :app:assembleAndroidTest :engine:connectedDebugAndroidTest
build_status=$?

app_status=0
if [ "$build_status" -eq 0 ]; then
    if command -v pwsh >/dev/null 2>&1; then
        pwsh -NoProfile -File scripts/run-app-instrumentation.ps1
        app_status=$?
    else
        echo "::error::pwsh is required for foreground app instrumentation"
        app_status=127
    fi
fi

gradle_status=$build_status
if [ "$gradle_status" -eq 0 ] && [ "$app_status" -ne 0 ]; then
    gradle_status=$app_status
fi

adb logcat -d -v threadtime > instrumentation-logcat.txt || true

# Foreground app instrumentation is driven by the PowerShell harness and does
# not emit Gradle connected-test XML. Only scan the engine connected-test
# results produced by the Gradle task above.
result_roots="engine/build/outputs/androidTest-results/connected"
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
