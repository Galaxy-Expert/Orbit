# Orbit

Dynamic-island style notifications **around your camera cutout** for AOSP-based ROMs.
An open-source Xposed module for **Vector (LSPosed)**.

> Status: **0.1 alpha**. Target: **LineageOS 23.2 (Android 16)**. Older Android versions are not planned.

## Camera layouts

| Device | Behaviour |
|---|---|
| Punch-hole in the **center** | Island grows from the camera, centered |
| Punch-hole on the **left / right** | Island grows from the camera and slides inward so it never leaves the screen |
| **No camera cutout** (tablets, older phones) | A black **capsule (ellipse)** at the right of the status bar acts as the island |
| Cutout not on the top edge (e.g. phone in landscape) | Island is hidden |

## What 0.1 does

- Black pill that hugs the camera cutout (position auto-detected, fine-tune offsets in the app)
- Every few seconds a short **orbit ring** spins around the camera (or around the capsule)
- System heads-up notifications are replaced by an island animation (spring); tap to open, swipe up to dismiss
- Tap the camera area for a small menu: **Notifications** (opens the shade) and **Settings**
- Status bar icons are hidden, the **clock stays**
- Old notifications stay in the normal notification shade
- Calls, alarms, ongoing/foreground-service and full-screen notifications are left to the system on purpose
- Nothing is shown on the lock screen

Not in 0.1: media, calls, charging, per-app filters.

## Install

1. Install the APK, open **Vector** (LSPosed manager) -> Modules -> enable **Orbit**, scope **System UI**.
2. Restart System UI (reboot, or `su -c killall com.android.systemui`).
3. Open the Orbit app -> **Send test notification**, go home and wait 3 seconds.

## Build, signing and releases

GitHub Actions builds a **signed release APK** named `Orbit-v<version>.apk` on every push.
Download it from the run's **Artifacts**, or from **Releases** when you push a tag such as `v0.1.0`
(creating a Release on GitHub with a new tag works too).

To make updates install **over** the previous version (no uninstall needed) the APK must always be signed with
the same key. Create one keystore, keep a backup, and add these **repository secrets**
(Settings -> Secrets and variables -> Actions):

| Secret | Content |
|---|---|
| `ORBIT_KEYSTORE_BASE64` | the keystore file, base64 encoded (`base64 -w0 orbit.jks`) |
| `ORBIT_KEYSTORE_PASSWORD` | keystore password |
| `ORBIT_KEY_ALIAS` | key alias (e.g. `orbit`) |
| `ORBIT_KEY_PASSWORD` | key password (same as the keystore password for PKCS12) |

Never commit the keystore. Without the secrets the build still works but is signed with a throwaway debug key.
`versionCode` is taken from the Actions run number, so every build is newer than the last.

Local build: `gradle :app:assembleRelease` (JDK 17, Android SDK).

## Debugging

Logs appear in **Vector -> Logs** (lines starting with `[Orbit]`) or via `adb logcat -s Orbit` /
`su -c logcat -s Orbit`.

SystemUI class and view-id names change between Android versions. The module logs which hooks and
status bar ids were found:

| Log line | Meaning |
|---|---|
| `hooked SystemUIApplication.onCreate` | fallback start trigger is installed |
| `PhoneStatusBarView hooks: N` | N = 0 means the status bar class changed |
| `status bar id '...': found/missing` | which icon containers could be hidden |
| `...HeadsUpManager.showNotification hooks: N` | N = 0 means heads-up is not intercepted |
| `window added (type=2024)` | island window was created |
| `geometry applied: hole=... virtual=...` | detected camera position / virtual capsule |

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

Orbit is free software, licensed under the **GNU General Public License v3.0**.
See the `LICENSE` file or <https://www.gnu.org/licenses/gpl-3.0.html>.
