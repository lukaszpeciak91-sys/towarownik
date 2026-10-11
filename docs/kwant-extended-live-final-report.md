# KWANT Extended — final live contract report

**Date:** 11 October 2026. **Evidence:** GitHub Actions [KWANT live contract probe #38122949960](https://github.com/lukaszpeciak91-sys/towarownik/actions/runs/38122949960), research commit `d9089b3f6d82be7c6b2a730a6599c2cedf9b7396`. Both product routes were independently visited in a public Playwright browser. The safe summary artifact contains bounded observed and replayed branch rows; raw request headers, cookies, opaque query fields, session tokens and HTML are not part of the research-PR artifact.

## Exact frontend-generated GET (verified, not guessed)

```http
GET https://services.kwant.net.pl/api/front/products/580/departments?extended=1
GET https://services.kwant.net.pl/api/front/products/7027/departments?extended=1
```

Each exact product page was checked for the corresponding numeric product identity. Each frontend GET had the **single** query key `extended`, with literal value **`1`**. Each returned HTTP **200** and JSON. The root field names observed for both were `list`, `total_stock`, `unit` (no root `product_id`, so product identity is bound to the one-product request path and independently confirmed page identity). Each list row used numeric `department_id` and numeric `stock`. The explicit root `unit` was `szt.`. `total_stock` appeared as a nonnegative numeric field but **must not** be silently interpreted as central stock, branch stock or reconciled inventory without separate evidence.

## Per-product observations (live, volatile quantities)

| Evidence | Product 580 | Product 7027 |
| --- | ---: | ---: |
| Frontend / no-cookie HTTP | 200 / 200 | 200 / 200 |
| Live canonical branch directory | 21 | 21 |
| Returned / distinct mapped canonical rows | 21 / 21 | 21 / 21 |
| Numeric stock values | 21 | 21 |
| Literal zero-stock rows | 3 | 8 |
| Null / missing / invalid stock rows | 0 / 0 / 0 | 0 / 0 / 0 |
| Unknown / duplicate department IDs | 0 / 0 | 0 / 0 |
| Unit | `szt.` | `szt.` |
| Exact selected-branch 205 stock in full list | 424 | 13 |
| Separate `/current?depstock=205` | HTTP 200; ID + department match; 424 | HTTP 200; ID + department match; 13 |
| Product page exact central `product.stock` | 9375 | 45 |
| Anonymous repetition: branch IDs + row stock states + values | Exactly equal | Exactly equal |

These values are a **snapshot**, not production fallbacks or promises of stock availability. In particular, product-level central `stock` values 9375/45 and selected-branch 205 quantities 424/13 come from different independently identified scopes and must **never** be added or substituted. A positive or zero numerical value is exact for the observed branch row at capture time; a future null or missing row must remain unknown, not zero.

## Cookie/session independence

After each browser-generated request, the probe created a **separate standard-library HTTP opener**, with no browser cookie jar and no Authorization/session headers, and repeated only the exact sanitized, allowlisted product-bound GET. Both independent GETs returned 200. For both products, every canonical `(department_id, stock)` pair matched the corresponding browser response. **Verdict for these two observed public requests: session/cookie independent.** This is not a promise that the public API can never change its authentication or anti-automation policy.

## Response schema and semantics

- The product ID is anchored to the singular requested `/products/<numeric-id>/departments` path, separately matched to the canonical product page. The root response does not repeat product ID.
- `list` holds branch-bound rows with canonical numeric `department_id` and one explicit `stock` value per branch. Do not infer state for omitted IDs.
- `stock=0` is an explicit zero; `null`, absent fields, non-integers, negative values, duplicate/unknown IDs and partial coverage must fail closed or be marked unavailable. The latter cases are **synthetic regression tests**, not observed failures in either live response.
- `unit=szt.` is explicit on these two /departments roots; do not infer units for other products. Confirm exact product unit at the point of future production integration.
- `total_stock` exists but has no verified central-vs-sum definition in this run. Exact product-page `product.stock` is the independent central fact, not the availability list nor `/current`.
- `/current?depstock=205` verifies the exact product ID, exact selected department ID, and its own selected quantity; it does not establish all-branch coverage by itself.

## Remaining risks / implementation acceptance gates

1. Only two public products and one selected branch were tested. Validate variable numbers of rows, other units, product IDs, empty or null responses and future directory drift. Do not hardcode 21 or `szt.`.
2. A browser and clean public GET worked on the probe runner at this instant; Android HTTP transport, cache/anti-bot behavior, throttling and future auth changes remain untested.
3. Guarantee exact host/path, `extended=1` as the **observed** literal, numeric route ID, canonical department ID set, no duplicate rows, strict nonnegative integer stock and safe unit; preserve typed unknown/partial states.
4. Avoid unbounded per-branch fanout. The observed contract is one public product-bound GET. A timestamp is not inventory reservation, and quantities can change between current and departments.
5. The existing production adapter and Advisor permissions remain disabled until a separate implementation PR tests offline fail-closed paths, provider isolation, scope limits, cancellation and transport behavior.

**Decision: GO for a separate, guarded production implementation PR using this verified request shape. NO-GO for immediate production activation or merge in this research PR.**

## Tests, safe fixture and code boundary

- Fixture: `tools/fixtures/kwant_extended_live_20261011.json` — contains only bounded, non-production observation aggregates and exact safe request shape, not raw responses.
- Tests: `tools/test_kwant_live_contract_probe.py` — exact endpoint sanitizer, unsafe token/query rejection, unit/zero/null/missing semantics, mismatched product identity, replay gating, selected-current ID matching, fixture consistency.
- CI: workflow #38122949960 — 108 offline Python tests PASS before live probe; Android checks #38122949995 — SUCCESS.
- No live capture data or binary artifacts committed. No production adapter/Worker/UI changes. PR deliberately left Draft and unmerged.
