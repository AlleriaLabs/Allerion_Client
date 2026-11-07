# ALLERION — Home & Server Monitoring

ALLERION is the Android client for the ALLERIA LABS home/server monitoring service.

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

Production releases must use one persistent Android signing keystore. The workflow intentionally refuses to publish a release when signing secrets are missing; it never falls back to the ephemeral debug key.

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

## Credential storage

Server configurations are encrypted using AES-GCM with a key protected by Android Keystore. Existing plaintext server preferences are migrated on first launch after upgrading. If encrypted settings cannot be decrypted, the app must not silently overwrite them.

The app supports HTTP for trusted private-network monitoring servers for backward compatibility. Use HTTPS whenever the server is reachable over an untrusted network.

## API compatibility

The Android client uses these monitoring service endpoints:

- `GET /api/stats`
- `GET /api/history?range=1h|24h|7d`
- `GET /api/energy`
- `GET /api/power/config`
- `POST /api/power/config`
- `POST /api/power/test`

The monitoring service/backend is maintained separately from this Android client.

## CI

Pull requests build a debug APK for verification. Pushes to `main` and manual workflow dispatches build and publish a signed release, provided the signing secrets above are configured.
