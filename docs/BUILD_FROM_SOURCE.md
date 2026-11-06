# Build from source

This guide explains how to build Feurstagram yourself. Building from source lets
you inspect the patches and avoid trusting a prebuilt APK.

## Requirements

- JDK 21 and the Android SDK (`ANDROID_HOME` set, build-tools installed)
- A GitHub token with the `read:packages` scope in `~/.gradle/gradle.properties`
  (used to fetch the patcher dependency):
  ```properties
  gpr.user=<your-github-username>
  gpr.key=<token-with-read:packages>
  ```
- A supported Instagram APK (arm64-v8a from a trusted source such as APKMirror)

## Steps

1. Clone the repository.
   ```sh
   git clone https://github.com/jean-voila/Feurstagram.git
   cd Feurstagram
   ```

2. Build and apply the patches to your Instagram APK.
   ```sh
   ./build.sh instagram.apk
   ```
   Add `--clone` to install alongside a stock Instagram, and `--install` to push
   to a connected device:
   ```sh
   ./build.sh instagram.apk --clone --install
   ```

3. Install the signed result (if you did not pass `--install`).
   ```sh
   adb install -r feurstagram.apk
   ```

## Signing

The bundle is signed during the build. Set `FEURSTAGRAM_KEYSTORE_PASS` (and
optionally `FEURSTAGRAM_KEY_PASS`) to sign with `feurstagram.keystore`;
otherwise Morphe signs with a throwaway key for testing. When a release
keystore is configured, the build signs with Android SDK `apksigner`; all build
outputs are then verified with `apksigner` and their certificate fingerprint
is printed. Do not commit your release keystore, and keep using the same one to
reinstall without losing app data.

## Notes

- Only use APKs from trusted sources.
- Patches are fingerprint-based, so a new Instagram version usually only needs a
  rebuild.
- If the build fails, check open issues or create a bug report.
