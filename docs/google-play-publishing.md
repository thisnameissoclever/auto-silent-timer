# Google Play Publishing Guide

This guide contains the current release process and recommended Google Play Console answers for Auto Silent Timer (`com.vibes.autosilenttimer`). Console labels can move over time, but the required declarations and values remain the same.

## 1. Release Contact And Remaining Owner Input

Use these finalized release contacts and replace the remaining video placeholder before production submission:

1. Support email: `silent-timer-support@stfuai.com`
2. Privacy policy URL: `https://thisnameissoclever.github.io/auto-silent-timer/`
3. Foreground-service video URL: `https://thisnameissoclever.github.io/auto-silent-timer/foreground-service-demo.mp4`.

The video is published from commit `c6675b6` on the public `gh-pages` branch and is available at the URL above.

Do not use `https://stfuai.com/privacy` for this app. That page describes STFUAI Podcasts, while the GitHub Pages URL above contains the published Auto Silent Timer policy.

## 2. Build The Play Artifact

1. Keep the release signing keystore backed up securely. Losing it can complicate future update signing even with Play App Signing.
2. Run `npm run test`.
3. Run `npm run lint`.
4. Run `npm run build-release-aab`.
5. Upload `app/build/outputs/bundle/release/app-release.aab` to Google Play.
6. For every later upload, increase `versionCode` in `app/build.gradle.kts`. Google Play rejects a reused version code.

This project targets Android API 36 and retains a minimum supported version of Android 8.0, API 26.

## 3. Create The App

1. Open Google Play Console and select **Create app**.
2. App name: `Auto Silent Timer`.
3. Default language: `English (United States) - en-US`.
4. App or game: `App`.
5. Free or paid: `Free`.
6. Accept the declarations and create the app.
7. Package name is established by the first uploaded bundle: `com.vibes.autosilenttimer`.

## 4. Play App Signing

1. Open **Test and release > Setup > App signing** when prompted during the first release.
2. Choose **Use Google-generated app signing key** unless you specifically need Play builds to update an existing build distributed outside Google Play.
3. Keep the repository's current release key as the upload key.
4. If prompted to register or reset an upload certificate, use the certificate from the existing Auto Silent Timer release keystore. Never upload the keystore file or its passwords as listing attachments.

The user's current sideloaded APK does not need to match the Play signing key, so the Google-generated app signing key is the simpler long-term choice. A user moving from a differently signed sideloaded build to the Play build must uninstall the sideloaded build once.

## 5. Main Store Listing

Open **Grow users > Store presence > Main store listing** and enter:

### App Details

1. App name: `Auto Silent Timer`
2. Short description: `Automatically restore your phone's sound after a silent-mode timer.`
3. Full description:

> Auto Silent Timer helps prevent missed calls and notifications after you silence your phone.
>
> When you switch your phone to silent or vibrate, the app asks how long it should stay quiet. Choose a preset or enter a custom duration. Auto Silent Timer restores the ringer when time runs out.
>
> Features:
>
> • Prompts you to set a timer when you manually enable silent or vibrate mode
> • Offers quick presets and custom durations
> • Shows the time remaining and scheduled restoration time
> • Restores monitoring and active timers after a device restart
> • Ignores sound changes caused by Do Not Disturb or Bedtime mode
> • Runs on your device with no account, ads, analytics, or data collection
>
> The app needs Display over other apps and Do Not Disturb access to detect manual ringer changes, show the timer prompt, and restore sound. Its ongoing notification keeps background monitoring visible and enables reliable operation.

### Graphics

1. App icon: 512 by 512 pixel, 32-bit PNG with alpha, no larger than 1 MB.
2. Feature graphic: 1024 by 500 pixel JPEG or 24-bit PNG without alpha.
3. Phone screenshots: upload at least two accurate screenshots. Use the main status screen, permissions screen, silent-mode timer prompt, and active timer state.
4. Screenshot files must be JPEG or 24-bit PNG without alpha, between 320 and 3840 pixels, with the long dimension no more than twice the short dimension.
5. Do not add a store preview video unless desired. The foreground-service review video is a separate policy artifact and is not a marketing video.

Prepared upload files are in `store-listing/`:

1. App icon: `store-listing/app-icon-512.png`
2. Feature graphic: `store-listing/feature-graphic-1024x500.png`
3. First phone screenshot: `store-listing/phone-active-timer.png`
4. Second phone screenshot: `store-listing/phone-silent-prompt.png`
5. Third phone screenshot: `store-listing/phone-monitoring.png`
6. Optional onboarding screenshot: `store-listing/phone-main.png`

The phone screenshots were captured from the release APK on an API 36 emulator and contain no personal device content. Upload them in the order above so the listing leads with the completed timer workflow instead of permission setup.

## 6. Store Settings

Open **Grow users > Store presence > Store settings**:

1. App category: `Tools`.
2. Tags: choose `Utilities`, `Productivity`, and `Personalization` if offered. Console tag choices vary; only choose tags that accurately describe the app.
3. Store listing contact email: `silent-timer-support@stfuai.com`.
4. Website: `https://stfuai.com`.
5. Phone: optional.
6. External marketing: owner preference. Leaving it enabled allows Google to promote the listing using its assets.

## 7. Privacy Policy

Open **Policy and programs > App content > Privacy policy**:

1. Privacy policy URL: `https://thisnameissoclever.github.io/auto-silent-timer/`.
2. The page must be public, active, readable without authentication, non-editable by viewers, and explicitly name Auto Silent Timer.
3. The policy must state that the app collects and shares no user data, describe locally stored settings, explain the permissions, identify the developer, and provide a contact method.

The ready-to-publish text is in [privacy-policy.md](privacy-policy.md).

## 8. Data Safety

Open **Policy and programs > App content > Data safety** and answer:

1. Does your app collect or share any of the required user data types? `No`.
2. Is all of the user data collected by your app encrypted in transit? This follow-up should not appear after answering No. If Console still presents it, select `Not applicable` when available.
3. Do you provide a way for users to request that their data is deleted? `No`, because no user data is collected or stored off-device.
4. Add `https://thisnameissoclever.github.io/auto-silent-timer/` when requested.
5. Review the preview. It should state that no data is collected and no data is shared.

Basis for these answers: the app has no `INTERNET` permission, network client, analytics library, advertising library, account system, or telemetry. Its preferences remain on-device. Android permissions alone are not data collection unless the app transmits or shares the accessed data.

## 9. Ads

Open **Policy and programs > App content > Ads**:

1. Does your app contain ads? `No`.

The app contains no advertising SDK or advertising content.

## 10. App Access

Open **Policy and programs > App content > App access**:

1. Are all app features available without special access? `Yes, all functionality is available without special access`.
2. Reviewer note, if a free-text field appears: `No account or login is required. Android will ask the reviewer to grant Display over other apps, Do Not Disturb access, notification permission, and the background battery exemption during setup.`

Device permissions are not login credentials or restricted app access. The reviewer can grant them using the app's visible setup controls.

## 11. Target Audience And Content

Open **Policy and programs > App content > Target audience and content**:

1. Target age groups: select `18 and over` for the narrowest, simplest declaration. The app is a general phone utility and is not designed for children.
2. Does the store listing intentionally appeal to children? `No`.
3. Confirm that the app does not contain content intended for children.

Selecting younger age groups creates Families Policy obligations that this utility neither needs nor benefits from.

## 12. Content Rating

Open **Policy and programs > App content > Content rating**:

1. Enter `silent-timer-support@stfuai.com` as the questionnaire email.
2. Category: `Utility, Productivity, Communication, or Other` using the closest Console wording.
3. Answer `No` to violence, fear, sexuality, gambling, controlled substances, profanity, user-generated content, social interaction, location sharing, purchases, and unrestricted web access.
4. The expected result is an all-ages or equivalent low-content rating. Submit the questionnaire and save the assigned rating.

## 13. News, Health, Financial, Government, And Other Declarations

For any App content declarations shown by Console:

1. News app: `No`.
2. Health app or health features: `No`.
3. Financial features: select every `None` or `No` option.
4. Government app: `No`.
5. COVID-19 contact tracing or status app: `No`.
6. Data deletion: no account creation is offered, so an account-deletion URL is not required.
7. Full-screen intent: the app does not request `USE_FULL_SCREEN_INTENT`; no declaration should be required.

## 14. Exact Alarm Permission Declaration

The manifest requests `SCHEDULE_EXACT_ALARM` through Android 12L, API 32, and `USE_EXACT_ALARM` on Android 13, API 33, and newer. This is Android's documented compatibility pattern and ensures that only one exact-alarm permission applies on each device. If Console displays the Exact alarm permission declaration, use:

1. Core functionality category: `Alarm or timer app`.
2. Permission use: `Core functionality`.
3. Justification:

> Auto Silent Timer is a user-facing timer app. When the user manually switches the phone to silent or vibrate, the app asks the user to select an exact duration. It schedules an exact alarm for the user-selected end time so it can restore the phone's ringer promptly, including while the device is idle. Without exact alarm access, the core timer can finish late and the phone may remain silent beyond the duration explicitly chosen by the user.

4. If asked whether this can use an inexact alarm: `No`. The app's primary promise is restoration at the user-selected timer deadline; idle batching can delay an inexact alarm.
5. If a video is requested here, use the same demonstration video as the foreground-service declaration if it clearly shows selecting a timer and sound being restored at expiry.

## 15. Foreground Service Declaration

The app uses one `specialUse` foreground service. Open **Policy and programs > App content > Foreground service permissions** and enter:

1. Foreground service type: `Special use`.
2. Use case: choose `Other` or the closest available monitoring option.
3. Feature description:

> When the user enables monitoring, Auto Silent Timer runs a foreground service that watches for user-initiated changes to silent or vibrate mode. The app immediately posts an ongoing notification so the work is visible, and the user can stop monitoring from the app at any time. When a qualifying ringer change occurs, the service shows the timer prompt. It does not use the network, location, microphone, camera, or user data.

4. Why the task must start immediately:

> The service must be active before the user changes the ringer mode. If startup is deferred, a silent or vibrate transition can occur before monitoring begins, so the timer prompt will not appear and the app's core workflow is missed.

5. User impact if interrupted:

> If the service is interrupted, the app cannot observe the next user-initiated silent or vibrate transition. The user will not receive the timer prompt and the phone may remain silent until changed manually. Any timer already scheduled remains independently registered with Android's alarm system, but new ringer changes cannot be detected while monitoring is stopped.

6. Video URL: `https://thisnameissoclever.github.io/auto-silent-timer/foreground-service-demo.mp4`.
7. Video checklist: show launching the app, granting setup permissions, enabling monitoring, the persistent notification, manually switching to silent or vibrate, the timer prompt appearing over another screen, choosing a short timer, and sound restoration when it expires.
8. Make the video public or unlisted and viewable without signing in. Do not include private notifications, contacts, account details, or other personal phone content.

Manifest subtype text submitted with the app: `Monitors ringer mode to prompt for an auto-restore timer`.

## 16. Permission Explanations For Review Notes

Use these values if Console or a reviewer asks why each permission is necessary:

1. Display over other apps (`SYSTEM_ALERT_WINDOW`): `Shows the user-requested timer prompt immediately after the user manually switches the phone to silent or vibrate, even when another app is in the foreground.`
2. Do Not Disturb access (`ACCESS_NOTIFICATION_POLICY`): `Allows the app to distinguish relevant sound-mode changes and restore the ringer when the user's timer ends. The app does not read notification contents.`
3. Notifications (`POST_NOTIFICATIONS`): `Shows the required foreground-service status notification and informs the user about silent-timer status and completion.`
4. Exact alarms (`SCHEDULE_EXACT_ALARM` through Android 12L and `USE_EXACT_ALARM` on Android 13+): `Restores the ringer at the end of the exact duration selected by the user, including while the device is idle.`
5. Foreground service (`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`): `Keeps user-enabled ringer-mode monitoring active and visible through an ongoing notification.`
6. Start after reboot (`RECEIVE_BOOT_COMPLETED`): `Resumes monitoring after restart and re-registers an active timer because Android alarms do not survive a device reboot.`
7. Battery optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`): `Opens the system dialog that lets the user exempt the app from battery optimization. The app's core function is a foreground service that must be alive at the moment the user switches the ringer; without the exemption, Android and manufacturer battery managers stop that service after a period of inactivity and the timer prompt never appears. The user can decline; monitoring still runs, with reduced reliability, and the app never re-prompts on its own.`

Play's policy allows this permission only when an app's core functionality is adversely affected without it. That is the case here: the whole app is the background monitor. Do not add any other use of the exemption.

## 17. Countries, Pricing, And Distribution

1. Open **Monetize with Play > Pricing and distribution** or the current equivalent.
2. Keep the app free. A free app cannot later be converted to paid under the same package name.
3. Select the countries and regions where support and policy obligations can be met. For a first release, United States only is the simplest choice; worldwide distribution is reasonable because the app contains no regional service or language-dependent backend.
4. Do not opt into Wear OS, Android TV, Android Auto, ChromeOS-specific, or Android XR programs. This is a phone/tablet Android app.

## 18. Internal Test Release

1. Open **Test and release > Testing > Internal testing**.
2. Create an email list and add the owner's Google account plus any trusted testers.
3. Create a release and upload `app-release.aab`.
4. Use release name `1.0 (1)`.
5. Release notes:

> Initial release of Auto Silent Timer. Set an automatic ringer restoration timer whenever the phone is switched to silent or vibrate.

6. Save, review, and roll out to internal testing.
7. Install from the Play opt-in link on at least one physical device. Test permission onboarding (including the Background battery use row), monitoring notification, overlay prompt, exact timer expiry while idle, reboot recovery, recovery after an app update, and manual stop/restore actions. Then stop the app from the notification shade's Active apps list and confirm the main screen reports why monitoring stopped.

## 19. Production Release

1. Resolve every incomplete task on the Console dashboard and every warning under **Publishing overview**.
2. Complete the Data safety, content rating, target audience, ads, app access, exact alarm, and foreground-service declarations.
3. Verify the privacy-policy page in a private browser window.
4. Promote the tested internal release to production or create a production release from the same bundle.
5. Select a staged rollout if Console offers it. A small initial percentage limits exposure if a device-specific issue appears.
6. Review all changes and submit them for review.
7. Treat submission, approval, and public availability as separate states. Check **Publishing overview** after approval; managed publishing can hold an approved release until it is manually published.

## 20. Release Verification

After Google approves and publishes the app:

1. Open the public Play listing while signed out or in a private browser.
2. Confirm the app name, developer identity, screenshots, support contact, privacy policy, and Data safety section.
3. Install the Play build on a device that does not have a differently signed sideloaded build, or uninstall the sideloaded app after backing up any settings that matter.
4. Repeat the core timer workflow on the Play-delivered build.
5. Keep the signed `.aab`, version details, release notes, and Play review result with the release records.
