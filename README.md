# Focus Lock 1.9.4

Focus Lock is an Android focus timer with a Kotlin, Jetpack Compose, and Material 3 interface. The app uses English and follows the system light or dark appearance. The Android services that enforce locks are implemented in Java.

Changing the system appearance updates the open screen and any Soft Lock cover in place, including while you edit a schedule or a website list.

New immediate Hard Lock and Soft Lock sessions default to 30 minutes.

Android Settings is blocked while either lock is active. Hard Lock keeps the Android keyguard in place and also covers the Settings app during unlock transitions when the Focus Lock accessibility service is enabled. Soft Lock covers the Settings app in both Blocklist and Allowlist.

Each mode posts its own live countdown. Focus Lock checks the app's notification permission and the selected mode's notification channel before showing the start confirmation. Enable lock screen notification display in Android to see the Hard Lock timer and Emergency break on the lock screen. Android controls which actions are accessible there. Use the Hard Lock notifications screen in Focus Lock settings to preview its pop-up. In Do Not Disturb, allow Focus Lock and notification pop-ups for eligible apps in the active Android mode.

## Install

Download `Focus-Lock-1.9.4.apk` from the [v1.9.4 release](../../releases/tag/v1.9.4) and open it on your Android device. Focus Lock requires Android 8.0 (API 26) or later. If prompted, allow the app opening the APK to install unknown apps.

## Emergency breaks

Both modes support temporary breaks. Open Settings → Emergency breaks before starting a lock. Defaults are two minutes each and three uses per session. Choose 1–60 minutes and 1–20 uses, or turn breaks off. Limits stay fixed throughout the session. The original countdown continues. Blocking resumes automatically when a break expires if time remains. Android Settings stays covered when accessibility is connected. Overlapping locks share the break and quota. Extending a lock does not replenish the quota. Both one-minute tests can end without using a break.

On Hard Lock, expand the lock screen notification and tap Emergency break, then unlock Android normally. On Soft Lock, open the app through its countdown notification and tap Emergency break.

## Hard Lock

- Set an Android PIN, pattern, or password and enable device admin for Focus Lock. During an active lock, Focus Lock reapplies the Android lock screen after you unlock the device.
- Focus Lock checks as soon as Android reports an unlock and briefly retries during the unlock animation. A fallback check runs while the screen is on.
- Start a session by entering its duration, or add a one-time or repeating schedule. Android may ask you to allow alarms and reminders before scheduling your first lock.
- **Test for 1 minute** locks the phone and ends automatically. Wake the screen and expand its notification to see **End test**. Settings → Lock screen notifications provides links to check both notification settings.
- The app and countdown notification use the latest end time among active Hard Locks. If Android prevents a scheduled lock from starting in the background, tap the failure notification to activate it while the scheduled window is still open.
- Existing schedules created with a location condition are disabled on upgrade. Enable one explicitly to make it apply at any location.
- Hard Lock continues to reapply the Android lock screen during calls. Android handles incoming and emergency calls on its lock screen. If you enable Emergency break in Focus Lock, the lock notification also provides a temporary break when notifications are available.

## Soft Lock

- **Blocklist** blocks the apps and websites you select. Website rules include subdomains and use the visible address bar of supported browsers. A blocked host must remain visible briefly before Focus Lock covers the page. Strict mode covers a browser after its address stays unreadable for about two seconds. In Samsung Internet, a website block requires a verified address even in strict mode. Typing in the address bar and short navigation transitions stay clear.
- **Allowlist** permits the apps you select. Essential system surfaces, the default dialer, and Focus Lock remain available. Web views inside allowed apps follow the app rule.
- **Return to Home for blocked apps** is on by default for new sessions in both modes. Focus Lock shows the block screen briefly and takes you back to Home. Turning this option off keeps the block screen open. Website blocks provide a **Previous page** button.
- Enable **Focus Lock Soft Lock** in Android accessibility settings. If your device restricts accessibility for a sideloaded app, check **Allow restricted settings** in the app-info menu.
- If the accessibility service disconnects during a session, **Blocking paused** shows that enforcement has stopped while the timer continues. Reconnecting applies the current rules for the remaining time.
- **Test for 1 minute** applies the selected rules without saving changes to the last normal session. Use **End test** in the app or on the block screen to exit early.
- Select **Add time** during a Soft Lock to extend it. You can add between one minute and 24 hours at a time.
- Select **Tighten rules** to add blocked apps or websites in Blocklist mode, or remove allowed apps in Allowlist mode. Changes apply to the current session and become the selections for future sessions.

## Statistics

Open each mode’s statistics to see protected time by day, sessions started, emergency breaks, and days with focus over today and the preceding six days. Time accumulates while the respective enforcement service is active and pauses during emergency breaks. One-minute tests are excluded. Select **All time** to see totals from every recorded date. The app stores daily totals on the device, including records from 1.8.0 when updating in place.

## Reboot and data

Immediate sessions use elapsed time during the current boot, including device sleep. Calendar schedules use the device clock and time zone. After reboot, Focus Lock estimates the remaining time from the saved wall-clock deadline, bounded by the saved duration, and resumes when Android sends the boot-completed event or reconnects the accessibility service. Wall-clock changes while the device is off can affect this estimate. Force-stopping the app, revoking permissions, and manufacturer background restrictions can affect enforcement. Focus Lock keeps settings and selected targets on the device. It does not store or transmit visited addresses.

## Build and update

Use JDK 17 or 21 and Android SDK 35. Run `./gradlew :app:assembleRelease` on Linux or macOS or `gradlew.bat :app:assembleRelease` on Windows. The unsigned APK is at `app/build/outputs/apk/release/app-release-unsigned.apk`. The display name is **Focus Lock**. The application ID stays `com.focuslock.app.preview18`, which is the ID of the distributed 1.8.0 APK.

Bump `versionCode` and `versionName` in `app/build.gradle` for each release. For in-place updates from the signed 1.9.1 release, keep the application ID and use the **Focus-Lock-release.p12** keystore with alias `focus-lock`. Use Android Studio's **Build → Generate Signed App Bundle or APK** or run:

```text
apksigner sign --ks Focus-Lock-release.p12 --ks-key-alias focus-lock --out Focus-Lock-signed.apk app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --print-certs Focus-Lock-signed.apk
```

Compare the resulting certificate SHA-256 fingerprint with the 1.9.1 release before distributing an update.

```text
8827cf9ab561a7534f8ecc056cc61920e37cc94bd8386a21e36bd4ec6ce6a8e9
```

Store the keystore and password separately and keep private backups. The 1.9.1 release starts a new signing lineage. An earlier installation with a different signing certificate needs a fresh install.
