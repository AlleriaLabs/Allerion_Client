# ALLERION — Home & Server Monitoring

ALLERION is the Android client for the ALLERIA LABS home/server monitoring service.

## Current client capabilities

- Multiple server profiles with add, edit, delete and connection testing.
- AES-GCM encrypted server credentials protected by Android Keystore, including migration from legacy plaintext preferences.
- Password-protected portable server backups using PBKDF2-HMAC-SHA256 and AES-256-GCM.
- Live server status, CPU, memory, temperature, GPU, disk, network and power monitoring.
- Historical graphs for supported 1-hour, 24-hour and 7-day ranges.
- Daily, monthly and yearly energy summaries and electricity-cost estimates when the server supplies energy data and a tariff.
- Scheduled smart health alerts (15-minute background checks) with user-configurable CPU, memory, temperature and disk thresholds; transition-based critical alerts, recovery notifications and a local 200-event alert history.
- Seven-day CSV and PDF monitoring report exports through Android's document picker.
- Adaptive multi-column server hub on wide screens and optional Android TV launcher visibility.
- Latest monitoring API response time displayed on the expanded server card (HTTP round-trip timing, not ICMP ping).

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

Enable Smart health alerts in ALLERION Settings. Android 13+ requires notification permission. Background checks run every 15 minutes while network connectivity is available. CPU, memory, temperature and disk thresholds are adjustable in Settings. Alerts are logged locally and a recovery notification is sent when a previously critical condition clears. The alert history is stored on-device and capped at 200 events.

## API compatibility

The Android client uses these monitoring service endpoints:

- `GET /api/stats`
- `GET /api/history?range=1h|24h|7d`
- `GET /api/energy`
- `GET /api/power/config`
- `POST /api/power/config`
- `POST /api/power/test`

The monitoring service/backend is maintained separately from this Android client. Background alerts and CSV reports rely on the same server APIs.

## Current client-side additions and backend dependencies

The client now exposes configurable alert thresholds, alert/recovery history, API response timing, and CSV/PDF exports using the existing API. These features do not require a backend schema change.

Docker/container inventory, process/service monitoring, LAN device discovery, packet-loss/ICMP diagnostics, and richer network topology require new collectors and authenticated endpoints in the separate monitoring backend. ALLERION will only show those capabilities after the backend provides real data; it will not fabricate metrics. Telegram/webhook integrations also require user-configured destinations and secure credential storage before they can be enabled safely.

## Automatic updates

The in-app updater still requires a stable update manifest and APK download endpoint accessible to users. The source repository is private, so a private GitHub release cannot be downloaded anonymously. Do not embed a personal access token in the APK. Configure a public release feed or a dedicated public distribution endpoint before enabling unattended update downloads.

## CI

Pull requests build a debug APK for verification. Pushes to `main` and manual workflow dispatches build and publish a signed release, provided all persistent signing secrets above are configured.