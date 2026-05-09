# AGENTS.md

## Build

```bash
# First clone — submodule is REQUIRED, build fails without it
git submodule update --init --recursive
./gradlew assembleDebug
```

- `./gradlew build` runs the full build (CI uses this).
- Java 8 target; CI uses JDK 11 on Ubuntu.

## Modules

| Module | Type | Purpose |
|---|---|---|
| `:app` | Android application | The Xposed module APK |
| `:libxservicemanager` | Git submodule, Android library | System service injection (clipboard hijack) |

`app/build.gradle` declares `applicationId "com.viewblocker.jrsen"`, but the namespace is `com.kaisar.xposed.godmode`. These differ — do not assume they match.

## Critical: Custom Resource Package ID

`app/build.gradle` sets `--package-id 0x95` on `androidResources`. This is NOT the Android default (`0x7f`). The code at `GodModeInjector.java` checks `R.string.res_inject_success >>> 24 == 0x7f` and **refuses to load** if the ID equals the default. Do not remove or change the `additionalParameters` block, and do not change the resource-id check in the injector.

## Xposed Entry Point

Declared in `app/src/main/assets/xposed_init`:
```
com.kaisar.xposed.godmode.injection.GodModeInjector
```

- Implements `IXposedHookLoadPackage` + `IXposedHookZygoteInit`.
- `xposed-api-89.jar` is `compileOnly` (not bundled — provided by the framework at runtime).
- The injector registers `GodModeManagerService` into `system_server` when the package is `"android"`.

## Code Generation

- **AIDL**: `buildFeatures { aidl = true }`. AIDL sources in `app/src/main/aidl/` generate IPC stubs automatically.
- **Safe Args**: The `androidx.navigation:navigation-safe-args-gradle-plugin` generates `*Directions` classes for navigation.
- **Glide**: `annotationProcessor 'com.github.bumptech.glide:compiler'` generates a `GlideModule`.

## Testing

No unit or instrumented tests exist despite `AndroidJUnitRunner` and `espresso-core` being declared. Adding tests is fine, but there is nothing to run today.

## Repositories

Build scripts use Alibaba mirrors (`maven.aliyun.com`) before Maven Central / Google. This may cause resolution issues outside China. If dependency downloads fail, swap the repo order or remove the Alibaba entries.

## Data Storage (at runtime)

Rules are persisted as JSON files at `/data/system/godmode/{package}/`. Backups use `.gzip` format with a `manifest.json` manifest. Screenshots of removed views are stored as `.webp` files alongside rules.
