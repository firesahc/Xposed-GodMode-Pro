# GodMode test target

This is an independent Android fixture app for device acceptance. It is not a
module of the root Gradle build and contains no production rule logic.

Build from the repository root:

```powershell
.\gradlew.bat -p test-target --no-daemon clean assembleDebug lintDebug
```

The launcher activity exposes stable content descriptions for static text and
image samples, margin/alpha observation, a 120-row two-template vertical list,
nested horizontal lists, dataset refresh, adapter replacement, fast scrolling,
and Activity recreation.

## Device test rules

All device tests follow the repository-wide [device test rules](../docs/device-test-rules.md).
Before each test that depends on this launcher being visible, explicitly bring
it to the foreground and confirm `RESUMED`:

```powershell
adb shell su -c "am start -W -n com.viewblocker.jrsen.testtarget/com.kaisar.xposed.godmode.testtarget.MainActivity"
```

Before every test round, verify the persistent log directory:

```powershell
adb shell su -c "ls -d /data/misc/godmode"
```
