# Taksula

Taksula is a small native Android utility for fast retail product lookup. The current OBI flow uses one search field for a seven-digit OBIK, EAN/GTIN, or product name, with exact price/stock verification against the conversation-selected supported OBI Poland store.

## Project status

The current phase is **Selective web search for Taksula v0.1**. Taksula keeps the final product/technical retail behavior and may now use one bounded OpenAI Responses `web_search` action when external verification is actually useful, while the Android OBI integration remains authoritative for current OBI store facts.

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
- Android resource localization: Polish default (`values/`) + English (`values-en/`)
- Active assistant proxy: Cloudflare Worker + TypeScript under `proxy/`

## Branding

- Public product name: **Taksula**
- Developer / brand owner: **Nepahu Studio**
- Working brand interpretation: **ally of the customer advisor / sojusznik doradcy**
- Android package/application ID intentionally remains `pl.lukaszpeciak.towarownik`.
- Repository, Worker/service identifiers, secrets, persisted keys, signing configuration, launcher icon assets, and internal symbols are intentionally not renamed in this branding-only PR.

Manual Google Play follow-up:
- public title: **Taksula**
- developer: **Nepahu Studio**
- optional short marketing line: **Sojusznik doradcy**


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

The self-contained Cloudflare Worker project lives under `proxy/` and is an active part of the advisor architecture. `GET /health` remains public. The authenticated agent endpoints `POST /v1/agent/start`, `POST /v1/agent/message`, and `POST /v1/agent/continue` require the shared internal-testing app token and communicate with the OpenAI Responses API using the Worker-only OpenAI key.

Android OBI lookup remains local. The Worker never scrapes OBI or duplicates the Android OBI parsers/repositories. The advisor has exactly one generic local tool, `find_obi_products(storeNumber, queries[])`. One call targets one explicit store and accepts at most five query groups whose requested limits sum to at most five, so one batch still performs no more than five exact product lookups. Android allows at most three local calls per USER turn; if a fourth is requested, it performs zero OBI work and returns the graceful `local_tool_limit_reached` continuation. Android validates `storeNumber` against one canonical static allowlist before any OBI request. The compact OpenAI tool payload contains one store context plus verified product OBIK, name, nullable brand/description, bounded technical facts, stock, and price; trusted product URLs, EANs, verification timestamps, and advisor search-result-count actions remain Android-local. When a broad advisor query requested multiple results and OBI reports more matches than the bounded advisor subset, Android may persist a trusted “Zobacz więcej” action with the exact query/store/count. The human-only Wyszukiwarka OBI still parses at most 25 recognized candidates from one search response and exact-verifies candidates against its explicit manual-search store context. Normal top-bar manual search starts from the active conversation store; a historical advisor action keeps its original store without mutating the conversation default. Selective OpenAI web search is now available; summarization/compaction remains a later milestone. AI usage/cost telemetry is now captured as the optimization baseline.

Advisor transport is versioned independently from the app version. Protocol v4 extends provider-aware v3 with one optional JPEG, PNG, or PDF attachment. Android streams private bytes as multipart only on `/start` or `/message`; text-only v2/v3 requests and every `/continue` remain JSON. The Worker validates MIME, file signature, and a 16 MiB ceiling before sending an image or PDF data URL to Responses. Missing protocol markers remain pinned to grouped v2 compatibility, and explicit v1 remains the legacy single-query contract.

Proxy checks require Node.js 22:

```bash
cd proxy
npm ci
npm run typecheck
npm test
```

For later Cloudflare repository setup, use `proxy` as the root directory and `towarownik-proxy` as the Worker name. Future secret names are documented in `proxy/README.md`; no secret is required for `/health`.

## AI usage baseline and local budget

Every successful OpenAI Responses API call may carry a bounded usage object from the Worker to Android: model, START/MESSAGE/CONTINUE request type, input/cached/output/reasoning/total token counts, estimated USD cost, and pricing version. Missing or malformed usage never invalidates an otherwise valid advisor answer. Each successful paid response is observed immediately, so usage from an earlier response remains counted even if a later local tool or continuation fails.

Current production advisor model is `gpt-6-luna` with low reasoning. Its server-controlled pricing configuration is versioned as `openai-gpt-6-luna-2026-09-27-v1`: USD 0.10/M ordinary input, USD 0.01/M cached input, USD 0.125/M cache-write input, and USD 0.50/M output. Requests above 272,000 input tokens use the model's long-context pricing for the full request: 2× all input-side rates and 1.5× output. Reasoning tokens are reported as an output detail and are never charged a second time. Android persists cumulative operational totals locally with decimal money arithmetic and keeps model totals separate for later comparisons.

**Settings → Użycie AI / AI usage** shows the latest measured model, tracking start, request/turn/tool-assisted-turn counts, ordinary usage details including cached/cache-write tokens, and estimated cost. Before measured usage there is no Android-side model guess. If one or more responses are unpriced, the UI explicitly reports their count and labels the accumulated priced spend as a known minimum rather than a complete total. PLN is derived only when an official NBP USD/PLN rate is available; the rate is refreshed on usage-screen demand with a 24-hour local cache. On NBP failure, the last cached rate remains dated and usable, otherwise PLN is simply unavailable. Advisor requests never depend on NBP.

The optional **Taksula AI budget** is user-configured local state, not OpenAI account-balance or account-credit data. Setting a remaining USD budget records the current cumulative Taksula spend as its baseline. Estimated remaining budget subtracts only later priced Taksula spend; if required cost data is unavailable, the app does not guess. Crossing below USD 1 produces one warning and re-arms only after the configured budget is reset/increased back to at least USD 1.

The GPT-5.6 Luna measurements remain historical baseline data in the same per-model usage store. The advisor tool receives compact verified product-page context from the same exact OBI lookup used for store facts. The current advisor behavior allows ordinary general technical knowledge while keeping SKU-specific/current-store claims under the verified OBI trust hierarchy. Selective web search is the final planned AI-capability addition for this stage.

## Final Taksula advisor behavior

Taksula is a practical in-store product and technical advisor for home-improvement/building-materials retail staff. It uses normal model knowledge for general technical explanation, installation principles, material/tool selection, compatibility reasoning, troubleshooting, and deciding which product categories are relevant to a customer's job; verified OBI data is not required for every general technical statement.

Taksula follows Advisor Product Contract v1: advisor first for ordinary technical questions, while clear intent for a concrete product, recommendation, assortment option, price/stock, or product identifier proactively uses the current conversation provider without requiring a separate “sprawdź w markecie” turn. Ordinary technical and sales-advice questions use normal model knowledge when sufficient and do not automatically trigger a lookup merely because a product category can be inferred. Ambiguous product-selection requests still ask one concise decision-critical clarification before lookup; assortment search is not used to guess the missing parameter.

Job/project and "what do I need?" questions are understood first. Once sufficiently clear, Taksula gives a small essentials-first practical answer and distinguishes optional convenience items without automatically turning the request into a store shopping list. It uses the current provider when the user clearly asks for concrete products or recommendations, or for a complete verified store kit; such a kit can be batched efficiently without per-category confirmation or an exhaustive list.

Explicit assortment/browse, current price/stock/availability, direct OBIK or product verification, sufficiently specified concrete product/recommendation intent, and explicit verified store-kit requests use the current provider lookup immediately. Complements are restrained and are mentioned proactively only when they materially help correctness, compatibility, safety, or avoidance of obvious failure. Android still independently enforces `MAX_LOCAL_TOOL_CALLS_PER_TURN = 3` as a hard safety fallback, and `local_tool_limit_reached` remains graceful if reached.

Availability states stay distinct: `stock = 0` is confirmed unavailable in that store; `stock = null` is unknown; grouped `not_found` means no verified matching result; grouped `unavailable` means retrieval could not establish the fact. A zero-stock/not-found requested item should not end at a bare "brak": when practical Taksula may verify a reasonable substitute in the current store and may offer another-market checking, but another market can be queried only when Android deterministically resolves the current user message against the active provider’s real branch directory (an explicit supported number still works). Ambiguous or unknown locations fail closed, and Taksula never invents another branch or claims cross-market availability without verification.

For a specific OBI SKU or current store fact, freshly verified `find_obi_products` data remains authoritative for OBIK, name, store, stock, price, and supplied product-page facts. Missing SKU-specific dimensions, materials, compatibility, certifications, applications, parameters, or limitations must not be invented. `productRefs` still resolve only against current-turn Android snapshots keyed by `(storeNumber, obik)`.

The existing model (`gpt-6-luna`), low reasoning effort, multi-query bounds, grouped results, partial-group failure behavior, structured `{text, productRefs}` answer, current-turn store authorization, web-search policy, and Android snapshot/card trust boundary remain unchanged.

## Selective web search and citations

The Worker now declares two distinct tool classes to the Responses API: the existing application function `find_obi_products` and the current built-in `{ "type": "web_search" }`. Tool choice is automatic and each Responses request sets `max_tool_calls: 1` for built-in tools. Android's existing maximum of three local OBI function calls per USER turn is separate and unchanged.

Search is intentionally selective. Ordinary technical questions should use model knowledge without browsing when that is sufficient. A focused web lookup is appropriate when the user explicitly requests online/current external verification or when a needed SKU-specific technical property is missing from verified OBI product data. Broader optional research should not trigger reflexive browsing. Current OBI stock, price, selected-store availability, and trusted product cards still come only from the local Android OBI integration and can never be overridden by web results.

For SKU-specific technical evidence, the instruction hierarchy prefers official manufacturer pages, manuals/datasheets, then authoritative technical/industry and reputable specialist sources; retailer pages are secondary and community sources are treated as experience/opinion. Searched page content is untrusted external data, never instructions for Taksula.

Only actual OpenAI `url_citation` annotations become user-visible sources. The proxy normalizes at most six stable-order, deduplicated HTTPS links with titles <= 200 characters and URLs <= 2048 characters. When OpenAI's real annotation offsets can be mapped exactly through the structured JSON `text` string, the normalized source also carries that bounded answer span; otherwise the span remains unknown rather than guessed. Android remaps safe spans through its display-text normalization, persists nullable spans with the source in Room (the source relation was introduced in v4; current schema is v5), renders a clickable inline citation marker at the supported fragment, and keeps the compact clickable source list as fallback/navigation. Raw search calls, queries, results and arbitrary metadata are never forwarded. Historical messages migrate with zero sources, and source rows cascade with their message/conversation. `productRefs` remain limited to current-turn Android-verified OBI snapshots; a web-only product never becomes a verified card.

AI Usage counts completed Responses `web_search_call` search actions directly from output, independently of optional token `usage`. Pricing version `openai-gpt-6-luna-2026-09-28-web-v1` keeps the existing GPT-6 Luna token pricing and adds the documented web-search fee of USD 0.01 per completed search action (USD 10 / 1000 calls). Merely making `web_search` available costs no search-call fee. When token usage is missing or malformed, the actual search count is still recorded while cost remains unpriced/known-minimum rather than guessed.

## Richer verified OBI product facts

The exact product lookup now extracts a compact optional product-context layer from the same decoded `__NUXT_DATA__` product object that already supplies verified identity and selected-store data. Live proof on OBIK 3496072, 6743009, and 7156243 confirmed `product.brand.name`, `product.productDescription`, `product.productOverview[]`, `product.technicalData.productDetails[]`, and `product.technicalData.dimensionsAndWeight[]`. No second product-page request is added.

Only three model-context fields are implemented: nullable brand, nullable normalized short description, and bounded technical facts. There is no separate inferred applications field because the live payload did not expose one stable structured application section; explicit uses/limitations present in OBI's structured product description remain available through the bounded description itself.

Bounds intentionally preserve the existing 16 KiB continuation envelope for up to five products: brand 80 chars, description 220 chars, at most 6 technical facts, fact label 60 chars, fact value 100 chars. Optional rich sections fail soft and never invalidate a product with valid identity/store/basic data. The parser strips presentation-only markup, collapses whitespace, removes empty entries, and deduplicates facts by normalized label.

Stock and price remain authoritative only from the requested selected store's `product.store.articleData.stock` and `product.store.articleData.pricing.grossPrice`. Rich descriptive/specification facts are product-level model context. They are not persisted into message-product snapshots, do not change visible verified cards, and do not enter problem reports. Product URL, verification timestamp, EAN, raw HTML, raw Nuxt, cookies, and diagnostics remain excluded from OpenAI tool results.

## Trusted OBI product thumbnails

Exact product parsing now derives at most one Android-local primary product image from structured Product JSON-LD on the same OBI product page already fetched for identity, stock, price, and product facts. Only HTTPS URLs on the confirmed OBI image host `bilder.obi.pl` are accepted; malformed, missing, non-HTTPS, or untrusted-host values fail soft to null. Only the first structured product image is retained, so galleries, labels, banners, recommendations, and heyOBI graphics are not exposed as product thumbnails.

The trusted image URL is presentation metadata only. It flows `LocalProduct → VerifiedProductSnapshot → VerifiedProductUiModel`, is persisted with assistant cards in Room v6, and is reused by manual-search exact enrichment. It is not sent to OpenAI, the Worker continuation payload, productRefs, or model context, and it never causes an additional OBI request.

Compose renders a compact bounded `ContentScale.Fit` packshot using Coil 3.x with the OkHttp network module. Image loading and caching are owned by Coil; a load failure removes only the thumbnail surface and never changes product/advisor/manual-search state. Manual search keeps the existing 25-candidate cap, five-at-a-time reveal/enrichment, and search-more behavior.

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

KWANT verified products keep selected-branch stock and nullable central stock as
separate facts; the public price is labelled as an indicative online price.
In a KWANT conversation, an explicitly named location can be resolved against
the live KWANT branch directory for one lookup without changing the saved
WorkingProfile. Unknown or non-unique names fail closed. OBI behavior is
unchanged.

The Android product flow supports direct OBIK lookup plus EAN/GTIN and product-name search. Name/EAN discovery remains store-independent; every exact product verification uses the selected supported OBI store. New conversations default to `075`, while the explicit selector persists one store per conversation without creating an empty conversation row. Alternate stores requested by the advisor are temporary turn-local queries and never mutate the conversation default. The manual search surface may browse up to 25 recognized candidates from one OBI HTML response in five-item increments; the advisor/local tool remains independently capped at five. Advisor “Zobacz więcej (N)” reuses that existing manual surface and does not add OBI pagination or imply that all N reported results are loaded or available in-store. The app still has no location-based store inference, server-side OBI logic, streaming, analytics backend, or autonomous/general agent framework.

## Documentation

- [Architecture](docs/architecture.md)
- [Product and technical decisions](docs/decisions.md)
- [Advisor product contract v1](docs/advisor-product-contract-v1.md)
- [Progress and next milestone](docs/progress.md)
- [Taksula behavioral evaluations](docs/behavioral-evals.md)
- [Contributor and agent rules](AGENTS.md)


## Local conversation persistence

Room schema v6 stores local conversation metadata, rendered USER/ASSISTANT messages, store-aware verified product snapshots, normalized web sources, and Android-local advisor search actions attached to ASSISTANT messages:

- conversation: id, title, createdAt, updatedAt, nullable lastResponseId, draft, storeNumber;
- message: id, conversationId, role, text, createdAt;
- message product: messageId, position, storeNumber, OBIK, name, nullable stock, lossless decimal price text, trusted productUrl, nullable trusted primary imageUrl, verifiedAt;
- message source: messageId, position, bounded title, normalized HTTPS URL;
- message search action: messageId, position, exact advisor query, storeNumber, reportedTotalCount;
- message rows cascade with conversation deletion; products, sources, and search actions cascade with their message.

Schema v1 upgrades through explicit 1→2, 2→3, 3→4, 4→5, and 5→6 migrations. The 2→3 step adds `Conversation.storeNumber` and `message_products.storeNumber` with deterministic default `075`; 3→4 adds `message_sources`; 4→5 adds `message_search_actions`; 5→6 adds nullable `message_products.imageUrl`, so historical product cards migrate with no thumbnail. There is no destructive fallback. Search actions are local UI metadata only and are never sent to the Worker or encoded into assistant text.

No API keys, app bearer tokens, OBI HTML/cookies, parser internals, raw OpenAI responses, or reasoning data are persisted.

History search is entirely local and performs case-insensitive SQLite phrase matching against conversation titles and USER/ASSISTANT message text. No proxy, OpenAI, embeddings, or network request is involved.

A first user send creates the conversation and derives a bounded local title from that message. Follow-up turns use the stored final `lastResponseId`. Only the final answer response ID advances conversation context; transient tool-call IDs are never stored.

If a request is interrupted after the USER message was written but before an ASSISTANT completion, the trailing USER message is removed during recovery and its text becomes the editable draft. No paid request is repeated automatically.


## Conversation lifecycle

A conversation is intended to represent one customer case. Start **Nowa rozmowa** for a new customer or a new problem; follow-up questions about the same case stay in the existing conversation. This is a product convention, not a technical length limit.

On normal app startup, local-only cleanup deletes conversations with `updatedAt < now - 30 days`. A conversation exactly at the cutoff is retained. Message rows disappear through the existing Room cascade. Cleanup does not call the proxy, OpenAI, or OBI and does not use WorkManager or a background scheduler.

Each history row also exposes **Usuń rozmowę** with explicit confirmation. Deleting an active conversation cancels its active advisor request, invalidates stale callbacks, removes the local conversation/messages, and returns the advisor surface to a fresh empty chat.


## Advisor verified product cards

Final model answers use a strict store-aware structured shape:

```json
{
  "text": "Krótka odpowiedź dla użytkownika.",
  "productRefs": [
    {"storeNumber": "074", "obik": "3496072"},
    {"storeNumber": "075", "obik": "3496072"}
  ]
}
```

`productRefs` is only a selection/order hint. During one USER turn Android retains successful exact `LocalProduct` snapshots keyed by `(storeNumber, obik)`, so the same OBIK in two stores remains two distinct verified facts. Android resolves final references only against snapshots verified during that current USER turn. Unknown model references are ignored and never trigger a lookup.

Card name, store number, OBIK, stock, gross price, URL, and verification time all come from the retained local snapshot. OpenAI never supplies or overrides card facts, never receives `productUrl` or `verifiedAt`, and never receives OBI HTML, Nuxt data, cookies, diagnostics, or parser internals. The selected snapshots are committed atomically with the ASSISTANT message and final response ID, then displayed as historical point-in-time facts with a “Sprawdzono …” timestamp. Reopening history does not refresh them.




## OBI multi-store

- One canonical Android allowlist contains only confirmed OBI Poland three-digit store numbers; the default remains `075`.
- The app never generates arbitrary store numbers and never scrapes the store catalog at runtime.
- The conversation selector is the only source that mutates the persisted conversation default store. A transient selection on an unsaved new conversation is persisted only when the first USER message creates the conversation.
- At the start of each USER turn Android captures an immutable conversation-store snapshot plus supported exact three-digit store tokens literally present in that current USER message.
- A requested tool store is authorized only when it equals the conversation store or is both allowlisted and literally present in the current USER message. Prior turns, city/store names, regions, and embedded longer numbers do not authorize a store.
- Unauthorized/unsupported tool calls fail closed before OBI and are returned to the advisor as bounded `store_not_authorized` continuation data; there is no fallback/substitution to `075`.
- `/start`, `/message`, and `/continue` all carry the immutable conversation-store context. The Worker validates only exact three-digit shape; Android owns allowlist membership.
- Store `075` remains the deterministic regression baseline and manual live-contract probe default.

## Android localization

User-facing Android UI text is resource-based rather than embedded in Compose/controllers. Polish is the default `values/strings.xml` resource set and English is provided completely in `values-en/strings.xml`. Settings exposes exactly **Polski** and **English** using `AppCompatDelegate.setApplicationLocales(...)`.

On API 32 and lower AppCompat persists the chosen app locale through its supported `autoStoreLocales` metadata service. On API 33+ AppCompat delegates to the platform per-app locale mechanism; `android:localeConfig` points to a locale configuration containing only `pl` and `en`. The activity uses `AppCompatActivity` with the smallest AppCompat-compatible host theme change; the Compose Warm Modular Utility theme remains the visual source of truth.

The UI locale is presentation-only. It is not sent to the Cloudflare proxy or OpenAI, does not change `/start`, `/message`, or `/continue`, does not translate persisted USER/ASSISTANT text, and does not restart an OpenAI response chain.

Settings also owns navigation to the existing OBI diagnostics screen; the previous hidden long-press on the centered app title has been removed. **Zgłoś problem / Report problem** is now a real user-controlled reporting entry point. **Polityka prywatności / Privacy policy** remains a disabled future row with no invented URL or legal copy. The About area renders the current `app_name` resource plus BuildConfig version information so future naming remains cheap.


## Problem reporting

Taksula exposes two local reporting entry points: a quiet **Zgłoś / Report** action under persisted ASSISTANT responses and **Settings → Zgłoś problem / Report problem** for general issues.

Contextual reports always include the exact reported persisted ASSISTANT response and its persisted verified-product snapshots. Broader conversation content is opt-in and the checkbox defaults **OFF**. When enabled, assistant-response context includes persisted messages only from the beginning of that conversation through the reported response; later messages and the unsent draft are excluded. General reporting similarly includes the current persisted conversation only after explicit opt-in.

Every report includes bounded app/device/UI-locale metadata. Existing OBI diagnostics are optional evidence only when diagnostics were already enabled and already contain sanitized recorder output. When such data exists, the report form exposes a separate **Dołącz diagnostykę OBI / Include OBI diagnostics** checkbox that defaults **OFF**; diagnostic data is included only after that explicit opt-in. Reporting never enables diagnostics, starts the live probe, replays an OBI operation, calls OpenAI, or refreshes products.

Generated reports live temporarily under `cacheDir/reports/` and are shared through a non-exported FileProvider exposing only that cache subdirectory. The final action creates the TXT, attaches it to an Android `ACTION_SEND` intent, pre-fills the Nepahu Studio recipient, and opens the system chooser. Taksula does not send email itself. If the system chooser cannot be opened, the fresh report file is deleted and the UI shows a bounded share-unavailable error. Screenshots are intentionally left to the user's mail/share app.

Reports never include OpenAI response IDs, tool-call IDs, `lastResponseId`, API/app tokens, Authorization headers, cookie values, raw OpenAI responses/reasoning, OBI HTML/Nuxt payloads, account identifiers, IP address, or precise location. Reports are not stored in Room and there is no report backend.
