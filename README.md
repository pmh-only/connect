# Connect

Connect is a Kotlin Android application with a Jetpack Compose Material 3 UI and a persistent foreground service.

## Local data collection

After the user grants access, Connect collects the following data locally:

- Health Connect steps, distance, active calories, and exercise sessions for the current day.
- Up to 100 recent SMS messages, including sender, body, type, and timestamp.
- Up to 100 recent Android notifications, including source package, title, text, and timestamp.
- Current battery percentage, charging state, temperature, and power source.
- Latest location coordinates, accuracy, altitude, speed, provider, and timestamp.

Collection snapshots are held in application process memory and rebuilt from currently accessible sources after restart. When a collection endpoint and token are configured in the app, the complete snapshot is uploaded immediately and every five minutes.

## Go server

The Go 1.25+ server in `server/` exposes two independently bound services:

- `WEB_ADDR` defaults to `:8080` and serves `POST /api/collect`, the OIDC-protected dashboard at `/`, and `/healthz`.
- `MCP_ADDR` defaults to `:8081` and serves the bearer-protected MCP endpoint at `/mcp` plus `/healthz`.

Submissions are appended to the JSON Lines file configured by `DATA_FILE`. The dashboard and MCP tools use the latest submission for each device. The MCP tools are `list_devices` and `get_latest_device_data`.

Copy `server/.env.example` to `server/.env`, configure an OIDC client with the exact callback URL in `OIDC_REDIRECT_URL`, and export the values before starting:

```bash
cd server
set -a
source .env
set +a
go run ./cmd/connect-server
```

Required settings are `COLLECT_TOKEN`, `MCP_TOKEN`, `OIDC_ISSUER_URL`, `OIDC_CLIENT_ID`, and `OIDC_REDIRECT_URL`. `OIDC_CLIENT_SECRET` is optional for public clients. Generate independent random bearer tokens, for example with `openssl rand -hex 32`.

Configure the Android app with the full collection URL, such as `https://connect.example.com/api/collect`, and the value of `COLLECT_TOKEN`. The token is encrypted by Android Keystore. HTTP endpoints are accepted for local sideload testing, but they expose health, message, notification, and location data in transit; use HTTPS outside a trusted development network.

An MCP client must connect to the MCP port with an authorization header:

```text
URL: https://connect.example.com:8081/mcp
Authorization: Bearer <MCP_TOKEN>
```

The MCP endpoint implements JSON-RPC Streamable HTTP initialization and tools for protocol version `2025-11-25`. Browser-origin requests are rejected unless their exact origins are listed in the comma-separated `MCP_ALLOWED_ORIGINS` setting.

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

## GitHub Actions

`.github/workflows/server-container.yml` builds the Go server on separate native `amd64` and `arm64` GitHub-hosted runners, pushes each architecture by digest, and publishes one multi-architecture image to `ghcr.io/<owner>/<repository>`. Main receives the `latest` tag and every build receives `sha-<commit>`.

`.github/workflows/android-release.yml` runs for every commit on `main`, uses GitHub Actions `GITHUB_RUN_NUMBER` for the `v1.N` tag, decrypts `android.keystore.gpg`, builds a signed release APK, verifies its signature, and uploads the APK plus SHA-256 file to GitHub Releases. Re-running the same workflow run reuses its version number.

Add the encrypted `android.keystore.gpg` file at the repository root and configure this Actions secret:

- `ANDROID_KEYSTORE_GPG_PASSWORD`: password used to encrypt `android.keystore.gpg`.

The signing keystore must contain alias `android`. Its keystore and key passwords are both `123456`, as configured in `app/build.gradle.kts`. The GPG password must be different. The plaintext `android.keystore` remains ignored and is deleted from the runner after every release attempt.
