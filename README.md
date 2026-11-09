# Orbit

Dynamic-island style notifications **around your camera cutout** for AOSP-based ROMs.
An open-source Xposed module for **Vector (LSPosed)**.

> Status: **0.1 alpha**. Target: **LineageOS 24 (Android 17)**, phones with a **centered punch-hole camera**.

## What 0.1 does

- Black pill that hugs the camera cutout (position auto-detected)
- Every few seconds a short **orbit ring** spins around the camera
- System heads-up notifications are replaced by an island animation (spring); tap to open, swipe up to dismiss
- Tap the camera area for a small menu: **Notifications** (opens the shade) and **Settings**
- Status bar icons are hidden, the **clock stays**
- Old notifications stay in the normal notification shade
- Calls, alarms, ongoing/foreground-service and full-screen notifications are left to the system on purpose
- Nothing is shown on the lock screen

Not in 0.1: media, calls, charging, per-app filters, off-center cutouts, tablets, landscape.

## Install

1. Install the APK, open **Vector** (LSPosed manager) -> Modules -> enable **Orbit**, scope **System UI**.
2. Restart System UI (reboot, or `su -c killall com.android.systemui`).
3. Open the Orbit app -> **Send test notification**, go home and wait 3 seconds.

## Build

GitHub Actions builds a debug APK on every push (see `.github/workflows/build.yml`).
Download it from the run's **Artifacts**. Locally: `gradle :app:assembleDebug` (JDK 17, Android SDK).

## Debugging

```
adb logcat -s Orbit
```

SystemUI class and view-id names change between Android versions. The module logs which hooks and
status bar ids were found:

| Log line | Meaning |
|---|---|
| `hooked SystemUIApplication.onCreate` | fallback start trigger is installed |
| `PhoneStatusBarView hooks: N` | N = 0 means the status bar class changed |
| `status bar id '...': found/missing` | which icon containers could be hidden |
| `...HeadsUpManager.showNotification hooks: N` | N = 0 means heads-up is not intercepted |
| `window added (type=2024)` | island window was created |

If a hook is missing, open the SystemUI APK of your ROM in jadx and update the names in
`SystemUiHooks.kt` / `StatusBarCleaner.kt`.

## Project layout

```
app/src/main/java/app/orbit/island/
  MainHook.kt          Xposed entry point (System UI only)
  SystemUiHooks.kt     hook points: app start, status bar view, heads-up manager
  StatusBarCleaner.kt  hides status bar icons, keeps the clock
  OrbitController.kt   window, cutout detection, notification filtering
  OrbitView.kt         pill, animations, orbit ring, touch
  Config.kt            settings + state shared with System UI
  SettingsProvider.kt  ContentProvider that hands settings to System UI
  SettingsActivity.kt  settings screen
xposed-api/            compile-only stubs of the legacy Xposed API
```

## Naming / legal

"Dynamic Island" is a trademark of Apple Inc. Orbit is an independent project and uses no Apple assets.

## License

Protected by GNU General Public License v3.0
