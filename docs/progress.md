# Progress

## Current phase

**V0.1 — OBI text-search false-empty repair**

Live Android diagnostics on app `0.1.5 (6)` confirmed that text-search transport and product-link extraction work: `dedra` produced 76 recognized unique product links while OBI reported 706 results, `pufas` produced 4, and the full Pufas product name produced 1. The failure was a parser precedence bug: a generic embedded “Nie znaleźliśmy żadnych wyników” phrase was treated as authoritative before recognized product links, causing false `NoResults`.

The search parser now treats recognized product links as stronger evidence than generic zero-result phrases. Explicit zero-result wording is used only when no product candidates were extracted.


## Previous completed context

**V0.1 — OBI live payload/parser contract repair**

Hardware verification of app `0.1.2 (3)` confirmed that the browser-compatible production transport is working end to end:

- the store-change request for `075` returns HTTP 302 to the bare product path;
- OBI redirects the bare product path to the canonical slug;
- the final product response is HTTP 200 and contains real OBI HTML;
- the production request uses the proven browser-compatible User-Agent, HTML Accept, Polish Accept-Language, redirect handling, and cookie session;
- the full response contains valid `__NUXT_DATA__`, the requested OBIK, store `075`, and the canonical product URL.

Hardware verification of app `0.1.3 (4)` reproduced the parser failure after transport success: `NUXT_JSON_PARSE_OK` followed by `PRODUCT_ID_MATCH_FAILED` and `STORE_075_MATCH_FAILED`. The live contract probe established the concrete mismatch: current payloads use `Ref`/`ShallowRef`, `skuId`, `product.store.information.storeId`, and `product.store.articleData` for local stock/pricing.

A developer-only GitHub Actions live contract probe is being added so current public OBI HTML and Nuxt structure can be inspected without rebuilding the Android app for every diagnostic iteration. It is manual-only; pull-request CI remains deterministic. The probe validates OBIK/store inputs, sanitizes the final URL, uploads raw payloads only after HTTP 200 from the expected OBI host, deletes its cookie jar before artifact handling, and labels raw captures as short-lived sensitive diagnostics. Its inspector now resolves flattened Nuxt references explicitly so fields such as local stock and gross price cannot be confused with reference indices. Deterministic Python tests run in normal CI.

## Current implementation milestone

**Repair `ObiPayloadParser` against the confirmed live Nuxt contract**

The parser fix adds confirmed `Ref`/`ShallowRef` unwrapping, `skuId` identity, store binding through `product.store.information`, and local stock/price through `product.store.articleData`. A sanitized deterministic fixture preserves the observed distinction between local stock `25` / gross price `12.99` and unrelated seller stock `9` / seller or fallback prices.

After this PR passes review and CI, perform one final Android verification before the next Play AAB.

## Not started

- Camera barcode scanning
- Local and nearby-store fallback behavior
- External product-link behavior
- Persistence/history/favorites
- Product images
- AI
- Final Play release polish
