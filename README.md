# CornerShot

CornerShot is a small Android screenshot cropping tool. Its floating button captures the current display, then lets you select a rectangular area and save it or copy it as an image.

## Workflow

1. Install and open CornerShot.
2. Turn on **Capture button**. Android opens Accessibility Settings if the service needs enabling.
3. Enable **CornerShot screenshot button**, return to CornerShot, and optionally choose a position preset or adjust the button size from 24dp to 64dp.
4. Leave CornerShot open in the background. Tap its floating screenshot button in any app, drag a rectangle, then choose **Save**, **Copy**, **Retry**, or **Cancel**. Long-press and drag the floating button itself to place it freely; its relative position is retained across display rotations and size changes.

**Save** writes a PNG to `Pictures/Screenshots` with a `CornerShot_yyyyMMdd_HHmmss_SSS.png` filename. **Copy** places an image content URI on the clipboard without saving to Pictures. Its local cache file is retained for up to seven days and is subject to a bounded cache limit. **Retry** clears the selection on the same frozen screenshot. **Cancel** discards it.

## Privacy and Accessibility

Screenshots are captured and processed locally. The app has no `INTERNET` permission and includes no network access, accounts, ads, analytics, telemetry, cloud sync, or remote assets.

Android requires an enabled AccessibilityService for `AccessibilityService.takeScreenshot()` and the `TYPE_ACCESSIBILITY_OVERLAY` floating button. CornerShot uses Accessibility access only to capture the screen after the floating button is tapped and to display that button. It does **not** inspect accessibility content, retrieve window content, inject gestures, or filter keys. The service configuration sets `canTakeScreenshot=true` and `canRetrieveWindowContent=false`.

Apps or windows marked secure by Android (`FLAG_SECURE`) cannot be captured. CornerShot reports the failure and does not attempt to bypass that restriction.

## Build and install

The project uses Kotlin/JVM 17, Android Gradle Plugin 8.13.0, Gradle 8.13, and Android SDK Platform 36. With those installed, build a debug APK from the project root with:

```sh
./gradlew :app:assembleDebug
```

Install it on a connected device with:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
