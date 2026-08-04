# Connect

Connect is a Kotlin Android application with a Jetpack Compose Material 3 UI and a persistent foreground service.

## Local data collection

After the user grants access, Connect collects the following data locally:

- Health Connect steps, distance, active calories, and exercise sessions for the current day.
- Up to 100 recent SMS messages, including sender, body, type, and timestamp.
- Up to 100 recent Android notifications, including source package, title, text, and timestamp.
- Current battery percentage, charging state, temperature, and power source.
- Latest location coordinates, accuracy, altitude, speed, provider, and timestamp.

Collection snapshots are held only in application process memory. The app does not contain a backend, network permission, upload logic, or persistent database. Data is cleared when the process is destroyed and rebuilt from currently accessible sources after restart.

## Background behavior

- Starts the foreground service when the application is first opened.
- Returns `START_STICKY` so Android can recreate the service after reclaiming its process.
- Keeps running when the application task is removed.
- Starts after locked boot, normal boot, and application updates.
- Displays the required ongoing foreground-service notification.
- Requests exemption from battery optimization on first launch.
- Requests exact-alarm access and schedules a 10-minute restart watchdog.
- Holds a partial wake lock while the foreground service is active.
- Uses a location foreground-service type and requests updates every 60 seconds or 25 meters.

On Android 11 and newer, background location must be enabled from the application settings by selecting **Permissions > Location > Allow all the time**. Connect opens the application settings during its first-run access flow.

These settings increase battery use. Android still does not let a regular application guarantee that its process is always alive. Force stop, the Android active-apps Stop action, manufacturer battery controls, or user-disabled permissions can prevent restarts. After Force stop, the user must open Connect again. A truly persistent Android process requires a privileged system application installed by the device manufacturer.

The `specialUse` foreground-service declaration must match the application's real connection work and is subject to Google Play review. Update its subtype description in `app/src/main/AndroidManifest.xml` when the actual connection behavior is implemented.

## Build

Requirements: Java 17 and an Android SDK containing platform and Build Tools 37.

```bash
./gradlew assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

Some Android builds treat SMS access as a restricted sideload permission. For a development device connected through ADB, install with:

```bash
adb install --grant-all-runtime-permissions --whitelist-restricted-permissions app/build/outputs/apk/debug/app-debug.apk
```
