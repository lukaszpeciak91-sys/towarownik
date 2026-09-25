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

## OBIK lookup flow

`ProductLookupRepository` accepts only a seven-digit OBIK and always requests store number `075` (OBI Nowy Sącz). `ObiHttpClient` calls `/api/disc/store/change?storeNumber=075&redirectUrl=/p/{OBIK}` with an in-memory cookie jar. OkHttp follows the normal redirect to the product route on that same client, so the response page was produced in the selected-store session. Non-2xx, empty, and transport responses become explicit unavailable results; the client does not retry.

`ObiPayloadParser` extracts the `__NUXT_DATA__` script as JSON and resolves Nuxt's flattened references. It selects only an object whose product identifier matches the requested OBIK. Product identity may be supplemented from Product JSON-LD; canonical-link markup is a URL fallback. It does not scrape visible price or availability text.

Local stock comes only from the matched product object's `stock` value in the selected-store Nuxt payload. Local price comes only from that object's `pricing.grossPrice`; neither online price nor shipping cost is a fallback. A parsed integer stock of `0` is confirmed zero. An absent, negative, or unparseable stock is `null` (unknown), and absent/unparseable local price is also `null`.

## Unified search flow

Input classification is explicit: exactly seven digits are an OBIK; numeric GTIN/EAN lengths 8, 12, 13, or 14 are EAN input; other non-blank input is text; blank or unsupported all-numeric lengths are invalid.

OBIK continues to use the direct store-`075` product lookup without a candidate list. EAN and text queries use OBI's public `/search/{query}/` route. `ObiSearchParser` reads product links structurally, preserves their page order, deduplicates by OBIK, and returns at most five candidates. A canonical product URL is also accepted as a single search candidate when OBI redirects a search directly to a product page.

Text search always requires user selection before product lookup. Multiple EAN candidates also require selection. A single EAN candidate is opened automatically only after the existing product payload confirms that its EAN equals the user's query; otherwise the candidate remains selectable instead of being guessed.

An explicit empty-search state or HTTP 404 maps to not found. Unrecognized or changed search structure maps to a data failure, never to not found. Selecting a candidate runs the existing store-`075` product lookup, so local stock and local gross price keep the same data rules.

## Temporary OBI diagnostics

A temporary in-app engineering diagnostic mode observes the existing OBI integration without changing its URLs, headers, redirect policy, cookie behavior, or parsing decisions. It is OFF by default and is opened by long-pressing the Towarownik title. State and history are process-session only; no persistence dependency is used.

`ObiDiagnosticRecorder` is bounded to the last 10 operations. An OkHttp network interceptor observes actual request headers, redirect hops, safe response metadata, and cookie names. All recorded URLs are sanitized: scheme/host/path are retained, only explicitly safe query values such as `storeNumber=075` remain visible, and other query values are replaced with `REDACTED`. Cookie values are inspected only transiently to classify per-hop store evidence as `true`, `false`, or `unknown`, then discarded; they are never stored or printed. Response bodies are never persisted. Successful responses are reduced immediately to safe signatures, while final 4xx/5xx responses use a bounded diagnostic preview for the same signatures. The reported body-size field is `decodedBodyUtf8Bytes`, meaning UTF-8 bytes of the decoded diagnostic text, not raw HTTP payload bytes.

Product and search parsers append diagnostic stages while keeping their existing decisions unchanged. Repositories append error-classification traces and then finalize each diagnostic operation. Diagnostic mode is infrastructure for contract discovery, not product behavior.

Deterministic fixtures and CI prove code behavior against known inputs; they do not prove compatibility with live OBI. Live diagnostic reports must be reviewed before changing transport, session, redirect, parser, or not-found assumptions.

## UI and configuration

The application uses a single Compose activity and the normal Android resource system. No orientation is locked, so the UI must continue to adapt cleanly to portrait and landscape sizes. Navigation, dependency injection, persistence, and other frameworks should be added only if a concrete feature requires them.
