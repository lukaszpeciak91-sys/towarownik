# Towarownik

Towarownik is a small native Android utility for fast retail product lookup. The V0.1 core can retrieve and parse an OBI product by its seven-digit OBIK; the app still shows a placeholder screen because product UI is outside this milestone.

## Project status

The current phase is **V0.1 — Android bootstrap**. See [the progress log](docs/progress.md) for the next milestone and [the decision record](docs/decisions.md) for the approved scope.

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

## Local bootstrap and build

The committed Gradle wrapper is the canonical build entry point. With JDK 17 and Android SDK Platform 36 installed and `ANDROID_HOME` or `ANDROID_SDK_ROOT` configured, run:

```bash
./gradlew check assembleDebug
```

The debug APK is generated under `app/build/outputs/apk/debug/` and must not be committed.

## Scope boundaries

This milestone intentionally has no EAN or text search, barcode or OCR support, persistence, dependency injection, navigation framework, analytics, accounts, ads, product images, Firebase, or AI. Dependencies and capabilities must only be introduced with a concrete requirement.

## Documentation

- [Architecture](docs/architecture.md)
- [Product and technical decisions](docs/decisions.md)
- [Progress and next milestone](docs/progress.md)
- [Contributor and agent rules](AGENTS.md)
