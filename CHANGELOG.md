# Changelog

## 0.2.0 — 2026-11-07 (GitHub publication candidate)

- Fold in fixes confirmed during the November 6 Android Studio build:
  - Gradle wrapper download from the official v8.13.0 GitHub tag, verified by pinned SHA-256 (replaces the 404 wrapper URL; PowerShell 5.1-compatible).
  - Kotlin compiler target via `compilerOptions { jvmTarget = JVM_17 }` instead of deprecated `kotlinOptions`.
  - `ScrollView.isFillViewport` rather than `fillViewport`.
  - `arrayOf<Any>(duration, event.id)` for the mixed-type SQL bind values.
- Integrate Google Apps Script v0.1.3 recipient allowlist: multiple authorized addresses, exactly one recipient per app event, `updateAllowedRecipients()` preserves the existing secret.
- Add English and Japanese Android resources with Android 13+ per-app language selection.
- Add optional English/Japanese email templates to Apps Script without changing the v1 HMAC protocol.
- Add English and Japanese documentation, ignore rules, LICENSE, security guidance, scripts, and a CI workflow.
- Keep the original application ID and SQLite database schema for updates over the previously installed prototype.

## 0.1.3 — 2026-11-06 (Apps Script only)

- Recipient allowlist with multiple approved addresses and a key-preserving update function.

## 0.1.2 — 2026-11-06

- Official Gradle wrapper JAR URL and checksum corrected after 404 errors.

## 0.1.0 — 2026-11-06

- Initial Android charging-power monitor, local history/CSV, optional Apps Script email alerts, queued send/retry.
