# Lessons Learned

## 2026-07-14: Do Not Change Tested Permissions During Publishing Preparation

1. What happened: While preparing the app for Google Play, `SCHEDULE_EXACT_ALARM` was removed because the app also declares `USE_EXACT_ALARM` and appears to qualify for Google's timer-app exception.
2. Root cause: A policy-minimization recommendation was treated as permission to change tested runtime configuration even though there was no demonstrated defect or Play rejection.
3. Prevention rule: Keep publishing-readiness changes separate from app behavior. Do not remove or replace an existing permission unless a verified platform requirement, build failure, runtime failure, or Play Console result requires it and the owner approves the behavior change.
4. Verification: Before completing publishing work, compare the manifest permission set to the last tested version and confirm that only explicitly approved permission changes are present.
5. Resolution: After owner approval and review of Android's API reference, retain `SCHEDULE_EXACT_ALARM` with `android:maxSdkVersion="32"` for Android 12 and 12L, and retain `USE_EXACT_ALARM` for Android 13 and newer. This preserves exact-alarm support while ensuring that only one permission applies on each device.

## 2026-07-14: Pin The Gradle Runtime To A Supported Java Version

1. What happened: After upgrading to Android Gradle Plugin 8.10.1 and Gradle 8.11.1, unit-test task creation failed with `Type T not present` before app compilation.
2. Root cause: The repository launcher honored the machine-wide JDK 24 `JAVA_HOME`, but Gradle 8.11.1 does not support running on Java 24. Android Studio's supported JBR 21 was already installed but never considered because `JAVA_HOME` took precedence.
3. Prevention rule: The repository launcher must validate the configured Java major version and use a supported Android Studio JBR when the machine-wide Java runtime is outside Gradle's supported range.
4. Verification: Run `node scripts/run-gradle.js --version` and `npm test`; confirm the launcher selects JBR 21 and the Android unit-test task is created successfully.

## 2026-09-08: Monitoring Dies In The Background Because Android Kills The Process

1. What happened: After running for a while, the app stopped prompting when the phone was switched to silent. The main screen still said monitoring was on.
2. Root cause: The ringer broadcast is only delivered to a running process, so the app depends on its foreground service staying alive. Android, manufacturer battery managers, the Stop button in the notification shade, and every app install all kill that process, and none of those events had a restart path. The app never asked for the battery-optimization exemption, which is the one setting those battery managers honor. The status text was computed from the on/off preference rather than from the service, so it could not report the death.
3. Prevention rule: Anything that must be alive in the background has to (a) ask for the battery exemption, (b) restart from every hook Android offers (sticky restart, boot, package replaced, an exact alarm, the app being opened), (c) report a kill instead of hiding it, and (d) never crash when Android refuses a foreground start. A watchdog alarm was deliberately not added; the owner considered the cost not worth it.
4. Verification: With monitoring on, stop the app from the notification shade's Active apps list, reopen the app, and confirm the main screen shows the kill reason and that monitoring restarts. Install a new build over the old one and confirm monitoring is running without opening the app. Run `adb shell dumpsys activity exit-info com.vibes.autosilenttimer` to compare Android's record with what the app reports.
5. Resolution: Added the Background battery use permission row and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (owner-approved behavior change), `MY_PACKAGE_REPLACED` handling, a restart from the timer-expiry alarm, `ProcessDeath` kill reporting on the main screen, and clean handling of a refused foreground start.
