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

Problem reporting is local and user-controlled. There are two entry points: an exact persisted ASSISTANT-message report from the advisor transcript and a general report from Settings. The UI carries a nullable persisted message ID only so the report flow can re-resolve the authoritative Room message before generation; Room schema v2 is unchanged.

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
store 075 verified structured result
    ↓
compact result returned to AI
```

`find_available_obi_075(query, limit)` is executed by Android using the existing `ProductSearchRepository` and sequential exact `ProductLookupRepository` lookups. `NotFound` is a verified empty result; repository/transport/parser failure is not converted to an empty result. Only verified records `{obik,name,stock,price}` are sent back to the proxy. The proxy does not scrape OBI, does not contain an OBI parser, and does not become authoritative for OBI data. Existing Android OBI search, OBIK extraction, exact lookup, store `075`, stock, and local price remain the source of truth.

OpenAI must receive only compact structured results produced by the app. OBI HTML, Nuxt payloads, cookies, and parser internals must not be forwarded to OpenAI or moved into the proxy.

The proxy now exposes a public `GET /health` plus authenticated `POST /v1/agent/start`, `POST /v1/agent/message`, and `POST /v1/agent/continue`. The AI endpoints require the shared Internal-Testing `TOWAROWNIK_APP_TOKEN`; the OpenAI credential remains Worker-only as `OPENAI_API_KEY`.

The Worker calls the OpenAI Responses API with a centralized `gpt-5.6-luna` configuration, low reasoning effort, concise temporary developer instructions, a bounded output budget, and exactly one strict application-defined function: `find_available_obi_075(query, limit)`. No OpenAI built-in tools are enabled.

When the model returns that function call, the Worker validates the tool name and arguments and returns a normalized `tool_request` envelope to Android. Android executes the existing OBI search/exact store-`075` lookup and later sends only the compact verified result to `/v1/agent/continue`. The Worker continues with `previous_response_id` and a matching `function_call_output`, resending the stable server-controlled instructions/tool declaration. It does not store conversation state in Cloudflare storage.

The proxy normalizes OpenAI output to either `tool_request` or a structured final `answer`. Final answers are constrained by a strict JSON schema with exactly `text` and `productObiks` (maximum five seven-digit OBIKs). The model supplies no card price, stock, name, URL, or verification timestamp. Raw Responses payloads, reasoning items, token/usage metadata, internal instructions, and upstream error bodies do not cross into Android.

On Android, `AdvisorProxyClient` is the isolated authenticated transport boundary. `AdvisorController` owns one USER turn and may execute at most two local tool calls during that turn before terminating with a bounded error. The allowance resets for every new USER message. It never automatically retries a completed proxy request. `FindAvailableObi075Tool` remains the only Android tool adapter; there is no generic agent/plugin framework.

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

Room schema v2 contains `conversations`, `messages`, and `message_products`. Conversation→message and message→product foreign keys use CASCADE deletion. The v1→v2 migration creates only the new product table/index and preserves existing conversations/messages. Room stores rendered USER/ASSISTANT text, timestamps, local title, draft, nullable final `lastResponseId`, and selected verified product snapshots. Snapshot gross price is stored as decimal text to avoid floating-point precision loss; the trusted exact `productUrl` and `verifiedAt` are stored locally for historical display. Room still does not store secrets, OBI payloads, raw OpenAI responses, or reasoning data.

During a USER turn, every successful exact `LocalProduct` result yields two deliberately separate views: the existing compact `{obik,name,stock,price}` tool result sent to OpenAI and a local-only verified snapshot `{obik,name,stock,grossPrice,productUrl,verifiedAt}`. `productUrl` and `verifiedAt` are never sent upstream. `AdvisorController` accumulates snapshots only inside the current `runTurn`, deduplicates by OBIK with the latest exact lookup winning, and resolves the final model-selected `productObiks` only against that set. Unknown selections are ignored; they never cause a lookup. Requested ordering is retained and duplicate selections cannot create duplicate cards.

A trailing USER without a committed ASSISTANT means the turn was interrupted. Recovery removes only that trailing USER, restores its text to `draft`, and leaves the prior final `lastResponseId` unchanged. No proxy/OpenAI request runs during recovery. Completed assistant text, selected verified snapshots, and the final response ID are committed in one Room transaction, so a failed/interrupted/stale turn cannot leave orphan product snapshots.

Conversation history search is local SQL substring matching over title and message text. It has no AI/network dependency. Conversations are ordered by `updatedAt` descending. A conversation represents one customer case: a new customer or new problem should normally start with “Nowa rozmowa”, while follow-up questions for the same case continue in place; this is not enforced as a hard conversation-length limit.

On normal app startup, Room deletes conversations whose `updatedAt` is strictly older than `now - 30 days`; rows exactly at the cutoff are retained and message rows disappear through the existing CASCADE foreign key. Cleanup is local-only and has no WorkManager, proxy, OpenAI, OBI, or other network dependency. If a remembered active conversation was removed by retention, the app opens a fresh empty chat instead of restoring stale saved UI state.

Each history entry can also be deleted manually after confirmation. Deleting the active conversation first invalidates the advisor generation token and cancels/joins its active request, then removes the Room row and returns to a fresh empty chat. Final callback handling checks the generation/conversation guard before database mutation, and the DAO completion transaction requires the conversation row to still exist, so a stale completion cannot recreate a deleted conversation.

Persisted product cards are historical point-in-time snapshots, not current OBI evidence. They render below the ASSISTANT message with their local verification timestamp and trusted persisted URL, with no proxy/OBI request merely to reopen history, restart the app, or open the drawer. A later USER question about current availability, stock, price, or currently suitable products must run `find_available_obi_075` again and receives independent current-turn snapshots.

On cold start the most recently updated useful retained conversation is restored when practical. Switching/new conversation cancels active work and generation-guards stale UI callbacks.

## OBIK lookup flow

`ProductLookupRepository` accepts only a seven-digit OBIK and always requests store number `075` (OBI Nowy Sącz). `ObiHttpClient` calls `/api/disc/store/change?storeNumber=075&redirectUrl=/p/{OBIK}` with an in-memory cookie jar. OkHttp follows the normal redirect to the product route on that same client, so the response page was produced in the selected-store session. Live Android probing confirmed that this existing URL/redirect/session flow is valid, but OBI/CloudFront rejects the default native/non-browser User-Agent with synthetic empty 404 responses. Production OBI requests therefore apply one centralized browser-compatible HTML navigation profile: a fixed synthetic Android Chrome-style User-Agent, HTML Accept, and Polish Accept-Language. The UA is a compatibility string and does not represent the user's installed Chrome. No bootstrap request or canonical-product prelookup is performed. Non-2xx, empty, and transport responses become explicit unavailable results; the client does not retry.

`ObiPayloadParser` extracts the `__NUXT_DATA__` script as JSON and resolves Nuxt's flattened references. It selects only an object whose product identifier matches the requested OBIK. Product identity may be supplemented from Product JSON-LD; canonical-link markup is a URL fallback. It does not scrape visible price or availability text.

Current live OBI Nuxt payloads wrap product references in `Ref`/`ShallowRef` entries. The decoder unwraps only these confirmed wrapper types while retaining the existing flattened-reference rules. Product identity accepts the current `skuId` field as well as legacy identifiers.

For the current contract, the matched product's selected store is `product.store.information.storeId` (or `storeNumber` if present). Local stock comes only from `product.store.articleData.stock` and local price only from `product.store.articleData.pricing.grossPrice`. Seller stock/pricing and `fallbackPricing` are never substitutes. The current `articleEanEcms` field may decode to a one-element array containing the EAN; the parser accepts either a direct scalar or exactly one non-blank scalar value, and does not guess when multiple values are present. The historical sibling `selectedStore` + product-local `stock`/`pricing` shape remains a compatibility fallback for deterministic legacy fixtures. A parsed integer stock of `0` is confirmed zero. An absent, negative, or unparseable stock is `null` (unknown), and absent/unparseable local price is also `null`.

## Unified search flow

Input classification is explicit: exactly seven digits are an OBIK; numeric GTIN/EAN lengths 8, 12, 13, or 14 are EAN input; other non-blank input is text; blank or unsupported all-numeric lengths are invalid.

There are now two intentionally separate search-result capacities over the same OBI transport/parser rules. The existing `ProductSearchRepository.search()` path remains capped at five candidates and is the only search path used by the advisor/local AI tool. The human-only `searchManual()` path asks the parser for at most 25 recognized product links from the same already downloaded HTML and also preserves OBI's reported total result count. The UI reveals those parsed candidates in five-item chunks. The reported total may be larger than the parsed candidate list; the UI never invents additional candidates and no OBI pagination HTTP contract is assumed.

OBIK continues to use the direct store-`075` product lookup without a candidate list. EAN and text queries use OBI's public `/search/{query}/` route. `ObiSearchParser` reads product links structurally, preserves their page order, deduplicates by OBIK, and returns at most five candidates. A canonical product URL is also accepted as a single search candidate when OBI redirects a search directly to a product page.

Text search always requires user selection before product lookup. Multiple EAN candidates also require selection. A single EAN candidate is opened automatically only after the existing product payload confirms that its EAN equals the user's query; otherwise the candidate remains selectable instead of being guessed.

An explicit empty-search state or ordinary HTTP 404 maps to not found. A narrow transport safeguard excludes the confirmed infrastructure signature—HTTP 404 with an empty body, `Server: CloudFront`, and `x-cache` containing `Error from cloudfront`—from business not-found classification; that case follows the existing server/network failure path. Unrecognized or changed search structure maps to a data failure, never to not found. Selecting a candidate runs the existing store-`075` product lookup, so local stock and local gross price keep the same data rules.

## Temporary OBI diagnostics

A temporary in-app engineering diagnostic mode observes the existing OBI integration without changing its URLs, headers, redirect policy, cookie behavior, or parsing decisions. It is OFF by default and is opened from **Drawer → Settings → Diagnostics**. The former hidden long-press on the app title has been removed. State and history are process-session only; no persistence dependency is used.

`ObiDiagnosticRecorder` is bounded to the last 10 operations. An OkHttp network interceptor observes actual request headers, redirect hops, safe response metadata, and cookie names. All recorded URLs are sanitized: scheme/host/path are retained, only explicitly safe query values such as `storeNumber=075` remain visible, and other query values are replaced with `REDACTED`. Cookie values are inspected only transiently to classify per-hop store evidence as `true`, `false`, or `unknown`, then discarded; they are never stored or printed. Response bodies are never persisted. Successful responses are reduced immediately to safe signatures, while final 4xx/5xx responses use a bounded diagnostic preview for the same signatures. The reported body-size field is `decodedBodyUtf8Bytes`, meaning UTF-8 bytes of the decoded diagnostic text, not raw HTTP payload bytes.

Product and search parsers append diagnostic stages while keeping their existing decisions unchanged. Repositories append error-classification traces and then finalize each diagnostic operation. Diagnostic mode is infrastructure for contract discovery, not product behavior.

Deterministic fixtures and CI prove code behavior against known inputs; they do not prove compatibility with live OBI. Live diagnostic reports must be reviewed before changing transport, session, redirect, parser, or not-found assumptions.

## UI and configuration

The application uses one AppCompat-backed Compose activity and state-based top-level surfaces; Navigation Compose is intentionally not introduced. The surfaces are Advisor, manual OBI search, Settings, and Diagnostics. The default surface is the advisor chat shell. Its top bar has a modal drawer action, centered Taksula title, explicit new-case action, and quick access to the independent full-screen Wyszukiwarka OBI. The drawer contains “Nowa rozmowa”, local persisted history/search, per-conversation confirmed deletion, and a visually separated Settings action pinned at the bottom regardless of history/search state. Opening Settings changes only the surface and closes the drawer; it does not reset the active conversation/draft or initiate proxy/OBI work.

Settings returns directly to Advisor. Diagnostics is entered only from Settings and returns to Settings. Settings contains General/Language, Help/Diagnostics plus disabled future Report problem, and About with resource-based `app_name`, current versionName/versionCode, plus a disabled future Privacy policy row. The placeholders are deliberately non-functional and contain no invented endpoint, URL, or legal text.

The advisor is now genuinely multi-turn within one local customer conversation. After a completed ASSISTANT answer the composer is enabled again; the next USER message continues from that conversation's stored final response ID. Submitted/completed messages persist with createdAt timestamps and keep the existing local HH:mm presentation. Simple Markdown markers are still normalized before storage/display. Starting or opening another conversation cancels active work, recovers any interrupted trailing USER to the old conversation's draft, and protects the new selection from stale callbacks.

The Wyszukiwarka OBI has its own Back surface and keeps advisor UI state intact. Completed manual-search state, typed search query, advisor draft/messages, and selected top-level surface use Compose saved state where practical so rotation does not unnecessarily erase the current screen. A reusable verified-product UI model/card receives only trusted Android-side exact lookup data and opens the existing `LocalProduct.productUrl` through the normal external browser intent.

`TOWAROWNIK_APP_TOKEN` is injected at Android build time through `BuildConfig`. Missing token configuration keeps compilation and ordinary search working; DORADCA fails locally before any network call. The signed Play workflow receives the token only from the matching GitHub Actions secret. The static token is only an Internal Testing abuse barrier and is extractable from an APK/AAB; it is not strong device authentication.

The application uses the normal Android resource system. No orientation is locked, so the UI must continue to adapt cleanly to portrait and landscape sizes. Navigation, dependency injection, persistence, and other frameworks should be added only if a concrete feature requires them.


## Multi-turn proxy continuation

The Worker additionally exposes authenticated `POST /v1/agent/message` with exact input `{previousResponseId,message}`. Android cannot override model, instructions, tools, reasoning effort, token budget, or OpenAI URL. The Worker resends the stable server-controlled instructions/tool declaration and passes `previous_response_id` plus only the new user message to the Responses API.

Using `previous_response_id` avoids manually sending the complete local transcript on each turn, but previous context tokens in that chain are still billed as input tokens. No summarization or compaction is implemented yet.

Server-side instructions explicitly require a fresh `find_available_obi_075` call when the current question depends on current store-075 availability, stock, price, or selecting currently available products. Historical stock/price statements are context only, never current authority.
