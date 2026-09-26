# Architecture

## Goal

Towarownik should remain a small native Android application. Its intended data flow is:

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

## Future AI assistant boundary

The assistant path is separate from the local OBI data path:

```text
User / Compose
    ↓
AI interaction controller
    ↓
Cloudflare proxy
    ↓
OpenAI
```

The Cloudflare Worker exists to protect server-side API credentials and, in a later milestone, mediate assistant requests. The OpenAI API key exists only on the proxy side. The Android app must never embed it.

When the future model requests a high-level local OBI tool, the direction is:

```text
AI requests local OBI tool
    ↓
existing Android OBI repository/search/lookup
    ↓
store 075 verified structured result
    ↓
compact result returned to AI
```

For example, a future tool intent such as `find_available_obi_075(query, limit)` is executed by Android using the existing OBI mechanisms. The proxy does not scrape OBI, does not contain an OBI parser, and does not become authoritative for OBI data. Existing Android OBI search, OBIK extraction, exact lookup, store `075`, stock, and local price remain the source of truth.

OpenAI must receive only compact structured results produced by the app. OBI HTML, Nuxt payloads, cookies, and parser internals must not be forwarded to OpenAI or moved into the proxy.

The proxy now exposes a public `GET /health` plus authenticated `POST /v1/agent/start` and `POST /v1/agent/continue`. The AI endpoints require the shared Internal-Testing `TOWAROWNIK_APP_TOKEN`; the OpenAI credential remains Worker-only as `OPENAI_API_KEY`.

The Worker calls the OpenAI Responses API with a centralized `gpt-6-luna` configuration, low reasoning effort, concise temporary developer instructions, a bounded output budget, and exactly one strict application-defined function: `find_available_obi_075(query, limit)`. No OpenAI built-in tools are enabled.

When the model returns that function call, the Worker validates the tool name and arguments and returns a normalized `tool_request` envelope to Android. Android executes the existing OBI search/exact store-`075` lookup and later sends only the compact verified result to `/v1/agent/continue`. The Worker continues with `previous_response_id` and a matching `function_call_output`, resending the stable server-controlled instructions/tool declaration. It does not store conversation state in Cloudflare storage.

The proxy normalizes OpenAI output to either `answer` or `tool_request`. Raw Responses payloads, reasoning items, token/usage metadata, internal instructions, and upstream error bodies do not cross into Android.

## OBIK lookup flow

`ProductLookupRepository` accepts only a seven-digit OBIK and always requests store number `075` (OBI Nowy Sącz). `ObiHttpClient` calls `/api/disc/store/change?storeNumber=075&redirectUrl=/p/{OBIK}` with an in-memory cookie jar. OkHttp follows the normal redirect to the product route on that same client, so the response page was produced in the selected-store session. Live Android probing confirmed that this existing URL/redirect/session flow is valid, but OBI/CloudFront rejects the default native/non-browser User-Agent with synthetic empty 404 responses. Production OBI requests therefore apply one centralized browser-compatible HTML navigation profile: a fixed synthetic Android Chrome-style User-Agent, HTML Accept, and Polish Accept-Language. The UA is a compatibility string and does not represent the user's installed Chrome. No bootstrap request or canonical-product prelookup is performed. Non-2xx, empty, and transport responses become explicit unavailable results; the client does not retry.

`ObiPayloadParser` extracts the `__NUXT_DATA__` script as JSON and resolves Nuxt's flattened references. It selects only an object whose product identifier matches the requested OBIK. Product identity may be supplemented from Product JSON-LD; canonical-link markup is a URL fallback. It does not scrape visible price or availability text.

Current live OBI Nuxt payloads wrap product references in `Ref`/`ShallowRef` entries. The decoder unwraps only these confirmed wrapper types while retaining the existing flattened-reference rules. Product identity accepts the current `skuId` field as well as legacy identifiers.

For the current contract, the matched product's selected store is `product.store.information.storeId` (or `storeNumber` if present). Local stock comes only from `product.store.articleData.stock` and local price only from `product.store.articleData.pricing.grossPrice`. Seller stock/pricing and `fallbackPricing` are never substitutes. The current `articleEanEcms` field may decode to a one-element array containing the EAN; the parser accepts either a direct scalar or exactly one non-blank scalar value, and does not guess when multiple values are present. The historical sibling `selectedStore` + product-local `stock`/`pricing` shape remains a compatibility fallback for deterministic legacy fixtures. A parsed integer stock of `0` is confirmed zero. An absent, negative, or unparseable stock is `null` (unknown), and absent/unparseable local price is also `null`.

## Unified search flow

Input classification is explicit: exactly seven digits are an OBIK; numeric GTIN/EAN lengths 8, 12, 13, or 14 are EAN input; other non-blank input is text; blank or unsupported all-numeric lengths are invalid.

OBIK continues to use the direct store-`075` product lookup without a candidate list. EAN and text queries use OBI's public `/search/{query}/` route. `ObiSearchParser` reads product links structurally, preserves their page order, deduplicates by OBIK, and returns at most five candidates. A canonical product URL is also accepted as a single search candidate when OBI redirects a search directly to a product page.

Text search always requires user selection before product lookup. Multiple EAN candidates also require selection. A single EAN candidate is opened automatically only after the existing product payload confirms that its EAN equals the user's query; otherwise the candidate remains selectable instead of being guessed.

An explicit empty-search state or ordinary HTTP 404 maps to not found. A narrow transport safeguard excludes the confirmed infrastructure signature—HTTP 404 with an empty body, `Server: CloudFront`, and `x-cache` containing `Error from cloudfront`—from business not-found classification; that case follows the existing server/network failure path. Unrecognized or changed search structure maps to a data failure, never to not found. Selecting a candidate runs the existing store-`075` product lookup, so local stock and local gross price keep the same data rules.

## Temporary OBI diagnostics

A temporary in-app engineering diagnostic mode observes the existing OBI integration without changing its URLs, headers, redirect policy, cookie behavior, or parsing decisions. It is OFF by default and is opened by long-pressing the Towarownik title. State and history are process-session only; no persistence dependency is used.

`ObiDiagnosticRecorder` is bounded to the last 10 operations. An OkHttp network interceptor observes actual request headers, redirect hops, safe response metadata, and cookie names. All recorded URLs are sanitized: scheme/host/path are retained, only explicitly safe query values such as `storeNumber=075` remain visible, and other query values are replaced with `REDACTED`. Cookie values are inspected only transiently to classify per-hop store evidence as `true`, `false`, or `unknown`, then discarded; they are never stored or printed. Response bodies are never persisted. Successful responses are reduced immediately to safe signatures, while final 4xx/5xx responses use a bounded diagnostic preview for the same signatures. The reported body-size field is `decodedBodyUtf8Bytes`, meaning UTF-8 bytes of the decoded diagnostic text, not raw HTTP payload bytes.

Product and search parsers append diagnostic stages while keeping their existing decisions unchanged. Repositories append error-classification traces and then finalize each diagnostic operation. Diagnostic mode is infrastructure for contract discovery, not product behavior.

Deterministic fixtures and CI prove code behavior against known inputs; they do not prove compatibility with live OBI. Live diagnostic reports must be reviewed before changing transport, session, redirect, parser, or not-found assumptions.

## UI and configuration

The application uses a single Compose activity and the normal Android resource system. No orientation is locked, so the UI must continue to adapt cleanly to portrait and landscape sizes. Navigation, dependency injection, persistence, and other frameworks should be added only if a concrete feature requires them.
