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

The product lookup core now implements the repository, HTTP/session transport, and OBI payload parser layers. It is intentionally not connected to Compose yet.

## Boundaries

- **UI / Compose** renders state and reports user intent. It must not issue HTTP requests or understand OBI payload details.
- **Repository / domain layer** will coordinate search rules and expose application-oriented results without leaking transport or page-format details into the UI.
- **HTTP/session transport** will retrieve public data and report transport outcomes. It must not interpret OBI payloads.
- **OBI-specific payload parser** will translate retrieved payloads into validated data. Parsing failures must be explicit; missing or malformed values must never be invented.

Transport and parsing must remain isolated from the UI. An OBI website change should require changes in its transport/parser boundary and tests, not a UI rewrite.

## OBIK lookup flow

`ProductLookupRepository` accepts only a seven-digit OBIK and always requests store number `075` (OBI Nowy Sącz). `ObiHttpClient` calls `/api/disc/store/change?storeNumber=075&redirectUrl=/p/{OBIK}` with an in-memory cookie jar. OkHttp follows the normal redirect to the product route on that same client, so the response page was produced in the selected-store session. Non-2xx, empty, and transport responses become explicit unavailable results; the client does not retry.

`ObiPayloadParser` extracts the `__NUXT_DATA__` script as JSON and resolves Nuxt's flattened references. It selects only an object whose product identifier matches the requested OBIK. Product identity may be supplemented from Product JSON-LD; canonical-link markup is a URL fallback. It does not scrape visible price or availability text.

Local stock comes only from the matched product object's `stock` value in the selected-store Nuxt payload. Local price comes only from that object's `pricing.grossPrice`; neither online price nor shipping cost is a fallback. A parsed integer stock of `0` is confirmed zero. An absent, negative, or unparseable stock is `null` (unknown), and absent/unparseable local price is also `null`.

## UI and configuration

The application uses a single Compose activity and the normal Android resource system. No orientation is locked, so the UI must continue to adapt cleanly to portrait and landscape sizes. Navigation, dependency injection, persistence, and other frameworks should be added only if a concrete feature requires them.
