# ALLERION — Home & Server Monitoring

ALLERION is the Android client for the ALLERIA LABS home/server monitoring service.

## Current client capabilities

- Multiple server profiles with add, edit, delete and connection testing.
- AES-GCM encrypted server credentials protected by Android Keystore, including migration from legacy plaintext preferences.
- Password-protected portable server backups using PBKDF2-HMAC-SHA256 and AES-256-GCM.
- Live server status, CPU, memory, temperature, GPU, disk, network and power monitoring.
- Historical graphs for supported 1-hour, 24-hour and 7-day ranges.
- Daily, monthly and yearly energy summaries and electricity-cost estimates when the server supplies energy data and a tariff.
- Scheduled smart health alerts (15-minute background checks) for offline servers, CPU >= 90%, memory >= 90%, temperature >= 85 °C and disk >= 92%. Alerts are sent on transition into a critical state to avoid repeated notifications.
- Seven-day CSV monitoring report export through Android's share sheet.
- Adaptive multi-column server hub on wide screens and optional Android TV launcher visibility.

## Build

- Android Gradle Plugin: 8.5.2
- Gradle: 8.7
- JDK: 17
- Kotlin: 1.9.24
- Minimum Android: API 26

Local debug build:

```bash
gradle assembleDebug
```

## Release signing (required)

Production releases must use one persistent Android signing keystore. The workflow intentionally skips publishing a release when signing secrets are missing; it never falls back to the ephemeral debug key.

Configure these GitHub Actions repository secrets:

- `KEYSTORE_BASE64`: Base64-encoded persistent JKS/PKCS12 keystore
- `KEYSTORE_PASSWORD`: keystore password
- `KEY_ALIAS`: signing key alias
- `KEY_PASSWORD`: signing key password

Example to encode an existing keystore:

```bash
base64 -w 0 allerion-release.jks
```

Keep a secure offline backup of the keystore and passwords. Losing the signing key prevents installing future updates over copies signed with that key.

**Signing continuity warning:** previous CI releases were built with the runner's debug signing configuration. GitHub-hosted runners do not preserve that generated debug keystore between runs. A newly configured persistent release key will therefore not match those old APK signatures. Users moving from those builds may need to uninstall the old app before installing the first persistently signed release. Back up any app data first.

## Credential storage and backups

Server credentials are encrypted using AES-GCM with a key protected by Android Keystore. Existing plaintext server preferences are migrated on first launch after upgrading. If encrypted settings cannot be decrypted, the app must not silently overwrite them.

Portable backups are separately encrypted with a user-supplied password. The password is not stored by the app and cannot be recovered. Restoring a backup replaces the device's saved server list; it does not alter server-side history or configuration.

The app supports HTTP for trusted private-network monitoring servers for backward compatibility. Use HTTPS whenever the server is reachable over an untrusted network.

## Smart alerts

Enable Smart health alerts in ALLERION Settings. Android 13+ requires notification permission. Background checks run every 15 minutes while network connectivity is available. Thresholds are currently fixed defaults and notifications are transition-based; customize notification permission through Android system settings.

## API compatibility

The Android client uses these monitoring service endpoints:

- `GET /api/stats`
- `GET /api/history?range=1h|24h|7d`
- `GET /api/energy`
- `GET /api/power/config`
- `POST /api/power/config`
- `POST /api/power/test`

The monitoring service/backend is maintained separately from this Android client. Background alerts and CSV reports rely on the same server APIs.

## Automatic updates

The in-app updater still requires a stable update manifest and APK download endpoint accessible to users. The source repository is private, so a private GitHub release cannot be downloaded anonymously. Do not embed a personal access token in the APK. Configure a public release feed or a dedicated public distribution endpoint before enabling unattended update downloads.

## CI

Pull requests build a debug APK for verification. Pushes to `main` and manual workflow dispatches build and publish a signed release, provided all persistent signing secrets above are configured.