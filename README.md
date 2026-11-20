# Focus Lock

Focus Lock helps you put your Android phone away for a set time or keep distractions out while you work. Start a session when you need it, or schedule a recurring time to disconnect.

- [**Download the latest APK**](https://github.com/jichoi33/Focus-Lock/releases/latest)
- [Report a bug](https://github.com/jichoi33/Focus-Lock/issues)

Requires **Android 8.0 or later**. The app is in English and follows your device's light or dark theme.

## Choose a lock

| Mode | What it does | What Android needs |
| --- | --- | --- |
| **Hard Lock** | Returns the phone to the Android lock screen if you unlock it during a session. Start a timer or set a one-time or repeating schedule. | A PIN, pattern, or password and device admin access. Scheduled locks need permission for alarms and reminders. |
| **Soft Lock** | Block selected apps and websites with **Blocklist**, or keep access to just the apps you choose and essential system features with **Allowlist**. | The **Focus Lock Soft Lock** accessibility service. |

## Install and get started

1. Open the [latest release](https://github.com/jichoi33/Focus-Lock/releases/latest), expand **Assets**, and download `Focus-Lock-<version>.apk`.
2. Open the APK on your Android device. If prompted, allow the browser or file manager opening it to **Install unknown apps**.
3. Open Focus Lock, choose a mode, and follow the permission prompts. If Android blocks the Soft Lock service for a sideloaded app, open Focus Lock's Android **App info** screen and choose **Allow restricted settings** from the menu.
4. Run **Test for 1 minute** before starting a longer lock. For Hard Lock, wake the screen and expand the lock screen notification to find **End test**. For Soft Lock, **End test** is available in the app or on the block screen.

To update, open the latest signed APK over your existing release. Android uses the app ID and signing certificate to decide whether it can install the update in place. Enable lock screen notifications if you want to see the Hard Lock countdown and emergency controls.

## How repeating Hard Locks work

Choose **Every day**, **Weekdays**, **Weekends**, or **Custom**. In Custom, tap the days the lock **starts**. For example, choosing Monday through Friday, **22:00 → 07:00**, includes Friday night through Saturday morning. Setting the start and end to the same time creates a full-day window. If Hard Locks overlap or run back to back, the phone stays locked until there is a gap. Tap **Save schedule** to review the first lock and its unlock time. Tap **Save schedule** again to confirm. Times follow your device’s 12-hour or 24-hour setting.

## During a session

- **Schedules** show the time range and start days for each repeating lock. Tap an inactive schedule to edit it, or use its switch to turn it on or off. When a repeating schedule could start immediately, you can start it now or wait for the next occurrence. Daily schedules that run continuously ask for confirmation. Focus Lock schedules a reminder five minutes before a scheduled lock. Android power-saving settings can delay it.
- **Emergency breaks** let you pause either lock temporarily while its countdown continues. Configure the duration and number of breaks in Focus Lock settings before starting. For Hard Lock, expand the lock screen notification. For Soft Lock, open the app from its countdown notification.
- **Soft Lock rules** can be tightened mid-session, and you can add time if you need it. Website rules include subdomains and depend on the address bar being readable in a supported browser. If the accessibility service disconnects, the app shows **Blocking paused** while the countdown continues.
- **Completion and statistics** show what you finished and how much time the app actually protected. Open Focus Lock after a regular session to see the celebration, or tap the completion notification. For Hard Lock, enable **Settings → Celebration on unlock** to open it when you unlock within 12 hours of the session ending. On Android 10 and later, allow Focus Lock to display over other apps when prompted. If Android prevents the automatic launch, the notification and app still offer the celebration. Each mode has a seven-day view and all-time totals. One-minute tests are excluded from statistics.

<details>
<summary>How Android permissions affect a lock</summary>

Hard Lock reapplies the system lock screen during calls. Android handles incoming and emergency calls on that screen. An active lock can also restrict access to Android Settings.

Soft Lock reads the visible address bar in supported browsers to apply website rules. Strict mode can cover a browser if its address stays unreadable. Samsung Internet requires a verified address. Web views embedded in an allowed app follow that app’s rule. To open links in supported browser-provided tabs, turn on **Soft Lock rules → Allow in-app browser tabs** before starting. This is off by default. Opening the full browser still requires adding it to the allowlist.

If Android stops a scheduled Hard Lock from starting in the background, tap the failure notification to activate it while the scheduled window is open. The app attempts to resume active locks after reboot. Force-stopping the app, revoking permissions, changing the device clock, or manufacturer background restrictions can affect enforcement.

</details>

## Privacy

Focus Lock stores your settings, selected apps and websites, and focus statistics on your device. It reads visible browser addresses to enforce your rules and does not store or transmit the addresses you visit. The app does not request internet access.

## Build from source

Use **JDK 17 or 21** and **Android SDK 35**. Open the project in Android Studio or run the Gradle wrapper from the repository root.

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

On Windows, use `gradlew.bat`. APKs are generated under `app/build/outputs/apk/`. The release build produces an unsigned APK. The application ID is `com.focuslock.app.preview18`.

<details>
<summary>Verify the official release signing certificate</summary>

Run `apksigner verify --print-certs Focus-Lock.apk`, substituting the downloaded filename. Official releases from the current signing lineage use this SHA-256 certificate fingerprint.

```text
8827cf9ab561a7534f8ecc056cc61920e37cc94bd8386a21e36bd4ec6ce6a8e9
```

</details>

## License

Focus Lock is distributed under the [MIT License](LICENSE).
