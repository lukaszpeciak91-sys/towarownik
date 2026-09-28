# OBI Poland cross-market availability research — 2026-09-28

## Scope

Research only. No production Android repository, parser, advisor tool, advisor instructions, Room schema, manual search, or store allowlist behavior is changed.

The probe started from `main` at:

```text
ddc72ebc19142d6a499b7d73795ea71a94ccccbe
```

Primary live evidence was collected by the bounded research workflow on branch `research/obi-pl-cross-market-availability`:

- run: `36482008095`
- probe head: `bfa16d1a03acbd466236659b389a49830fd0423e`
- OBIKs: `3496072`, `6743009`, `7156243`
- stores: `075`, `074`, `078`

The final committed workflow is `workflow_dispatch` only. The evidence run itself used a temporary trigger restricted to this research branch because the automation client used for this investigation cannot dispatch GitHub Actions manually. That temporary trigger was removed after collecting evidence.

## Existing Android transport profile reused

The probe intentionally mirrors the current production compatibility profile:

```text
User-Agent: Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36
Accept-Language: pl-PL,pl;q=0.9
HTML Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8
JSON Accept: application/json,text/plain,*/*
```

A cookie jar is retained within a probe session and discarded afterwards. No cookie values, auth material, full HTML responses, or full JSON bodies are written to the research artifact. The deterministic probe test also fails if the Android compatibility constants drift away from the values used by the research script.

## Candidate Poland per-store endpoint

Candidate:

```http
GET https://www.obi.pl/api/disc/article-service-proxy/store-specific-articles/v2/PL/pl/{storeNumber}?articleNumbers={OBIK}
```

### Cold request matrix

Every candidate request returned HTTP 404 with `text/html`, not the expected JSON contract.

| OBIK | Store | HTTP | Content-Type | Duration | Result |
|---|---:|---:|---|---:|---|
| 3496072 | 075 | 404 | text/html | 1393 ms | HTTP/challenge failure |
| 3496072 | 074 | 404 | text/html | 848 ms | HTTP/challenge failure |
| 3496072 | 078 | 404 | text/html | 802 ms | HTTP/challenge failure |
| 6743009 | 075 | 404 | text/html | 676 ms | HTTP/challenge failure |
| 6743009 | 074 | 404 | text/html | 813 ms | HTTP/challenge failure |
| 6743009 | 078 | 404 | text/html | 360 ms | HTTP/challenge failure |
| 7156243 | 075 | 404 | text/html | 412 ms | HTTP/challenge failure |
| 7156243 | 074 | 404 | text/html | 372 ms | HTTP/challenge failure |
| 7156243 | 078 | 404 | text/html | 353 ms | HTTP/challenge failure |

The returned HTML contained the existing bounded challenge keywords used by diagnostics. Because a 404 HTML response can represent route absence, edge rejection, or challenge behavior, this evidence does **not** claim the route is definitively nonexistent. It establishes that the candidate Poland form is not a usable verified JSON contract with the current proven browser-compatible transport.

### Primed-session check

To test whether the candidate endpoint required cookies established by the normal product flow, the probe first performed the existing store-selection/full-product request and then called the candidate endpoint with the same cookie jar.

| Store / OBIK | Full product page | Candidate after priming |
|---|---|---|
| 075 / 3496072 | 200 HTML | 404 HTML |
| 074 / 6743009 | 200 HTML | 404 HTML |
| 078 / 7156243 | 200 HTML | 404 HTML |

Candidate durations after session priming were approximately 243 ms, 284 ms, and 662 ms respectively.

Session priming therefore did not make the candidate endpoint usable.

## Cross-check against current full product lookup

The same selected-store semantics used by `ProductLookupRepository` were reproduced for three live pairs. A 200 product page was treated as usable only when its Nuxt payload actually resolved the expected OBIK and selected store.

| Store | OBIK | Selected store in payload | Stock | Gross price |
|---:|---:|---:|---:|---:|
| 075 | 3496072 | 075 | 25 | 12.99 |
| 074 | 6743009 | 074 | 4 | 188 |
| 078 | 7156243 | 078 | 20 | 23.99 |

These full product lookups prove that the tested articles/stores were valid at probe time and that the existing production path could retrieve selected-store facts while the candidate lightweight endpoint failed.

A textual `captcha` / `robot` signature was present in the successful HTML pages, but the expected Nuxt product/store data parsed successfully. Therefore keyword presence alone is not treated as proof that a 200 product response is blocked.

Because no lightweight candidate request produced JSON, there is no valid lightweight-vs-full stock or price equality comparison to report. The comparison is explicitly **unavailable**, not a mismatch in stock values.

## Availability/failure semantics

The research parser deliberately keeps these states separate:

- `verified_stock`: matching article record exists and has a non-negative stock value, including `0`;
- `missing_stock`: matching article exists but stock is absent/unusable;
- `article_missing`: successful JSON array contains no matching article;
- `malformed_json` / `unexpected_json`: a nominal success cannot be interpreted safely;
- HTTP/challenge failure: endpoint did not return a usable success contract;
- transport failure: no HTTP fact was established.

Deterministic tests prove that a valid `stock: 0` remains zero and is not confused with missing stock.

No live Poland candidate response established the semantics of a missing article. Therefore **a missing article must not be treated as stock zero** based on the German behavior alone.

## Store directory

Endpoint:

```http
GET https://www.obi.pl/api/disc/store/locator/country/PL
```

Live result:

- HTTP: `200`
- Content-Type: `application/json`
- duration in primary run: approximately `187 ms`
- response shape: object with `stores`
- parsed stores: `61`
- active stores: `61`

Live entries confirmed the following useful fields:

- `storeNumber`
- `name`
- `city`
- street/address
- postal code
- latitude
- longitude
- `isActive`

Example sanitized rows included store 001 in Tychy, 002 in Warszawa, and 003 in Kraków with names, addresses, and coordinates.

### Comparison with current static allowlist

At probe time:

```text
active directory stores missing from allowlist: none
allowlist entries not returned as active: 004
```

This research PR intentionally does **not** modify `SUPPORTED_OBI_STORE_NUMBERS`. The difference should be reviewed separately before any allowlist maintenance decision.

## Performance observations

The candidate endpoint never produced a successful availability response, so the following timings measure **404/failure latency only** and must not be used as successful-endpoint capacity estimates.

Primary run:

- one request: ~328 ms;
- three sequential stores: 778 ms total, ~259 ms mean, 328 ms max;
- three stores in bounded parallel: 681 ms wall time, ~592 ms mean individual request, 629 ms max.

An earlier run showed noticeable variance, which reinforces that these figures are only rough network observations.

Because the candidate endpoint is not verified, this research does not establish that an N-store availability scan is practical.

## Search for a single-request multi-store endpoint

A bounded static scan inspected the successful product page and its four same-origin JavaScript bundle candidates:

- script candidates: 4
- scripts successfully scanned: 4
- failures: 0
- relevant literal `/api/disc/...` store/article/availability paths discovered: **0**

Targeted public GitHub code search also found no references to the Poland forms:

- `/api/disc/store/locator/country/PL`
- `store-specific-articles/v2/PL/pl`
- Poland `storeSpecificArticle` usage

General web search did not surface a Poland-specific OBI multi-store availability API either.

This is negative evidence only. Minified/runtime-generated frontend calls may not contain a complete literal URL in static bundles. A browser network trace while invoking OBI Poland's actual “availability in another store” UI remains the most useful next research step.

No verified single-request multi-store endpoint was found in this probe.

## Does all-market availability currently require N requests?

Not established.

The German contract suggests an N-per-store strategy, but its Poland analogue did not work in this probe. It would be unsafe to implement an all-market scan by assuming the German route semantics or by falling back to 61 full product-page requests.

The verified directory can provide the store set, but a proven Poland availability data source is still missing.

## Recommendation

### C. NOT READY

The candidate lightweight Poland endpoint is **not ready for implementation**:

1. 9/9 cold requests returned 404 HTML instead of JSON.
2. 3/3 requests after successful same-session product-page priming still returned 404 HTML.
3. The existing full lookup independently verified the tested OBIKs, stores, stock, and prices.
4. The store-directory endpoint is healthy and useful, but it does not provide article availability.
5. No verified single-request multi-store availability endpoint was discovered.
6. Missing-article semantics for Poland remain unproven.

### Future advisor-tool direction

Do not add `find_obi_product_availability` yet.

Preferred future contract, once the live frontend contract is proven:

1. **Prefer a single-request multi-store endpoint** if OBI Poland's own UI exposes one. It should return explicit per-store states and preserve zero vs unknown/failure.
2. If only a per-store endpoint exists, use a bounded/concurrent strategy with explicit partial failures rather than assuming every directory store succeeded.
3. Treat store directory discovery as separate from article availability.
4. Never map “article omitted” to `stock=0` until Poland-specific live evidence confirms that semantic.
5. Keep current full `ProductLookupRepository` as the trusted fallback/reference until a lighter contract is independently verified.

A follow-up research pass should capture the browser network calls produced by the real OBI Poland “check another store” interaction and re-run this probe using the exact observed endpoint/request shape.
