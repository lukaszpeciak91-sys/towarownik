# Decisions

The following decisions are approved for V0.1:

- Build a native Android application using Kotlin and Jetpack Compose.
- Use package and application ID `pl.lukaszpeciak.towarownik`.
- Use no backend.
- Provide one unified search field.
- Automatically detect a 7-digit OBIK product code, EAN, or product name.
- Return at most five results for text searches.
- Use store number `075` in Nowy Sącz as the default store.
- Focus a result on product name, exact local stock, local store price, and an external product link.
- When local stock is zero, later check a bounded number of nearby stores.
- Do not include product images.
- Do not include user accounts.
- Do not include analytics.
- Do not include AI in V0.1.
- Support portrait and landscape orientations.
- Use neutral Towarownik branding with no retailer branding.
- Validate through local APK testing first, followed by Google Play Internal Testing.

These decisions describe the broader intended product behavior. The currently implemented subset is recorded below.

## OBI product lookup core v0.1

- A seven-digit OBIK remains a direct product lookup and does not pass through a candidate list.
- Store `075` is selected through OBI's store-change endpoint, using one cookie-preserving OkHttp session through its redirect to `/p/{OBIK}`.
- The selected-store Nuxt payload is authoritative for `stock` and `pricing.grossPrice`. Online price and shipping cost are not substitutes for local gross price.
- Confirmed numeric stock `0` means zero. Missing, negative, or malformed stock means unknown and must not be converted to zero.
- Missing local price remains unknown. Retrieval and required product-identity failures produce an unavailable result rather than invented data.
- No generic retailer abstraction is introduced; transport and structured parsing are OBI-specific and remain outside Compose.

## Unified product search v0.1

- One input classifies exactly seven digits as OBIK, plausible 8/12/13/14-digit numeric values as EAN/GTIN, other non-blank input as text, and unsupported blank/numeric input as invalid.
- EAN and text queries use OBI's public search route; search candidates contain only identification data and preserve OBI's ordering.
- Search lists are limited to at most five candidates.
- Text results are never auto-selected. Ambiguous EAN results also require user selection.
- A single EAN candidate may proceed directly only when the fetched product payload confirms the queried EAN.
- Selecting any candidate uses the existing store `075` product lookup for exact local stock and local price.
- Search parser uncertainty is a data failure, not a not-found guess.

## In-app OBI diagnostics v0.1

- Diagnostics are temporary engineering infrastructure and are OFF by default.
- The diagnostics screen is opened by long-pressing the Towarownik title; no permanent diagnostic action is added to the normal search UI.
- Diagnostic history is in-memory only and bounded to the last 10 OBI operations.
- The report may include sanitized request/redirect/canonical URLs, redirect statuses, safe response metadata, parser stages, and error mappings. Unknown query values are redacted; explicitly safe values such as `storeNumber=075` may remain visible.
- Cookie values may be inspected transiently only to determine whether recognizable store context matches `075`; only `true`/`false`/`unknown` evidence and cookie names may be retained. Cookie and Set-Cookie values, full response bodies, tokens, device identifiers, account data, IP addresses, and precise location must never be included.
- Final 4xx/5xx responses may use a bounded in-memory preview to derive the same safe body signatures. `decodedBodyUtf8Bytes` describes decoded diagnostic text re-encoded as UTF-8 and is not a raw HTTP byte count.
- Diagnostic instrumentation must not modify OBI request URLs, request headers, redirect following, cookie/session behavior, parser rules, or not-found semantics.
- Deterministic CI is not evidence that the live OBI contract still matches fixtures; live evidence must be reviewed before revising integration assumptions.

## OBI browser-compatible transport v0.1

- Live Android probing confirmed that OBI/CloudFront returns synthetic empty HTTP 404 responses for the default OkHttp and other non-browser User-Agent profiles.
- Changing only to the fixed synthetic browser-like Android Chrome User-Agent restored normal OBI HTML; HTML Accept and Polish Accept-Language alone did not.
- Production OBI navigation uses the proven browser-compatible profile: the fixed synthetic browser-like User-Agent plus HTML Accept and `Accept-Language: pl-PL,pl;q=0.9`. This compatibility UA is not claimed to be the user's installed Chrome version.
- The existing OBI URLs remain authoritative: search stays on `/search/{query}/`; product lookup still starts with `/api/disc/store/change?storeNumber=075&redirectUrl=/p/{OBIK}`.
- Session bootstrap is unnecessary. Product lookup does not pre-request `/` or a product page.
- The bare `/p/{OBIK}` redirect target is valid; OBI may redirect it to the canonical slug itself, so no slug prelookup is added.
- Only the confirmed empty CloudFront edge 404 signature (empty body + `Server: CloudFront` + `x-cache: Error from cloudfront`) is classified as infrastructure/server failure rather than business not-found. Other HTTP 404 behavior remains unchanged.

## OBI live contract tooling v0.1

- Live Android verification remains authoritative for end-to-end app behavior, but parser-contract discovery does not require a new AAB for every iteration.
- A manual GitHub Actions probe may request the same public OBI product flow with the proven browser-compatible headers and store selection, then retain the public HTML and extracted `__NUXT_DATA__` only as short-lived workflow artifacts.
- The workflow must not upload its cookie jar or commit captured live payloads.
- A repository-side inspector produces bounded structural evidence: exact OBIK/store occurrences, flattened-reference neighborhoods, and relevant key names around product/store/stock/price fields.
- Parser fixes must still be converted into deterministic sanitized fixtures and tests before merge; the live workflow is evidence gathering, not a replacement for CI fixtures.
