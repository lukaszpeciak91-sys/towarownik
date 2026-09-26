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
- The selected-store Nuxt payload is authoritative for local availability and price. In the current live contract the product is identified by `skuId`, store binding is `product.store.information.storeId`, and local values come only from `product.store.articleData.stock` and `product.store.articleData.pricing.grossPrice`. Seller values and `fallbackPricing` are not substitutes.
- Confirmed numeric stock `0` means zero. Missing, negative, or malformed stock means unknown and must not be converted to zero.
- Nuxt `Ref` and `ShallowRef` wrappers are dereferenced as part of the confirmed flattened payload contract.
- Legacy `selectedStore` fixtures remain supported as a compatibility path, but current live store-bound product data takes precedence.
- Missing local price remains unknown. Retrieval and required product-identity failures produce an unavailable result rather than invented data.
- No generic retailer abstraction is introduced; transport and structured parsing are OBI-specific and remain outside Compose.

## Unified product search v0.1

- One input classifies exactly seven digits as OBIK, plausible 8/12/13/14-digit numeric values as EAN/GTIN, other non-blank input as text, and unsupported blank/numeric input as invalid.
- EAN and text queries use OBI's public search route; search candidates contain only identification data and preserve OBI's ordering.
- Search lists are limited to at most five candidates.
- Text results are never auto-selected. Ambiguous EAN results also require user selection.
- A single EAN candidate may proceed directly only when the fetched product payload confirms the queried EAN.
- Selecting any candidate uses the existing store `075` product lookup for exact local stock and local price.
- Product links alone are not sufficient evidence of search results because OBI may include recommendation/cross-sell product links on a true empty-search page. A positive `Wyniki dla … (N)` count is required before product links are accepted as search results. With a positive count, recognized product links are returned even if generic hidden empty-state wording is also embedded in the HTML. With no positive count, explicit empty-state wording produces `NotFound`; otherwise ambiguity is a data failure.
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
- The GitHub Actions live probe is manual-only (`workflow_dispatch`) and is never part of pull-request checks. Normal CI must remain deterministic and must not depend on live OBI.
- The manual probe may request the same public OBI product flow with the proven browser-compatible headers and store selection.
- Inputs are restricted to a seven-digit OBIK and a three-digit store number before any request is made.
- The cookie jar is deleted before artifact upload and captured live payloads are never committed.
- A safe summary artifact contains only sanitized transport metadata and bounded structural evidence. Raw HTML and extracted `__NUXT_DATA__` may be uploaded only after HTTP 200 from the expected `www.obi.pl` host and are treated as short-lived sensitive diagnostic artifacts.
- The inspector must distinguish flattened Nuxt reference indices from resolved values. Raw scalar values are exposed only for an exact allowlist of parser-contract identifiers and values (for example `skuId`, `storeId`, `stock`, `grossPrice`, EAN/GTIN). Other keyword hits expose only reference indices plus type/length/shape metadata, never arbitrary live scalar strings. Relevant allowlisted fields are reported as explicit chains such as `stock -> ref top[793] -> 25`, not as the misleading pseudo-value `stock=793`.
- Deterministic tests cover Nuxt extraction, malformed/missing payloads, reverse-reference traversal, bounded collectors, wrapper dereferencing, value-vs-reference semantics, and identifier validation.
- Parser fixes must still be converted into deterministic sanitized fixtures and tests before merge; the live workflow is evidence gathering, not a replacement for CI fixtures.


## AI assistant / proxy foundation v0.1

- The original V0.1 decisions “Use no backend” and “Do not include AI in V0.1” describe the initial product-lookup milestone and remain part of the project history.
- The assistant phase intentionally introduces one minimal Cloudflare Worker under `proxy/` solely as the future server-side boundary for protecting API credentials and communicating with OpenAI.
- This foundation does not call OpenAI, select a model, implement prompts, expose agent start/continue endpoints, implement tool calling, or consume API credits.
- Future Worker secrets are reserved as `OPENAI_API_KEY` and `TOWAROWNIK_APP_TOKEN`; neither value belongs in the repository or Android application.
- The Worker does not scrape OBI and must not duplicate or move Android OBI transport/parser/repository logic.
- Existing Android OBI search and exact store-`075` lookup remain authoritative for product identity, local stock, and local price.
- A future model may request a high-level local tool such as `find_available_obi_075(query, limit)`, but Android executes that tool and returns only compact structured results.
- OBI HTML and Nuxt payloads are not sent to OpenAI.
- The initial Worker surface is only `GET /health`; unknown routes return bounded JSON 404 and unsupported health methods return 405.
- No paid Cloudflare services, storage products, schedules, custom domains, or automatic deployments are introduced by this foundation.
