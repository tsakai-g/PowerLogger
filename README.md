# Power Logger for Android / 停電ロガー

[日本語のREADME](README.ja.md) · [Changelog](CHANGELOG.md) · [Security](SECURITY.md) · [MIT License](LICENSE)

An open-source, small Android app that records **loss and restoration of external USB charging power** on a spare Android phone and optionally sends email through Google Apps Script (GAS). The app has English and Japanese UI in the **same APK** and is intended for compatible Android smartphones.

> **Important:** This app detects **USB charging power**, not the presence of AC mains voltage directly. An unplugged cable or defective USB adapter can look like a power outage. It is not a certified utility power instrument or safety alarm.
>
> **Build status:** A prior Japanese prototype was successfully built with Android Studio after the fixes recorded below. This consolidated v0.2.0 source archive has been checked with static/local tests, but **its APK has not yet been compiled or tested on an Android device in this environment**. The GitHub Actions workflow performs a complete Android build after the repository is uploaded.

## Features

- Timestamp **USB power disconnected** and **USB power restored** events; compute outage durations.
- SQLite history and Excel-friendly UTF-8 CSV export.
- Foreground monitoring service, dynamic power-event receiver and 20-second fallback checks.
- Attempts to resume monitoring after boot (subject to Android and device-manufacturer background restrictions).
- Email alerts are **OFF by default**. When enabled, send stop, restoration and test notifications.
- Email events are queued locally while offline; background jobs retry later (delivery timing is not guaranteed).
- Server allows **multiple approved email addresses**, but **each phone selects exactly one** and the server sends **only to that one recipient if it is on the allowlist**.
- Signed requests (HMAC SHA-256), event-ID deduplication, and fixed recipient allowlist. No Gmail password stored in the app.
- **English/Japanese** UI, notification text and CSV labels; optional English/Japanese server email language.
- No Play Store registration required: install the APK directly.

## Project structure

```text
PowerLoggerAndroid/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/jp/tsakailab/powerlogger/
│       │   ├── MainActivity.kt
│       │   ├── MonitorService.kt
│       │   ├── PowerStore.kt
│       │   └── EmailSender.kt
│       └── res/
│           ├── values/strings.xml       # English fallback
│           ├── values-ja/strings.xml    # Japanese
│           └── xml/locale_config.xml   # per-app language selection
├── server/Code.gs                     # public, reusable GAS source
├── test/server-tests.js               # local GAS tests, sends NO email
├── .github/workflows/android-build.yml
├── gradle/wrapper/gradle-wrapper.properties
├── install-gradle-wrapper.ps1
├── install-gradle-wrapper.sh
├── SETUP-FIRST.bat
├── gradlew / gradlew.bat
├── README.md / README.ja.md
├── SECURITY.md / CHANGELOG.md / LICENSE
└── .gitignore
```

All addresses in the published code are **placeholders**; no shared secret, personal email list, deployment URL, recorded outage history or signing key is included.

## 1. Build an APK on Windows

1. Install [Android Studio](https://developer.android.com/studio) and its Android SDK. The project uses Android 16 SDK **API 36**, Gradle **8.13**, Android Gradle Plugin **8.13.2**, Kotlin **2.3.0**, and JVM target **17**.
2. Download this repository as ZIP (or clone it) and extract it on the PC.
3. Run **`SETUP-FIRST.bat` once** in the `PowerLoggerAndroid` folder. The old `services.gradle.org/distributions/gradle-8.13-wrapper.jar` URL was a **404**. This corrected installer obtains the official JAR from Gradle's official GitHub source tag `v8.13.0` and verifies pinned SHA-256 `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`. It never installs an unverified download.
4. In Android Studio choose **Open** and select the folder containing `settings.gradle.kts` (the `PowerLoggerAndroid` folder).
5. Allow Gradle Sync and SDK Platform **36** installation. Use Android Studio's bundled JDK (17 or newer supported by this toolchain).
6. Choose **Build → Build Bundle(s) / APK(s) → Build APK(s)** (menu names may vary slightly by version).
7. Find **`app/build/outputs/apk/debug/app-debug.apk`** and copy it onto your Android phone. Open it on the phone and allow installation from that trusted file source.
8. Launch Power Logger, allow notification permission, and press **Start monitoring**.

**On macOS/Linux:** run `./install-gradle-wrapper.sh`, then open the project in Android Studio (or run `./gradlew :app:assembleDebug` with a configured Android SDK). The installer is verified by the same checksum.

**Updating an installed version:** the `applicationId` (`jp.tsakailab.powerlogger`) and SQLite schema remain unchanged. Use an APK signed with the **same signing key** as the installed version. A different signing key will prevent an in-place update; do **not** uninstall the old app just to work around this if you want to preserve the history. Keep production signing keys offline, not in GitHub.

## 2. Switch between English and Japanese

Android 13 or later: open **Power Logger → App language: English / 日本語**, then choose **English** or **日本語** in Android's per-app language screen. Alternatively find **Settings → Apps → Power Logger → Language**. The app uses Android `localeConfig`, not two separate codebases. The operating system language need not be changed. The Google Apps Script email language is configured **separately** in `server/Code.gs`.

## 3. Configure Google Apps Script (optional)

The Android app works as a local logger without Google Apps Script. **Alerts default to OFF**.

### First-time GAS setup

1. In a browser, open <https://script.google.com/home/start> and create a new project.
2. Replace the entire `Code.gs` in the GAS editor with **`server/Code.gs` from this repository**.
3. In the GAS editor (not your public repository), edit the array at the top:

   ```javascript
   const ALLOWED_EMAILS = [
     'one@example.com',
     'two@example.com'
   ];
   ```

   Replace these examples with **your own approved email addresses**, using as many entries as needed (or one). Duplicates and invalid entries are rejected. Do not put personal email addresses in a Git commit.
4. Optional: set **`EMAIL_LANGUAGE = 'en'`** for English messages (default `'ja'`) and adjust `EMAIL_TIME_ZONE` if needed.
5. Choose the function **`setup`** and click **Run**. Approve the authorization prompt. The execution log prints a **new 64-character shared secret**. Save the secret privately; `setup()` will refuse to overwrite an existing one.
6. Run **`authorizeMail`** to approve MailApp authorization.
7. Choose **Deploy → New deployment → Web app**; **Execute as: Me** and **Who has access: Anyone**, then deploy. Copy the **`https://script.google.com/macros/s/.../exec`** URL (not `/dev`). If your Google Workspace administrator blocks public web-app access, this method may not be available.
8. On Android: enter the **single desired recipient address** (must match an entry in `ALLOWED_EMAILS`), the `/exec` URL and the shared secret. Save, turn **Enable email alerts** ON, and use **Send test email**.

### Updating an existing GAS deployment (v0.1.0–0.1.3)

- Replace the deployed GAS editor's `Code.gs` with the latest `server/Code.gs`.
- Edit `ALLOWED_EMAILS` in that GAS project.
- Run **`updateAllowedRecipients()`**, **NOT** `setup()`. This preserves the existing shared secret and does not require reinstalling the Android APK.
- **Deploy → Manage deployments → Edit → New version → Deploy** to activate the updated code while retaining the existing `/exec` URL.
- Email alerts continue to use the same v1 HMAC request format. The app's saved key, URL, recorded history and settings remain intact when updating on the same signing identity.

### How multiple addresses work

Suppose the server allowlist contains `one@example.com` and `two@example.com`:

| Android-app recipient | Result |
|---|---|
| `one@example.com` | One email to `one@example.com` |
| `two@example.com` | One email to `two@example.com` |
| `other@example.com` | Rejected: `recipient_not_allowed` |

The app does **not** email everyone on the allowlist. Address matching is exact after trimming and lowercasing the configured values. The app stores one normalized recipient, and the request signature protects that recipient.

## 4. Device setup / reliable logging

On a compatible Android phone, enable notifications and allow background operation and unrestricted battery usage where these options are available. Review battery-saving settings and test that monitoring continues with the screen off. Android and OEM policies may still stop it. Reboot auto-resume is best effort. A battery-backed LTE SIM or LTE Wi-Fi router can keep the phone online during a mains outage.

1. Alerts OFF: start monitoring → disconnect USB charger → reconnect → check two timestamps and CSV.
2. Repeat with the screen off for several minutes.
3. Enable alerts after GAS setup, send test email, and check mailbox.
4. Disable data connectivity, disconnect/reconnect USB, restore connectivity and check queued notifications.
5. Reboot and verify that monitoring resumes without manually reopening the app.
6. Switch the app language to English and back to Japanese; check history, buttons, notifications and CSV header language.

Events observed later by periodic polling or after reboot may have inaccurate timestamps and are indicated accordingly. Stopping monitoring while power is off results in an unknown restoration time. If Android's clock changes, the calculated duration may be unreliable. These limitations remain even when email delivery succeeds.

## 5. GitHub publication checklist

- [ ] Do not paste actual email addresses or a generated shared secret into `server/Code.gs` in the public repository. Configure your **private GAS editor copy** instead.
- [ ] Do not commit `*.jks`, `*.keystore`, `keystore.properties`, local settings, SQLite databases, CSV exports, deployment URL, tokens or log files.
- [ ] Review the license attribution; this package uses the MIT License with `Power Logger contributors` as the copyright holder description.
- [ ] Check all changed files, including hidden files, before the first commit. `.gitignore` is not a substitute for inspection.
- [ ] Push to a new GitHub repository and check the **Actions** tab for the **Verify Android and Apps Script** workflow. It will run Gradle `:app:assembleDebug` and the local Apps Script tests on every push/PR.
- [ ] Build and install the app on a target Android device and record test results before calling the app production-ready.

### Local verification without sending email

With Node.js 18+ installed:

```sh
node test/server-tests.js
```

This script runs a simulated GAS environment and does not contact Google or send real mail. It verifies signed requests, one-recipient allowlist behavior, refusal of unauthorized addresses, duplicate suppression, elapsed time, key-preserving allowlist updates, and both email languages.

## Security & support

Read [SECURITY.md](SECURITY.md). The Android shared secret is stored in private app preferences (not Android Keystore-encrypted in this version). The GAS `/exec` endpoint is callable by anyone but checks a per-installation HMAC and allowlist. Google Apps Script quotas and network conditions can delay or prevent delivery. For power-monitoring research and convenience only, **not an emergency alert system**.
