# Auto Silent Timer

Auto Silent Timer prompts for a restore timer when the user switches an Android phone to silent or vibrate mode. When the timer expires, the app restores the ringer automatically.

The app runs entirely on the device. It has no internet permission, accounts, analytics, advertising, or off-device data collection.

## Requirements

1. Android SDK 36
2. JDK 17 through 23, or Android Studio's bundled JBR
3. Node.js 16 or newer for the repository helper scripts

## Builds

1. Run `npm run build-local-apk` to build a debug APK.
2. Run `npm run build-release-apk` to build a signed release APK.
3. Run `npm run build-release-aab` to build the signed Android App Bundle required by Google Play.

Release builds require the signing configuration described in `keystore.properties.example`. The Play publishing checklist and exact Console values are in [docs/google-play-publishing.md](docs/google-play-publishing.md).
