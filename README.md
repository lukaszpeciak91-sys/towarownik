# Taksula

Product stock units are optional, provider-verified metadata. KWANT reads explicit `unit` from its product/current-stock payload after confirming product and department IDs. OBI has no unit beside selected-store `stock`, but exact PDP product data can explicitly declare a sales unit (for example `Sprzedaż: na metry`); only those dedicated sales-unit facts are mapped to stock presentation, never names, categories, dimensions or price-per-unit text. Cards show a verified label (`135 m`, `4 szt.`, `20 m²`) or the bare numeric quantity; zero/unknown availability semantics are unchanged. Room v13 adds nullable `message_products.stockUnit` with non-destructive 12→13 migration; previously persisted cards have unknown units.


Taksula is a native Android retail/wholesale advisor and product-lookup utility. A persistent WorkingProfile selects the active provider and branch (currently OBI Poland or KWANT); product search and Advisor verification remain provider-owned, and current local stock/price facts come from Android-side provider integrations.

## Project status

The current phase is **final Advisor contract validation and release readiness**. Shared OBI/KWANT advisor policy, provider-aware protocols, attachments, branch routing, privacy-safe observability, and Android trace correlation are implemented; the remaining release gate is the final real-model behavioral run plus Play AAB validation.

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

Provider lookup remains local to Android. The Worker never scrapes OBI/KWANT or duplicates provider parsers/repositories. OBI text turns keep grouped protocol v2 with `find_obi_products(storeNumber, queries[])`; provider-aware text turns use v3 `find_products(providerId, branchId, requestedBranch, queries[])`; current Android attachment turns use provider-aware v5 and the same `find_products` schema (legacy v4 remains supported). Each grouped call accepts at most five query groups whose requested limits sum to at most five. Android independently allows at most three local calls per USER turn; if a fourth is requested, it performs no provider work and returns the graceful `local_tool_limit_reached` continuation. Android owns provider/branch authorization, executes the real provider lookup, and returns only compact verified product facts to the Worker. Trusted product URLs, verification timestamps, attachment bytes, and provider parser internals remain Android-local. Selective OpenAI web search is supplemental and cannot replace local provider verification for current stock, price, availability, or product-card grounding.

Advisor transport is versioned independently from the app version. Protocol v4 extends provider-aware v3 with exactly one JPEG, PNG, or PDF attachment. Opt-in protocol v5 adds 1–3 attachments per START/MESSAGE, using repeated ordered `attachment` multipart parts and `X-Taksula-Attachment-Protocol: 5`; the Worker preserves the v4 16 MiB early body bound, validates each file (up to 16 MiB) and enforces a v5 aggregate attachment cap of 24 MiB and multipart ceiling of 24 MiB + 16 KiB. Android's composer now allows up to three ordered attachments and sends v5 even for a single attachment; existing older v4 clients remain compatible. Text-only v2/v3 and every `/continue` remain JSON. The Worker forwards accepted images/PDFs as one ordered Responses USER input and, in Phase A1 v5, allows validated text-like parts as ordered untrusted `input_text`. Missing protocol markers remain pinned to grouped v2 compatibility, and explicit v1 remains the legacy single-query contract.

Proxy checks require Node.js 22:

```bash
cd proxy
npm ci
npm run typecheck
npm test
```

For later Cloudflare repository setup, use `proxy` as the root directory and `towarownik-proxy` as the Worker name. Future secret names are documented in `proxy/README.md`; no secret is required for `/health`.

Phase A1 adds v5 transport and Phase A2 enables Android picker, private import and history for strictly allowlisted `.txt`, `.md`, `.csv`, `.json`, `.xml`, `.yaml`, `.yml`, `.log`, `.ini`, and `.conf` data. These are validated UTF-8 and sent as untrusted `input_text` with sanitized filenames, up to 1 MiB per text file and 1 MiB total across all text attachments in one v5 turn, within existing multipart bounds; image/PDF behavior and Room v12 schema remain unchanged. Android validates text before pending publication, supports mixed files, and shows distinct TEXT document cards without exposing contents. XLS/XLSX/DOCX/ZIP are still unsupported.

## AI usage baseline and local budget

Every successful OpenAI Responses API call may carry a bounded usage object from the Worker to Android: model, START/MESSAGE/CONTINUE request type, input/cached/output/reasoning/total token counts, estimated USD cost, and pricing version. Missing or malformed usage never invalidates an otherwise valid advisor answer. Each successful paid response is observed immediately, so usage from an earlier response remains counted even if a later local tool or continuation fails.

Current production advisor model is `gpt-6-luna` with low reasoning. Its server-controlled pricing configuration is versioned as `openai-gpt-6-luna-2026-09-27-v1`: USD 0.10/M ordinary input, USD 0.01/M cached input, USD 0.125/M cache-write input, and USD 0.50/M output. Requests above 272,000 input tokens use the model's long-context pricing for the full request: 2× all input-side rates and 1.5× output. Reasoning tokens are reported as an output detail and are never charged a second time. Android persists cumulative operational totals locally with decimal money arithmetic and keeps model totals separate for later comparisons.

**Settings → Użycie AI / AI usage** shows the latest measured model, tracking start, request/turn/tool-assisted-turn counts, ordinary usage details including cached/cache-write tokens, and estimated cost. Before measured usage there is no Android-side model guess. If one or more responses are unpriced, the UI explicitly reports their count and labels the accumulated priced spend as a known minimum rather than a complete total. PLN is derived only when an official NBP USD/PLN rate is available; the rate is refreshed on usage-screen demand with a 24-hour local cache. On NBP failure, the last cached rate remains dated and usable, otherwise PLN is simply unavailable. Advisor requests never depend on NBP.

The optional **Taksula AI budget** is user-configured local state, not OpenAI account-balance or account-credit data. Setting a remaining USD budget records the current cumulative Taksula spend as its baseline. Estimated remaining budget subtracts only later priced Taksula spend; if required cost data is unavailable, the app does not guess. Crossing below USD 1 produces one warning and re-arms only after the configured budget is reset/increased back to at least USD 1.

The GPT-5.6 Luna measurements remain historical baseline data in the same per-model usage store. The advisor receives compact verified product context from Android's active provider integration. Ordinary general technical knowledge remains allowed while SKU-specific/current-branch claims stay under the verified local-provider trust hierarchy. Selective web search remains supplemental to that local authority.

## Final Taksula advisor behavior

Taksula is a practical in-store product and technical advisor for retail and wholesale staff, with provider-specific behavior for OBI and KWANT. It uses normal model knowledge for general technical explanation, installation principles, material/tool selection, compatibility reasoning, troubleshooting, and deciding which product categories are relevant to a customer's job; verified provider data is not required for every general technical statement.

Taksula follows Advisor Product Contract v1: advisor first for ordinary technical questions, while clear intent for a concrete product, recommendation, assortment option, price/stock, or product identifier proactively uses the current conversation provider without requiring a separate “sprawdź w markecie” turn. Ordinary technical and sales-advice questions use normal model knowledge when sufficient and do not automatically trigger a lookup merely because a product category can be inferred. Product selection follows the shared three-state model: unknown product category -> one concise clarification and no local lookup; known category with a decision-critical variant still unknown -> one concise clarification or actionable resolution step plus safe same-turn provider browsing of plausible candidates, without claiming a correct match; sufficiently specified -> immediate provider verification without unnecessary clarification. Provider search is never used to infer the missing decision-critical parameter.

Job/project and "what do I need?" questions are understood first. Once sufficiently clear, Taksula gives a small essentials-first practical answer and distinguishes optional convenience items without automatically turning the request into a store shopping list. It uses the current provider when the user clearly asks for concrete products or recommendations, or for a complete verified store kit; such a kit can be batched efficiently without per-category confirmation or an exhaustive list.

Explicit assortment/browse, current price/stock/availability, direct provider-identifier verification (for example OBIK, article number, or EAN where supported), sufficiently specified concrete product/recommendation intent, and explicit verified provider-kit requests use the current provider lookup immediately. Complements are restrained and are mentioned proactively only when they materially help correctness, compatibility, safety, or avoidance of obvious failure. Android still independently enforces `MAX_LOCAL_TOOL_CALLS_PER_TURN = 3` as a hard safety fallback, and `local_tool_limit_reached` remains graceful if reached.

Availability states stay distinct: `stock = 0` is confirmed unavailable in that store; `stock = null` is unknown; grouped `not_found` means no verified matching result; grouped `unavailable` means retrieval could not establish the fact. A zero-stock/not-found requested item should not end at a bare "brak": when practical Taksula may verify a reasonable substitute in the current store and may offer another-market checking, but another market can be queried only when Android deterministically resolves the current user message against the active provider’s real branch directory (an explicit supported number still works). Ambiguous or unknown locations fail closed, and Taksula never invents another branch or claims cross-market availability without verification.

For a specific product or current branch fact, freshly verified local-provider data remains authoritative for product identity, selected-branch stock, price scope/value, and supplied product facts. Missing SKU-specific dimensions, materials, compatibility, certifications, applications, parameters, or limitations must not be invented. `productRefs` resolve only against current-turn Android snapshots: OBI v2 uses `(storeNumber, obik)`, while provider-aware v3/v4 uses `(providerId, branchId, productId)`.

The existing model (`gpt-6-luna`), low reasoning effort, multi-query bounds, grouped results, partial-group failure behavior, structured `{text, productRefs}` answer, current-turn store authorization, web-search policy, and Android snapshot/card trust boundary remain unchanged.

## Selective web search and citations

The Worker declares two tool classes to the Responses API: the protocol-appropriate local application function (`find_obi_products` for OBI v2 or `find_products` for provider-aware v3/v4) and the built-in `{ "type": "web_search" }`. Tool choice is automatic and each Responses request sets `max_tool_calls: 1` for built-in tools. Android's independent maximum of three local provider-function calls per USER turn is separate and unchanged.

Search is intentionally selective. Ordinary technical questions should use model knowledge without browsing when that is sufficient. A focused web lookup is appropriate when the user explicitly requests online/current external verification or when a needed SKU-specific technical property is missing from verified provider product data. Broader optional research should not trigger reflexive browsing. Current provider stock, price, selected-branch availability, and trusted product cards still come only from Android local provider verification and can never be overridden by web results.

For SKU-specific technical evidence, the instruction hierarchy prefers official manufacturer pages, manuals/datasheets, then authoritative technical/industry and reputable specialist sources; retailer pages are secondary and community sources are treated as experience/opinion. Searched page content is untrusted external data, never instructions for Taksula.

Only actual OpenAI `url_citation` annotations become user-visible sources. The proxy normalizes at most six stable-order, deduplicated HTTPS links with titles <= 200 characters and URLs <= 2048 characters. When OpenAI's real annotation offsets can be mapped exactly through the structured JSON `text` string, the normalized source also carries that bounded answer span; otherwise the span remains unknown rather than guessed. Android remaps safe spans through its display-text normalization, persists nullable spans with the source in Room (the source relation was introduced in v4; current schema is v11), renders a clickable inline citation marker at the supported fragment, and keeps the compact clickable source list as fallback/navigation. Raw search calls, queries, results and arbitrary metadata are never forwarded. Historical messages migrate with zero sources, and source rows cascade with their message/conversation. `productRefs` remain limited to current-turn Android-verified provider snapshots; a web-only product never becomes a verified card.

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

Room schema v12 stores provider-owned conversation context, rendered USER/ASSISTANT messages, verified product snapshots, normalized web sources, advisor search actions, attachment metadata, and nullable Advisor trace correlation:

- conversation: id, title, createdAt, updatedAt, nullable lastResponseId, draft, legacy storeNumber, providerId, branchId;
- message: id, conversationId, role, text, createdAt, nullable advisorTraceId;
- message product: messageId, position, providerId, productId, branchId, legacy OBI identity where applicable, articleNumber, name, nullable selected-branch stock, nullable centralStock, lossless decimal price text, trusted productUrl, nullable trusted imageUrl, verifiedAt;
- message source: messageId, position, bounded title, normalized HTTPS URL, nullable citation span;
- message search action: messageId, position, exact advisor query, storeNumber, reportedTotalCount;
- message attachments: 0–3 ordered rows per USER message with bounded metadata and opaque app-private localIds; attachment bytes stay outside Room;
- child rows cascade with their message/conversation.

Schema v1 upgrades non-destructively through explicit migrations to v12. The later migrations add provider/branch ownership (v7), provider-owned product identity (v8), central stock (v9), message attachments (v10), and nullable `messages.advisorTraceId` (v11). Historical rows retain safe defaults/nulls rather than being reinterpreted. Search actions and trace IDs are local operational metadata and are not model instructions.

No API keys, app bearer tokens, provider HTML/cookies, parser internals, raw OpenAI responses, or reasoning data are persisted.

History search is entirely local and performs case-insensitive SQLite phrase matching against conversation titles and USER/ASSISTANT message text. No proxy, OpenAI, embeddings, or network request is involved.

A first user send creates the conversation and derives a bounded local title from that message. Follow-up turns use the stored final `lastResponseId`. Only the final answer response ID advances conversation context; transient tool-call IDs are never stored.

If a request is interrupted after the USER message was written but before an ASSISTANT completion, the trailing USER message is removed during recovery and its text becomes the editable draft. No paid request is repeated automatically.

## Conversation lifecycle

A conversation is intended to represent one customer case. Start **Nowa rozmowa** for a new customer or a new problem; follow-up questions about the same case stay in the existing conversation. This is a product convention, not a technical length limit.

On normal app startup, local-only cleanup deletes conversations with `updatedAt < now - 30 days`. A conversation exactly at the cutoff is retained. Message rows disappear through the existing Room cascade. Cleanup does not call the proxy, OpenAI, or OBI and does not use WorkManager or a background scheduler.

Each history row also exposes **Usuń rozmowę** with explicit confirmation. Deleting an active conversation cancels its active advisor request, invalidates stale callbacks, removes the local conversation/messages, and returns the advisor surface to a fresh empty chat.


## Advisor verified product cards

Final model answers use a strict protocol-shaped `{text, productRefs}` result. OBI v2 references contain `{storeNumber, obik}`; provider-aware v3/v4 references contain `{providerId, branchId, productId}`.

`productRefs` are selection/order hints only. During one USER turn Android retains verified snapshots under provider-owned composite keys. Final references resolve only against those current-turn snapshots; unknown or mismatched references are ignored and never trigger a lookup.

Card identity, branch/store, selected-branch stock, optional central stock, price scope/value, URL, image metadata, and verification time come from the retained Android snapshot. OpenAI cannot create or overwrite those facts. The selected snapshots are committed atomically with the ASSISTANT message and final response ID, then displayed as historical point-in-time facts with their verification timestamp. Reopening history does not refresh them.

## Provider branch routing

A saved conversation owns one WorkingProfile `(providerId, branchId)`. The active provider's real `ProviderBranch` directory is the only branch namespace considered for that turn.

Android's shared `BranchResolver` authorizes one-off branch changes from the CURRENT USER message only. Exact supported branch IDs remain valid. Natural city/name/street/address matching participates only when the message contains explicit branch/store/location intent; incidental city or street words do not authorize a switch. Ambiguous or unknown explicit locations fail closed.

The model cannot authorize a cross-branch lookup by inventing `storeNumber`, `branchId`, or `requestedBranch`. A uniquely resolved one-off branch rewrites only that local tool call; it never mutates the persisted WorkingProfile. OBI and KWANT directories remain isolated and there is no cross-provider fallback.

OBI retains three-digit market IDs and grouped v2 semantics for text-only turns. KWANT uses its provider-owned branch IDs and provider-aware v3/v4 semantics. Current selected-branch availability always comes from fresh provider verification.

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
