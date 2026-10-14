# Installation Guide

## Option 1: Download a prebuilt APK

Go to the latest GitHub Release and download one of the APK files.

### Classic APK

Use this if you want Feurstagram to replace your current Instagram app.

### Clone APK

Use this if you want Feurstagram to be installed alongside the official Instagram app.

## Option 2: Build it yourself

If you prefer not to trust a prebuilt APK, follow the [build-from-source guide](BUILD_FROM_SOURCE.md).

## Recommended installation flow

1. Choose classic APK or clone APK.
2. Download the APK from the official GitHub Releases page.
3. Check the release notes for the supported Instagram version.
4. Install the APK on your Android device.
5. Log in through Instagram as usual.
6. Long-press the Home tab to open Feurstagram settings.
7. Report issues on GitHub if something does not work.

## Troubleshooting

### Android blocks the installation

Enable installation from unknown sources for your browser or file manager.
This permission is separate from APK signature validation.

### Android says the APK is missing a developer certificate

Delete the incomplete download and download the APK again directly from the
official GitHub Releases page. If Android still reports a missing certificate,
do not try to bypass device security settings; report the exact release asset,
device model, Android version, and the full installer message.

### The app does not install

Check whether you are installing the classic APK over an incompatible Instagram version.

If you want to keep official Instagram installed, use the clone APK instead.

### A feature is broken

Check the latest issues and releases. Instagram updates may break patches.

### You lose app data after reinstalling

For builds you sign yourself, Android treats a different signing key as a different trusted installer. Reinstall with the same keystore to preserve existing app data, or uninstall the previous build first.
