# Publishing Allerion Android releases

The pull-request workflow builds a debug APK for verification. A public release is created only when a version tag such as v1.3.3 is pushed.

## Required GitHub Actions secrets

Create these repository Actions secrets before tagging a release:

- ANDROID_KEYSTORE_BASE64 — base64-encoded persistent JKS/keystore file
- ANDROID_KEYSTORE_PASSWORD
- ANDROID_KEY_ALIAS
- ANDROID_KEY_PASSWORD

Create the keystore once and retain a secure offline backup. Never generate a replacement keystore for an update to an existing app: Android will reject the update if the signing identity changes. Never commit the keystore, passwords or Firebase configuration.

Optional:

- ANDROID_GOOGLE_SERVICES_JSON_BASE64 — base64-encoded Firebase google-services.json for project allerion-de360. Without this secret, the APK can still build, but Firebase Cloud Messaging will not be configured.

## Create a release

After the backend and Android changes have been merged and verified:

    git tag v1.3.3
    git push origin v1.3.3

The workflow derives the Android version name/code from the numeric major.minor.patch tag, builds a signed APK, creates a SHA-256 checksum, and publishes both as a GitHub Release.

Do not publish a tag until the backend version required by that Android build is available. Keep the Android application ID and signing key stable for upgrade compatibility.
