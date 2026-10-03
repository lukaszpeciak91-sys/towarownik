# Architecture

## Goal

Taksula should remain a small native Android application. Its intended data flow is:

```text
UI / Compose
    ↓
Repository / domain layer
    ↓
HTTP/session transport
    ↓
OBI-specific payload parser
```

The product lookup core implements repository, HTTP/session transport, and OBI-specific parsing. The unified search controller runs blocking repository work on `Dispatchers.IO` and exposes only application UI state to Compose.

## Boundaries

- **UI / Compose** renders state and reports user intent. It must not issue HTTP requests or understand OBI payload details.
- **Repository / domain layer** coordinates search rules and exposes application-oriented results without leaking transport or page-format details into the UI.
- **HTTP/session transport** will retrieve public data and report transport outcomes. It must not interpret OBI payloads.
- **OBI-specific payload parser** will translate retrieved payloads into validated data. Parsing failures must be explicit; missing or malformed values must never be invented.

Transport and parsing must remain isolated from the UI. An OBI website change should require changes in its transport/parser boundary and tests, not a UI rewrite.

## UI localization boundary

Android presentation text uses normal platform resources: Polish is the default `app/src/main/res/values/strings.xml` set and English lives in `app/src/main/res/values-en/strings.xml`. Compose resolves current UI chrome through `stringResource(...)` and formatted resource placeholders.

Application/controller state remains locale-independent. Advisor and product-search failures are represented by stable error enums and are translated only at the Compose boundary; the manual-search saver stores the enum value rather than rendered localized text. Persisted conversations, USER/ASSISTANT role constants, product facts, OBIK/store identifiers, URLs, API paths, JSON fields, tool names, OpenAI schema, database schema, and diagnostic internal traces remain technical contract data and are not localized.

Locale selection uses AndroidX AppCompat per-app locales. `MainActivity` is an `AppCompatActivity`; selecting Polski or English calls `AppCompatDelegate.setApplicationLocales(LocaleListCompat...)`. AppCompat's `autoStoreLocales` metadata provides supported persistence below Android 13, while Android 13+ uses the platform per-app locale service and the manifest `localeConfig` advertises only `pl` and `en`. No custom locale Context wrapper or SharedPreferences language store exists.

The UI locale remains independent from advisor/model language. No locale field is added to proxy requests or persisted conversations, and existing USER/ASSISTANT text is never translated as a side effect of changing Settings.

## Problem reporting boundary

Problem reporting is local and user-controlled. There are two entry points: an exact persisted ASSISTANT-message report from the advisor transcript and a general report from Settings. The UI carries a nullable persisted message ID only so the report flow can re-resolve the authoritative Room message before generation. Web-source persistence is independent of reporting; normalized sources are not automatically copied into problem-report evidence.

A contextual report resolves the requested conversation/message from `ConversationRepository`, confirms that the message belongs to that conversation and has role `ASSISTANT`, and reads its persisted verified-product snapshots. With conversation inclusion OFF (the default), no unrelated transcript is included. With inclusion ON, context is sliced from the beginning through the reported response only. General reports include the current persisted conversation only after explicit opt-in. Draft text is not part of report evidence.

Report generation never calls the proxy, OpenAI, OBI repositories, or the live diagnostic probe. If the existing OBI recorder is already enabled and has sanitized captured operations, the form may expose those diagnostics as a separate opt-in checkbox. The checkbox defaults OFF, and the existing public `report()` output is appended only when the user explicitly selects it. Otherwise the report records that diagnostics were not included.

The report formatter receives only bounded technical metadata, persisted message/product evidence, the stable report category, and the user's description. It deliberately has no access to `lastResponseId`, OpenAI/tool IDs, secrets, raw responses, cookies, HTML/Nuxt, accounts, network address, or location.

TXT files are UTF-8 and temporary under `cacheDir/reports/`. Before creating a new file, previous files in that dedicated directory are removed. A non-exported `FileProvider` exposes only `reports/`; sharing uses `ACTION_SEND` + `EXTRA_STREAM` + temporary read permission and the Android chooser. `ActivityNotFoundException` while opening the chooser is handled locally: the fresh file is deleted and a bounded UI error is returned. No report is auto-sent, persisted in Room, or uploaded to a report backend.

## AI assistant boundary

The Android app now exposes two separate top-level paths. WYSZUKIWARKA continues to use the existing local OBI flow directly and never invokes the proxy. DORADCA uses this assistant path:

```text
User / Compose
    ↓
AI interaction controller
    ↓
Cloudflare proxy
    ↓
OpenAI
```

The Cloudflare Worker is the active credential and OpenAI mediation boundary for advisor requests. The OpenAI API key exists only on the proxy side. The Android app must never embed it.

When the model requests the single high-level local OBI tool, Android owns execution:

```text
AI requests local OBI tool
    ↓
existing Android OBI repository/search/lookup
    ↓
requested-store verified structured result
    ↓
compact result returned to AI
```

`find_obi_products(storeNumber, queries[])` is executed by Android using the existing `ProductSearchRepository` and sequential exact `ProductLookupRepository` lookups. One invocation targets one store, accepts 1–5 query groups, and enforces `sum(query.limit) <= 5`, so a batch can cover several customer-kit categories without increasing the existing maximum of five exact product lookups per local call. Results remain grouped by requested query with bounded `verified`, `not_found`, or `unavailable` status. A failed group never discards successful verified snapshots from other groups; no failure is converted into a fabricated product. Only one store context plus grouped verified bounded records `{obik,name,brand?,shortDescription?,technicalFacts[],stock,price}` are sent back to the proxy. The proxy does not scrape OBI, does not contain an OBI parser, and does not become authoritative for OBI data. Android owns the canonical store allowlist and validates membership before any OBI HTTP request.

OpenAI must receive only compact structured results produced by the app. OBI HTML, Nuxt payloads, cookies, and parser internals must not be forwarded to OpenAI or moved into the proxy.

The proxy now exposes a public `GET /health` plus authenticated `POST /v1/agent/start`, `POST /v1/agent/message`, and `POST /v1/agent/continue`. The AI endpoints require the shared Internal-Testing `TOWAROWNIK_APP_TOKEN`; the OpenAI credential remains Worker-only as `OPENAI_API_KEY`.

The Worker calls the OpenAI Responses API with a centralized `gpt-6-luna` configuration, low reasoning effort, the final server-controlled Taksula advisor instructions, a bounded output budget, the strict application function `find_obi_products(storeNumber, queries[])`, and the Responses built-in `{type:"web_search"}`. Tool choice is automatic and `max_tool_calls=1` bounds built-in use per Responses request; Android independently retains a three-local-function-calls-per-USER-turn infrastructure safety guard.

When the model returns that function call, the Worker validates the tool name and arguments and returns a normalized `tool_request` envelope to Android. Android executes the existing OBI search/exact store-`075` lookup and later sends only the compact verified result to `/v1/agent/continue`. The Worker continues with `previous_response_id` and a matching `function_call_output`, resending the stable server-controlled instructions/tool declaration. It does not store conversation state in Cloudflare storage.

The proxy normalizes OpenAI output to either `tool_request` or a structured final `answer`. Final answers are constrained by a strict JSON schema with exactly `text` and `productRefs` (maximum five `{storeNumber,obik}` references). The model supplies no card price, stock, name, URL, or verification timestamp. Raw Responses payloads, reasoning content/items, internal instructions, and upstream error bodies do not cross into Android. The only usage information crossing this boundary is a separately validated bounded metadata object containing model/request type, token counts, estimated USD cost, and pricing version; malformed telemetry is dropped without dropping the core answer/tool request.

On Android, `AdvisorProxyClient` is the isolated authenticated transport boundary. `AdvisorController` owns one USER turn and may execute at most three local OBI tool calls during that turn. A fourth requested batch performs zero OBI work, returns `local_tool_limit_reached`, removes `find_obi_products` from the final continuation, and finishes from already verified current-turn snapshots plus general guidance. The allowance resets for every new USER message. It never automatically retries a completed proxy request. `FindObiProductsTool` is the single Android OBI tool adapter; there is no generic agent/plugin framework or per-store tool class.

Conversation continuity is local-first:

```text
Room conversation.lastResponseId
        ↓
POST /v1/agent/message
        ↓
Responses API previous_response_id
        ↓
tool loop if requested
        ↓
FINAL answer responseId
        ↓
transaction: ASSISTANT message + replace lastResponseId
```

The first USER turn has no previous response and uses `/v1/agent/start`. Later turns use the last successfully completed final answer response ID. Tool request response IDs are used only transiently for `/continue`; call IDs are never persisted. A new conversation starts with no response ID.

Room schema v6 contains `conversations`, `messages`, `message_products`, `message_sources`, and the narrowly scoped `message_search_actions` relation. Version 6 adds only nullable `message_products.imageUrl` for trusted Android-local product presentation metadata. Conversation→message and all message-child foreign keys use CASCADE deletion. Explicit migrations preserve historical data; v4→v5 adds zero search-action rows for historical messages. Room stores rendered USER/ASSISTANT text, timestamps, local title, draft, nullable final `lastResponseId`, selected verified product snapshots, at most six normalized HTTPS citation links with optional validated answer-span indices, and local advisor search actions `{query,storeNumber,reportedTotalCount}` for an ASSISTANT message. Snapshot gross price is stored as decimal text to avoid floating-point precision loss; trusted product URLs/verification times remain local. Room still does not store secrets, OBI payloads, raw OpenAI/search responses, raw web-search queries or raw tool payloads, or reasoning data; the only persisted search query is the bounded local advisor query intentionally stored in `message_search_actions` for the “Zobacz więcej” action.

During a USER turn, every successful exact `LocalProduct` result yields two deliberately separate views: a compact tool result with one `storeNumber` plus `{obik,name,brand?,shortDescription?,technicalFacts[],stock,price}` products sent to OpenAI, and local-only verified snapshots `{storeNumber,obik,name,stock,grossPrice,productUrl,verifiedAt}`. `productUrl` and `verifiedAt` are never sent upstream. `AdvisorController` accumulates snapshots only inside the current `runTurn`, keyed by `(storeNumber, obik)`, and resolves final model-selected `productRefs` only against that set. Unknown references are ignored; they never cause a lookup. Requested ordering is retained and duplicate composite references cannot create duplicate cards. Broad advisor searches also have a separate Android-only metadata path: when a query uses `limit > 1` and OBI's `reportedTotalCount` exceeds the bounded candidates requested/returned, `FindObiProductsTool` emits an `AdvisorSearchAction`. `AdvisorController` deduplicates these actions per USER turn by store + normalized query. They are never added to the Worker continuation or model context and remain independent of verified snapshots/productRefs. Exact product parsing may additionally attach one nullable `primaryImageUrl` to the local snapshot path. The parser reads only the first structured Product JSON-LD image from the already-downloaded product page and accepts it only when it is HTTPS on `bilder.obi.pl`. This URL is never copied into `AdvisorVerifiedProduct` or the Worker protocol.

A trailing USER without a committed ASSISTANT means the turn was interrupted. Recovery removes only that trailing USER, restores its text to `draft`, and leaves the prior final `lastResponseId` unchanged. No proxy/OpenAI request runs during recovery. Completed assistant text, selected verified snapshots, and the final response ID are committed in one Room transaction, so a failed/interrupted/stale turn cannot leave orphan product snapshots.

Conversation history search is local SQL substring matching over title and message text. It has no AI/network dependency. Conversations are ordered by `updatedAt` descending. A conversation represents one customer case: a new customer or new problem should normally start with “Nowa rozmowa”, while follow-up questions for the same case continue in place; this is not enforced as a hard conversation-length limit.

On normal app startup, Room deletes conversations whose `updatedAt` is strictly older than `now - 30 days`; rows exactly at the cutoff are retained and message rows disappear through the existing CASCADE foreign key. Cleanup is local-only and has no WorkManager, proxy, OpenAI, OBI, or other network dependency. If a remembered active conversation was removed by retention, the app opens a fresh empty chat instead of restoring stale saved UI state.

Each history entry can also be deleted manually after confirmation. Deleting the active conversation first invalidates the advisor generation token and cancels/joins its active request, then removes the Room row and returns to a fresh empty chat. Final callback handling checks the generation/conversation guard before database mutation, and the DAO completion transaction requires the conversation row to still exist, so a stale completion cannot recreate a deleted conversation.

Persisted product cards are historical point-in-time snapshots, not current OBI evidence. They render below the ASSISTANT message with their local verification timestamp and trusted persisted URL, with no proxy/OBI request merely to reopen history, restart the app, or open the drawer. A later USER question about current availability, stock, price, or currently suitable products must run `find_obi_products` again and receives independent current-turn snapshots.

On cold start the most recently updated useful retained conversation is restored when practical. Switching/new conversation cancels active work and generation-guards stale UI callbacks.



## Multi-store authorization boundary

The selected store is conversation state, not inferred location. New conversations start at `075`. For an existing conversation, the explicit UI selector persists `Conversation.storeNumber`; changing the selector never rewrites historical product snapshots. For a not-yet-persisted new conversation, selection remains transient until the first USER send creates the row.

At the beginning of each USER turn Android captures one immutable conversation-store value and the set of allowlisted exact three-digit store tokens literally present in that current USER message. A tool request is authorized only when its requested store equals the conversation store, or when the requested store is allowlisted and appears in that captured token set. Matching uses digit boundaries, so `1074` does not authorize `074`, and earlier USER turns do not authorize an alternate store in the current turn.

Authorization runs before the OBI tool adapter and before any OBI HTTP request. Unauthorized/unsupported stores produce bounded `store_not_authorized` continuation data for the advisor; Android never substitutes another store or fabricates an empty OBI result. The proxy validates exact three-digit shape but does not duplicate the full allowlist.

Room v2→v3 adds `conversations.storeNumber` and `message_products.storeNumber` with deterministic historical default `075`. Verified product identity is therefore `(storeNumber, obik)`, both in current-turn dedupe and persisted history.


## OBIK lookup flow

`ProductLookupRepository` accepts a seven-digit OBIK plus a supported `storeNumber` (default `075`) and validates the store against the canonical allowlist before HTTP. `ObiHttpClient` calls `/api/disc/store/change?storeNumber={storeNumber}&redirectUrl=/p/{OBIK}` with an in-memory cookie jar. OkHttp follows the normal redirect to the product route on that same client, so the response page was produced in the selected-store session. Live Android probing confirmed that this existing URL/redirect/session flow is valid, but OBI/CloudFront rejects the default native/non-browser User-Agent with synthetic empty 404 responses. Production OBI requests therefore apply one centralized browser-compatible HTML navigation profile: a fixed synthetic Android Chrome-style User-Agent, HTML Accept, and Polish Accept-Language. The UA is a compatibility string and does not represent the user's installed Chrome. No bootstrap request or canonical-product prelookup is performed. Non-2xx, empty, and transport responses become explicit unavailable results; the client does not retry.

`ObiPayloadParser` extracts the `__NUXT_DATA__` script as JSON and resolves Nuxt's flattened references. It selects only an object whose product identifier matches the requested OBIK. Product identity may be supplemented from Product JSON-LD; canonical-link markup is a URL fallback. It does not scrape visible price or availability text.

Current live OBI Nuxt payloads wrap product references in `Ref`/`ShallowRef` entries. The decoder unwraps only these confirmed wrapper types while retaining the existing flattened-reference rules. Product identity accepts the current `skuId` field as well as legacy identifiers.

For the current contract, the matched product's selected store is `product.store.information.storeId` (or `storeNumber` if present). Local stock comes only from `product.store.articleData.stock` and local price only from `product.store.articleData.pricing.grossPrice`. Seller stock/pricing and `fallbackPricing` are never substitutes. The current `articleEanEcms` field may decode to a one-element array containing the EAN; the parser accepts either a direct scalar or exactly one non-blank scalar value, and does not guess when multiple values are present. The historical sibling `selectedStore` + product-local `stock`/`pricing` shape remains a compatibility fallback for deterministic legacy fixtures. A parsed integer stock of `0` is confirmed zero. An absent, negative, or unparseable stock is `null` (unknown), and absent/unparseable local price is also `null`.

## Unified search flow

Input classification is explicit: exactly seven digits are an OBIK; numeric GTIN/EAN lengths 8, 12, 13, or 14 are EAN input; other non-blank input is text; blank or unsupported all-numeric lengths are invalid.

There are now two intentionally separate search-result capacities over the same OBI transport/parser rules. The existing `ProductSearchRepository.search()` path remains capped at five candidates and is the only search path used by the advisor/local AI tool. The human-only `searchManual()` path asks the parser for at most 25 recognized product links from the same already downloaded HTML and also preserves OBI's reported total result count. The UI reveals those parsed candidates in five-item chunks. The advisor parser now preserves the same OBI-reported total alongside its bounded five-candidate subset. That count is trusted only as a search-result count, never as store stock or verified availability. When eligible, the persisted advisor action opens the existing manual search with the exact historical query and store; manual search still loads at most 25 candidates and reveals them in five-item chunks. The reported total may be larger than the parsed candidate list; the UI never invents additional candidates and no OBI pagination HTTP contract is assumed.

OBIK continues to use direct exact product lookup without a candidate list, now against the active selected store. EAN and text queries use OBI's public `/search/{query}/` route. `ObiSearchParser` reads product links structurally, preserves their page order, deduplicates by OBIK, and returns at most five candidates. A canonical product URL is also accepted as a single search candidate when OBI redirects a search directly to a product page.

Text search always requires user selection before product lookup. Multiple EAN candidates also require selection. A single EAN candidate is opened automatically only after the existing product payload confirms that its EAN equals the user's query; otherwise the candidate remains selectable instead of being guessed.

An explicit empty-search state or ordinary HTTP 404 maps to not found. A narrow transport safeguard excludes the confirmed infrastructure signature—HTTP 404 with an empty body, `Server: CloudFront`, and `x-cache` containing `Error from cloudfront`—from business not-found classification; that case follows the existing server/network failure path. Unrecognized or changed search structure maps to a data failure, never to not found. Selecting a candidate runs exact lookup for the active selected store, so local stock and local gross price keep the same data rules.

## Temporary OBI diagnostics

A temporary in-app engineering diagnostic mode observes the existing OBI integration without changing its URLs, headers, redirect policy, cookie behavior, or parsing decisions. It is OFF by default and is opened from **Drawer → Settings → Diagnostics**. The former hidden long-press on the app title has been removed. State and history are process-session only; no persistence dependency is used.

`ObiDiagnosticRecorder` is bounded to the last 10 operations. An OkHttp network interceptor observes actual request headers, redirect hops, safe response metadata, and cookie names. All recorded URLs are sanitized: scheme/host/path are retained, only explicitly safe query values such as the requested `storeNumber` remain visible, and other query values are replaced with `REDACTED`. Cookie values are inspected only transiently to classify per-hop store evidence as `true`, `false`, or `unknown`, then discarded; they are never stored or printed. Response bodies are never persisted. Successful responses are reduced immediately to safe signatures, while final 4xx/5xx responses use a bounded diagnostic preview for the same signatures. The reported body-size field is `decodedBodyUtf8Bytes`, meaning UTF-8 bytes of the decoded diagnostic text, not raw HTTP payload bytes.

Product and search parsers append diagnostic stages while keeping their existing decisions unchanged. Repositories append error-classification traces and then finalize each diagnostic operation. Diagnostic mode is infrastructure for contract discovery, not product behavior.

Deterministic fixtures and CI prove code behavior against known inputs; they do not prove compatibility with live OBI. Live diagnostic reports must be reviewed before changing transport, session, redirect, parser, or not-found assumptions.

## UI and configuration

The application uses one AppCompat-backed Compose activity and state-based top-level surfaces; Navigation Compose is intentionally not introduced. The surfaces are Advisor, manual OBI search, Settings, and Diagnostics. The default surface is the advisor chat shell. Its top bar has a modal drawer action, centered Taksula title, explicit new-case action, and quick access to the independent full-screen Wyszukiwarka OBI. The drawer contains “Nowa rozmowa”, local persisted history/search, per-conversation confirmed deletion, and a visually separated Settings action pinned at the bottom regardless of history/search state. Opening Settings changes only the surface and closes the drawer; it does not reset the active conversation/draft or initiate proxy/OBI work.

Settings returns directly to Advisor. Diagnostics is entered only from Settings and returns to Settings. Settings contains General/Language, Help/Diagnostics plus disabled future Report problem, and About with resource-based `app_name`, current versionName/versionCode, plus a disabled future Privacy policy row. The placeholders are deliberately non-functional and contain no invented endpoint, URL, or legal text.

The advisor is now genuinely multi-turn within one local customer conversation. After a completed ASSISTANT answer the composer is enabled again; the next USER message continues from that conversation's stored final response ID. Submitted/completed messages persist with createdAt timestamps and keep the existing local HH:mm presentation. Simple Markdown markers are still normalized before storage/display. Starting or opening another conversation cancels active work, recovers any interrupted trailing USER to the old conversation's draft, and protects the new selection from stale callbacks.

The Wyszukiwarka OBI has its own Back surface and keeps advisor UI state intact. Visible candidates still use the existing exact-verification enrichment path; if that already-fetched exact product carries a trusted primary image URL, the enriched result card renders the same local thumbnail without any image-specific OBI request. It now has an explicit manual-search store context separate from the conversation-selected store: normal top-bar entry initializes it from the current conversation, while a persisted advisor “Zobacz więcej” action opens with its historical query/store and automatically starts the same `ManualSearchController` flow without mutating the conversation store. Completed manual-search state, typed search query, advisor draft/messages, and selected top-level surface use Compose saved state where practical so rotation does not unnecessarily erase the current screen. A reusable verified-product UI model/card receives only trusted Android-side exact lookup data and opens the existing `LocalProduct.productUrl` through the normal external browser intent.

`TOWAROWNIK_APP_TOKEN` is injected at Android build time through `BuildConfig`. Missing token configuration keeps compilation and ordinary search working; DORADCA fails locally before any network call. The signed Play workflow receives the token only from the matching GitHub Actions secret. The static token is only an Internal Testing abuse barrier and is extractable from an APK/AAB; it is not strong device authentication.

The application uses the normal Android resource system. No orientation is locked, so the UI must continue to adapt cleanly to portrait and landscape sizes. Navigation, dependency injection, persistence, and other frameworks should be added only if a concrete feature requires them.


## Multi-turn proxy continuation

The Worker exposes authenticated turn endpoints carrying the conversation-store snapshot: `/start` receives `{message,storeNumber}`, `/message` receives `{previousResponseId,message,storeNumber}`, and `/continue` retains the same `storeNumber` alongside tool output. Android cannot override model, instructions, tools, reasoning effort, token budget, or OpenAI URL. The Worker resends the stable server-controlled instructions/tool declaration and passes `previous_response_id` plus only the new user message to the Responses API.

Using `previous_response_id` avoids manually sending the complete local transcript on each turn, but previous context tokens in that chain are still billed as input tokens. No summarization or compaction is implemented yet.

Advisor transport is explicitly versioned at the Android/Worker boundary. Current Android sends `protocolVersion: 2` on START, MESSAGE, and CONTINUE; v2 is the grouped `find_obi_products(storeNumber, queries[])` contract. The Worker also retains an explicit v1 single-query contract for controlled compatibility, but an absent `protocolVersion` is deliberately pinned to v2. This is required because the already-deployed grouped Android client predates the marker and is therefore also unversioned; absence cannot safely distinguish it from still older single-query builds. Future protocol evolution must not reinterpret an unversioned request as v1. Pre-versioned single-query Android builds are not recovered by this mechanism and must update rather than receive an incompatible guessed response shape.

Server-side instructions define Taksula as a practical in-store home-improvement retail product/technical advisor following Advisor Product Contract v1. Normal model knowledge answers ordinary technical and sales-advice questions when sufficient; OBI is not called merely because a product category can be inferred. Ambiguous product-selection requests ask one concise decision-critical clarification before `find_obi_products`; job/project requests similarly clarify materially different interpretations first and then give essentials-first advice without automatically becoming an OBI shopping list. The local tool is used immediately for explicit assortment/browse, direct current price/stock/availability or OBIK verification, sufficiently specified selection with an explicit selected-market request, and explicit sufficiently specified verified store kits. Those store kits may batch related categories efficiently, while complements remain restrained and are searched only when requested or materially required by an explicit verified kit. Android independently retains `MAX_LOCAL_TOOL_CALLS_PER_TURN = 3` as a safety guard and resolves exhaustion through the existing graceful `local_tool_limit_reached` path. Availability semantics remain strict: stock `0` is confirmed unavailable in that store, null stock is unknown, `not_found` is no verified match, and `unavailable` is retrieval failure. A zero-stock/not-found requested item may lead to a verified current-store substitute; another store may be queried only when its exact supported three-digit number appears literally in the current USER message. Verified tool facts remain authoritative for SKU/current-store facts, missing SKU-specific facts remain unknown, and historical stock/price statements are context only, never current authority.


## AI usage observability v0.1

- The Worker reads Responses API `usage` independently from answer/tool normalization. START, MESSAGE, and CONTINUE are explicit request types. Usage failure is non-critical.
- Current pricing is centralized server-side under version `openai-gpt-6-luna-2026-09-27-v1`: USD 0.10/M ordinary input, USD 0.01/M cached input, USD 0.125/M cache-write input, USD 0.50/M output. Ordinary input is `input - cached - cacheWrite`; above 272,000 input tokens the full request uses 2× input-side rates and 1.5× output. Reasoning tokens are output detail only and are not double-counted. Historical GPT-5.6 Luna totals remain in the same per-model local usage state.
- Android records every successful proxy/OpenAI response immediately, plus USER-turn and first-tool-request counters. Cached and cache-write token totals remain separately auditable. Cumulative money is persisted as decimal text/BigDecimal rather than binary floating-point. Totals remain grouped by model; the UI model label comes from measured usage, not a duplicated Android model constant. Unpriced responses remain counted and cause the displayed known cost to be marked as a lower bound.
- AI usage state contains operational numeric metadata only; it stores no conversation text, prompts, tool queries/results, response/call IDs, raw reasoning, or secrets.
- NBP USD/PLN lookup is an AI Usage UI concern only. A successful rate stores rate, effective date, and refresh time; a 24-hour cache prevents per-turn calls. Failure returns stale dated cache when present and otherwise leaves PLN unavailable.
- User-configured Taksula budget stores a starting remaining USD amount plus cumulative-cost/unpriced-request baselines. It is explicitly not OpenAI account balance data. A below-USD-1 warning is edge-triggered and re-armed only by a budget reset/increase above the threshold.


## Richer verified OBI product facts v0.1

- Enrichment remains inside the existing exact product-page lookup. The same browser-compatible OBI response is decoded once; there is no second product fetch and no server-side OBI parser.
- Live contract evidence across OBIK 3496072, 6743009, and 7156243 confirmed product-level paths `brand.name`, `productDescription`, `productOverview[]`, `technicalData.productDetails[] {key,value}`, and `technicalData.dimensionsAndWeight[] {key,value}` on the selected-store product object.
- `LocalProduct` carries optional brand, optional normalized short description, and bounded `TechnicalFact(label,value)` items for the active exact lookup. Room snapshots remain unchanged and continue to persist store, OBIK, name, stock, price, trusted URL, and verification time only.
- Model-context bounds are brand 80 chars, description 220 chars, six facts, 60-char labels, and 100-char values. These tighter limits preserve the existing 16 KiB continuation request budget at the five-product tool maximum.
- Optional enrichment is fail-soft. Malformed or absent descriptive subsections are ignored independently; OBIK identity and selected-store structure remain required exactly as before.
- Store authority is unchanged: stock and gross price come only from `product.store.articleData`. Descriptive and technical facts are product-level data unless OBI itself explicitly states otherwise.
- Android serializes only bounded product context into `function_call_output`. Product URL, verifiedAt, EAN, raw HTML/Nuxt, cookies, diagnostics, and arbitrary JSON never cross to OpenAI. Final `productRefs` and the `(storeNumber, obik)` trust boundary are unchanged.


## Final Taksula advisor behavior v0.1

- Scope: product selection, building/finishing materials, tools, electrical/lighting, garden/home-improvement products, applications, installation guidance, compatibility, troubleshooting, alternatives, and helping retail staff answer customer questions.
- General technical knowledge is explicitly allowed. The advisor should answer first, remain practical/concise, ask at most one clarification when genuinely necessary, and continue helping even when a specific SKU detail is unconfirmed.
- Product-selection ambiguity is handled before concrete SKU choice: when a missing parameter materially changes correctness, compatibility, safety, or usefulness, Taksula asks one targeted clarification instead of guessing or searching merely to guess the missing parameter. When enough relevant detail is already present, it proceeds without unnecessary clarification.
- Assortment/browse intent is distinct from selection intent. Requests such as what is available, which variants/sizes exist, or to show options should use multiple verified results where useful rather than arbitrarily narrowing to the first result. If several verified products materially fit, Taksula must present/compare the useful alternatives or briefly explain why one was chosen; it must not silently imply that only one exists.
- `find_obi_products` exposes only a bounded subset (at most five verified products per batch). Browse/assortment answers must not describe that subset as the complete assortment unless completeness is independently verified; wording such as examples/among other found variants is preferred when completeness is unknown.
- Specific-product trust hierarchy: verified `find_obi_products` facts are authoritative for OBIK/name/store/stock/price and supplied product-page facts; model knowledge may explain general principles around them but may not fabricate absent SKU-specific properties.
- Rich OBI facts are selected for relevance rather than dumped or rewritten as marketing copy. Missing rich data means unknown, not negative.
- Clearly unrelated general-chat requests receive a short role redirect; borderline practical home-improvement topics remain in scope.
- Response language follows the user's current conversation language where practical. Android locale/persisted-history behavior remains unchanged.
- Structured output, productRefs, Android current-turn snapshot resolution, tool/product limits, multi-store authorization, GPT-6 Luna/low reasoning, and usage/cost accounting remain unchanged.
- Selective Responses `web_search` is now the only enabled OpenAI built-in tool; deep research, file search, image search, MCP, streaming, and background mode remain absent.


## Selective web search v0.1

- Search is supplemental, not a replacement for model knowledge or the Android OBI tool. General technical questions should normally use model knowledge; focused search is available for explicit relevant web requests, inherently current external information, or a missing important SKU-specific fact after OBI verification.
- Current OBI stock, price, store availability, and product-card eligibility remain Android-local authority. Search-visible OBI/manufacturer/retailer pages cannot override those facts, and web-discovered products cannot enter `productRefs`.
- Built-in configuration is `{type:"web_search"}`, `tool_choice:"auto"`, `max_tool_calls:1` per Responses request. Application function continuations still use `previous_response_id` and Android retains its three-local-function-calls-per-USER-turn infrastructure safety guard.
- Output normalization tolerates reasoning items, `web_search_call`, message items, and one application `function_call`. Only application function calls are returned as Android tool requests; raw web-search objects/actions/query metadata never cross the proxy boundary.
- Final web evidence is sourced exclusively from actual OpenAI `url_citation` annotations. Normalization accepts HTTPS only, deduplicates URL order, and caps six sources, 200-char titles, and 2048-char URLs. Android validates those bounds again.
- `message_sources` was introduced in Room v4; current Room v5 additionally adds only the local `message_search_actions(messageId,position,query,storeNumber,reportedTotalCount)` relation for advisor “Zobacz więcej”, with message FK cascade. Assistant text, verified product snapshots, sources, and final response ID commit in one DAO transaction. Historical v3 messages migrate with zero sources and history reopens citations without network access.
- Source UI is a compact clickable list below the assistant answer. Inline annotation offsets are intentionally not fabricated or remapped through the structured JSON text boundary.
- Web pages are untrusted external reference data. Server instructions reject page attempts to change role/tool/trust rules, disclose secrets, or exfiltrate unrelated conversation data.
- Usage telemetry adds `webSearchCalls`. The current pricing version adds USD 0.01 per completed search action to the existing token cost. Tool availability alone does not count as a call; unknown/unpriceable model usage remains unpriced.
- Existing advisor call timeout remains unchanged at 30 seconds because built-in search is limited to one action and no background/deep-research flow is introduced.

## Neutral product-provider foundation

The product layer now defines a minimal provider-owned identity boundary for future multi-provider work:

- `ProviderId` identifies a product source; the existing OBI Poland provider is `obi-pl`.
- `BranchId` is an opaque provider-owned branch identifier. The provider-neutral layer does not assume three digits or OBI store semantics.
- `WorkingProfile(providerId, branchId)` pairs the active provider and branch. The current default profile is `obi-pl` + the existing default OBI store `075`.
- `ProductRef(providerId, productId)` owns a product identifier explicitly, so the neutral boundary does not assume every provider uses OBIK.

`ProductProvider` intentionally exposes only the two operations already proven by the application: bounded product search and exact product lookup in a branch. `ObiProductProvider` is a thin adapter over the existing `ProductSearchRepository` / `ProductLookupRepository`; it translates between neutral provider types and the unchanged OBI-specific repositories, parsers, HTTP transport, error semantics, stock/price/image/URL fields, and current OBI search capacities. No OBI parser or network behavior is duplicated.

`ProductProviderRegistry` resolves providers by `ProviderId`. Production registers `obi-pl` and `kwant-pl`; unknown provider IDs fail explicitly and never fall back to another provider. KWANT `BranchId` is the public `department_stock_id`. The provider resolves the public branch directory metadata and builds the proven compact `departmentCookie` JSON object from `department_stock_id`, `department_stock_name`, `department_stock_postcode`, and `department_stock_street` before exact lookup.

Neutral product results distinguish price scope with `BRANCH` and `ONLINE`. Existing OBI exact prices map to `BRANCH`; KWANT's public storefront price maps to `ONLINE` and is not represented as a branch/counter/customer-specific price.

This is a foundation boundary only. Existing OBI-specific UI, conversation persistence, advisor tool contracts, `LocalProduct`, OBIK fields, and store-number flows remain intentionally unchanged in this PR. They should move behind the neutral boundary incrementally when the second provider is introduced, rather than through a broad rename/rewrite now.

