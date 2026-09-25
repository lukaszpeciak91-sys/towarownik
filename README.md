# Towarownik

Towarownik is a small native Android utility for fast retail product lookup. This repository currently contains only the V0.1 Android bootstrap and a placeholder screen; product lookup and OBI integration are not implemented.

## Project status

The current phase is **V0.1 — Android bootstrap**. See [the progress log](docs/progress.md) for the next milestone and [the decision record](docs/decisions.md) for the approved scope.

## Technology

- Native Android application written in Kotlin
- Jetpack Compose UI with Material 3
- Package and application ID: `pl.lukaszpeciak.towarownik`
- Minimum SDK: 26 (Android 8.0)
- Compile and target SDK: 36
- JDK: 17
- Gradle: 8.11.1
- Android Gradle Plugin: 8.10.1
- Kotlin and Compose compiler plugin: 2.1.21
- Compose BOM: 2025.05.01

## Local bootstrap and build

The binary `gradle/wrapper/gradle-wrapper.jar` is intentionally absent because Codex must not create or commit binary files. After checkout, generate it locally from a trusted Gradle installation:

```bash
gradle wrapper --gradle-version 8.11.1 --distribution-type bin
```

The generated wrapper JAR remains ignored. With JDK 17 and Android SDK Platform 36 installed and `ANDROID_HOME` or `ANDROID_SDK_ROOT` configured, run:

```bash
./gradlew check assembleDebug
```

The debug APK is generated under `app/build/outputs/apk/debug/` and must not be committed.

## Scope boundaries

This bootstrap intentionally has no networking, OBI payload parsing, barcode or OCR support, persistence, dependency injection, navigation framework, analytics, accounts, ads, product images, Firebase, or AI. Dependencies and capabilities must only be introduced with a concrete requirement.

## Documentation

- [Architecture](docs/architecture.md)
- [Product and technical decisions](docs/decisions.md)
- [Progress and next milestone](docs/progress.md)
- [Contributor and agent rules](AGENTS.md)
