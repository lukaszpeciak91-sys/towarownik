# Towarownik

Towarownik is a small native Android utility for fast retail product lookup. The V0.1 flow uses one search field for a seven-digit OBIK, EAN/GTIN, or product name, then presents the selected product for OBI store 075.

## Project status

The current phase is **V0.1 — unified OBI product search**. See [the progress log](docs/progress.md) for the next milestone and [the decision record](docs/decisions.md) for the approved scope.

## Technology

- Native Android application written in Kotlin
- Jetpack Compose UI with Material 3
- Package and application ID: `pl.lukaszpeciak.towarownik`
- Minimum SDK: 26 (Android 8.0)
- Compile and target SDK: 36
- JDK: 17
- Gradle: 9.5.0
- Android Gradle Plugin: 9.3.0
- Kotlin Compose compiler plugin: 2.3.21
- Compose BOM: 2026.06.00
- Activity Compose: 1.13.0
- OkHttp: 4.12.0
- kotlinx.serialization JSON: 1.9.0
- Kotlin coroutines: 1.10.2

## Local bootstrap and build

The committed Gradle wrapper is the canonical build entry point. With JDK 17 and Android SDK Platform 36 installed and `ANDROID_HOME` or `ANDROID_SDK_ROOT` configured, run:

```bash
./gradlew check assembleDebug
```

The debug APK is generated under `app/build/outputs/apk/debug/` and must not be committed.

## OBI live contract probe

The manual GitHub Actions workflow **OBI live contract probe** inspects the current public OBI product payload without changing or rebuilding the Android app. By default it requests OBIK `3496072` for store `075` through the same browser-compatible request profile used by production.

The workflow is manual-only. It always emits a bounded safe summary, but raw HTML and extracted `__NUXT_DATA__` are uploaded only after HTTP 200 from the expected `www.obi.pl` host. Raw captures are deliberately treated as short-lived sensitive diagnostic artifacts because a live public page can contain request-specific identifiers or future transient tokens; their retention is one day. The temporary cookie jar is deleted before any artifact upload and is never committed.

Normal pull-request CI never calls live OBI. It runs deterministic Python tests for the inspector alongside the Android checks. Use the manual probe only to refresh contract evidence when OBI changes, then convert confirmed structure into sanitized deterministic fixtures for parser tests.

## Signed Google Play AAB

A signed release bundle is built only by the manual GitHub Actions workflow **Build signed Play AAB**. Configure these repository Actions secrets first:

- `ANDROID_KEYSTORE_BASE64` — Base64-encoded upload keystore;
- `ANDROID_KEYSTORE_PASSWORD`;
- `ANDROID_KEY_ALIAS`;
- `ANDROID_KEY_PASSWORD`.

Then open **Actions → Build signed Play AAB → Run workflow**. The workflow runs `./gradlew check bundleRelease`, reconstructs the keystore only under the runner's temporary directory, and removes it after the job. Download the resulting artifact named `towarownik-play-release-aab`; it contains `app-release.aab` built from `app/build/outputs/bundle/release/app-release.aab`.

Never commit keystores, signing credentials, APKs, or AABs.

## Scope boundaries

This milestone supports direct OBIK lookup plus EAN/GTIN and product-name search with at most five selectable candidates. It has no nearby-store fallback, barcode or OCR support, persistence, dependency injection, navigation framework, analytics, accounts, ads, product images, Firebase, or AI. Dependencies and capabilities must only be introduced with a concrete requirement.

## Documentation

- [Architecture](docs/architecture.md)
- [Product and technical decisions](docs/decisions.md)
- [Progress and next milestone](docs/progress.md)
- [Contributor and agent rules](AGENTS.md)
