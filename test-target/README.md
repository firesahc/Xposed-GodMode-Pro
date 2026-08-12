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
