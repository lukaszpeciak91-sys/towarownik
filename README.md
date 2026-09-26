# Towarownik

Towarownik is a small native Android utility for fast retail product lookup. The V0.1 flow uses one search field for a seven-digit OBIK, EAN/GTIN, or product name, then presents the selected product for OBI store 075.

## Project status

The current phase is **chat-style shell + manual OBI search v0.2**. The default surface is now the Towarownik advisor chat shell with a modal conversation drawer, explicit new-case actions, and one-shot advisor messages presented as chat bubbles. Direct OBI search remains a separate full-screen local surface reachable from the top-right search action and does not depend on the proxy/OpenAI path.

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
- Optional future assistant proxy: Cloudflare Worker + TypeScript under `proxy/`

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

## AI assistant proxy foundation

The self-contained Cloudflare Worker project lives under `proxy/`. `GET /health` remains public. `POST /v1/agent/start` and `POST /v1/agent/continue` require the shared internal-testing app token and communicate with the OpenAI Responses API using the Worker-only OpenAI key.

Android OBI lookup remains local. The Worker never scrapes OBI or duplicates the Android OBI parsers/repositories. If the model asks for `find_available_obi_075`, Android executes the existing `ProductSearchRepository` + `ProductLookupRepository` flow, returns only compact verified product records, and remains capped at five products per tool call. The human-only Wyszukiwarka OBI may parse at most 25 recognized candidates from the already downloaded search HTML and reveals them in chunks of five; this does not add or assume an OBI pagination endpoint. There is still no persistent assistant history and the final advisor persona remains a later milestone.

Proxy checks require Node.js 22:

```bash
cd proxy
npm ci
npm run typecheck
npm test
```

For later Cloudflare repository setup, use `proxy` as the root directory and `towarownik-proxy` as the Worker name. Future secret names are documented in `proxy/README.md`; no secret is required for `/health`.

## Signed Google Play AAB

A signed release bundle is built only by the manual GitHub Actions workflow **Build signed Play AAB**. Configure these repository Actions secrets first:

- `ANDROID_KEYSTORE_BASE64` — Base64-encoded upload keystore;
- `ANDROID_KEYSTORE_PASSWORD`;
- `ANDROID_KEY_ALIAS`;
- `ANDROID_KEY_PASSWORD`;
- `TOWAROWNIK_APP_TOKEN` — the same shared Internal Testing app token configured on the production Worker.

Then open **Actions → Build signed Play AAB → Run workflow**. The workflow runs `./gradlew check bundleRelease`, reconstructs the keystore only under the runner's temporary directory, injects `TOWAROWNIK_APP_TOKEN` into Android through BuildConfig for that release build, and removes the transient keystore after the job. Download the resulting artifact named `towarownik-play-release-aab`; it contains `app-release.aab` built from `app/build/outputs/bundle/release/app-release.aab`.

`TOWAROWNIK_APP_TOKEN` is only an Internal Testing abuse barrier. A determined user can extract a static token embedded in an APK/AAB, so it must not be described as strong device or user authentication. Ordinary WYSZUKIWARKA use does not require this token.

Never commit keystores, signing credentials, APKs, or AABs.

## Scope boundaries

The Android product flow supports direct OBIK lookup plus EAN/GTIN and product-name search. Exact product facts still come only from the existing store-`075` lookup. The manual search surface may browse up to 25 recognized candidates from one OBI HTML response in five-item increments; the advisor/local tool remains independently capped at five. Exact results show product name, OBIK, local gross price, local stock, and the trusted `LocalProduct.productUrl` for external browser opening. The chat shell deliberately provides no persisted history, fake conversations, multi-turn OpenAI reuse, generic tool framework, server-side OBI logic, streaming, analytics, or final assistant persona.

## Documentation

- [Architecture](docs/architecture.md)
- [Product and technical decisions](docs/decisions.md)
- [Progress and next milestone](docs/progress.md)
- [Contributor and agent rules](AGENTS.md)
