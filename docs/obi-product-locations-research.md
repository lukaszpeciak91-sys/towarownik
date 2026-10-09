# OBI product availability across markets — research

**Status: F_INCONCLUSIVE — confirmed first interactive live browser run #37990629233 (9 October 2026); multi-market contract not yet verified.**
Research only. There is no verified OBI all-market or subset API in this PR.
The A–F test fixtures are synthetic and must never be interpreted as live findings.

## CONFIRMED: existing foundations (not new cross-market evidence)

- Exact product control: OBIK **3496072** (seven digits).
- Selected market: **075**, Nowy Sącz (three digits).
- Production-observed selected-store navigation: GET
  https://www.obi.pl/api/disc/store/change?storeNumber=075&redirectUrl=/p/3496072.
  This is a **store-context switch**, not a multi-market stock API.
- Existing tools/obi_live_contract_probe.py inspects downloaded product
  HTML and flattened __NUXT_DATA__. Selected-store evidence cannot be
  promoted to availability in a different store.
- The **only** canonical ID set comes from production OBI_STORES in
  app/src/main/java/pl/lukaszpeciak/towarownik/product/ObiStores.kt.
  Known IDs: 075 Nowy Sącz, 003 Kraków Wielicka, 019 Kraków
  Bora-Komorowskiego, 074 Łódź Wieniawskiego. City alone is ambiguous.
- KWANT's independent all-branch contract is documented in
  docs/kwant-product-locations-research.md and is **not** assumed for OBI.

## CONFIRMED: live run #2 (not yet multi-market proof)

Manual **OBI live contract probe** run
[37990629233](https://github.com/lukaszpeciak91-sys/towarownik/actions/runs/37990629233)
completed successfully on head
`1d10b73387f8251e59fb6960bb2698e26a64f9ac`.

- Browser verified the **exact OBIK 3496072 product page**, exact
  product-owned Nuxt context, and selected market **075** in that context.
- **No product-owned multi-store availability rows** appeared in the
  initial structured Nuxt snapshot. Global directory/recommendations do not
  count as product availability.
- Visible, enabled exact button:
  **`Sprawdź dostępność w innym sklepie`**. Actual SSR markup:
  `<button data-component="PdpLink">...Sprawdź dostępność w innym sklepie</button>`.
  Prior index-based re-resolution found its metadata but **CLICK_FAILED**.
  This failed click proves nothing about the availability contract or
  browser state mutations.
- **Before the click**, the frontend itself fetched
  **`GET /api/pdp/v1/availability/sp/3496072`** (HTTP 200,
  `www.obi.pl`), returning a JSON root object with
  top-level **`pickupStores`**. The exact product identity is
  deterministically bound to OBIK **3496072** by the singular request
  path. Query field names observed: `lang`, `postalCode`, `quantity`;
  their values and semantics are **not** asserted by the sanitized output.
- Separately, frontend itself fetched
  **`GET /api/pdp/v1/availability/hd/3496072`** (HTTP 200),
  returning an object with top-level **`deliveryDataPerSeller`**.
  This is **not** evidence of multi-market pickup inventory. The suffixes
  `sp` and `hd` have no independently verified business meaning.
- Run #2 recorded **zero trusted store/availability rows** for these
  response shapes. The old research parser recognized no usable state field,
  so the classification was correctly **`F_INCONCLUSIVE`**.

## OBSERVED BUT NOT YET PRODUCTION-TRUSTED

The two `/api/pdp/v1/availability/{sp,hd}/3496072` calls are **real
frontend-generated requests**, not guessed endpoints, but their nested
payload schemas, coverage, store ID fields, zero semantics and persistence
effects are not yet established. The updated safe diagnostic will disclose
only bounded **structural** evidence inside `pickupStores` and, separately,
`deliveryDataPerSeller`; it will **not** automatically promote a matching
store-shaped object to known availability.

The exact opener now uses
`get_by_role("button", name="Sprawdź dostępność w innym sklepie", exact=True)`.
It requires one visible, enabled match, optionally corroborates
`data-component=PdpLink`, scrolls it into view and makes a normal click;
zero/multiple/hidden/disabled/changed controls fail closed. No generic
availability text, recommendation, DOM index or forced click is used.
Click failures produce bounded categories, never raw exception text.

## UNKNOWN: research gates after run #2

| Subject | Current status |
| --- | --- |
| Exact harmless opener | **CONFIRMED** in product SSR/visible controls; run #2 click failed |
| Real product-bound availability request | **CONFIRMED** `/api/pdp/v1/availability/sp/3496072`; nested `pickupStores` schema **UNKNOWN** |
| Separate delivery response | **CONFIRMED** `/api/pdp/v1/availability/hd/3496072`; `deliveryDataPerSeller` meaning **UNKNOWN** |
| Store ID field inside `pickupStores` | UNKNOWN; must map exact three-digit canonical OBI_STORES number |
| A/B/D/E request coverage, request-count economics | UNKNOWN; no trustworthy store rows yet |
| Numeric versus qualitative store availability | UNKNOWN; no usable verified state field |
| Zero/unavailable/null/omitted store behavior | UNKNOWN; do not invent values |
| Persistent selected-store mutation during interaction | UNKNOWN; click failed, no store checking occurred |
| Product-owned initial Nuxt multi-store preload | **NOT OBSERVED** for control product 3496072 in run #2 |

## REJECTED / UNSAFE ASSUMPTIONS

Never guess endpoint URLs, copy KWANT paths, brute force, or directly
invoke an unobserved API. Observations must be browser-issued public
XHR/fetch/navigation or existing established production paths.
Do not infer a product ID from click timing, global store directories,
recommendations or array order. Reject contradictory OBIK identities.
Only exact canonical three-digit market IDs count: Kraków and Łódź have
multiple distinct markets. Missing store does not mean stock 0;
numeric 0, null, missing, false and qualitative availability are distinct.
No cart/purchase/reservation/order or personal-data form interactions.
No cookies, session identifiers, tokens, headers or arbitrary user
scalars belong in safe artifacts.

## Manual workflow and next run after PR #100 re-audit

Existing workflow: **OBI live contract probe**
(.github/workflows/obi-live-contract.yml), dispatched manually against
branch **research/obi-product-locations-contract**.

| Workflow input | Next-run value |
| --- | --- |
| obik | **3496072** |
| store | **075** |
| other_markets | **003,074** |

Other-markets input accepts at most two unique IDs from the canonical
OBI_STORES list, different from the selected store. 003 is Kraków
Wielicka, 074 is Łódź Wieniawskiego. 019 is another canonical Kraków
market for a possible later comparison.

The existing curl/HTML/NUXT phase remains unchanged. A bounded
Playwright Chromium phase is added **only to this manual workflow**.
Normal PR CI remains **offline** and installs no Chromium.

Manual browser behavior:
1. Navigate using the already known public store-switch route to the
   configured exact product and verify OBIK from the final product path
   and exactly one product-owned flattened Nuxt skuId.
2. Inspect bounded, visible/enabled accessible control metadata and the
   exact run-2 observed button. Resolve by **exact role/name**, not DOM index.
3. Inspect `pickupStores` and `deliveryDataPerSeller` **separately**,
   recording container types, bounded object/nesting field names, canonical
   store ID candidate fields and availability field scalar types. These are
   structural hints **not** proof of stock. Do not print arbitrary strings,
   addresses, postal/coordinate values, tokens or session identifiers.
4. If an **initial-page** browser-issued exact product availability response
   already establishes complete canonical store coverage with recognized
   usable states, stop without extra market clicks. Otherwise try the
   exact harmless button, then up to two unambiguous canonical market rows
   (003 and 074), if such controls actually appear.
5. Record trusted request method/host/path, safe query/body field names and
   allowlisted values, response root and bounded shapes, and ephemeral
   before/after browser state-change flags. Generic teasers, CMS, global
   directory and recommendation requests **never** count as inventory.
   A missing/failed opener, unknown response fields, or a blocked product
   page stays **F_INCONCLUSIVE**.

**Safe artifact:** obi-live-contract-summary-3496072-store-075
(retention 3 days) now additionally includes locations-summary.json and
locations-summary.txt with bounded observed control names, network
method/host/safe path, query/body field names and safe OBIK/store values,
response status, root type/top fields, candidate store state rows,
classification and per-action request counts.

**Sensitive artifact:** obi-live-contract-raw-3496072-store-075
(retention 1 day) remains the existing curl HTML and Nuxt JSON.
The new browser phase does not save its raw page HTML, full response
bodies, cookies or localStorage. Cookie/localStorage values are compared
**in ephemeral memory only**, exporting change flags, not values.
A change flag is not proof of which selected store changed.

The manual job may technically succeed while output classification
remains F_INCONCLUSIVE. Run #2 is one such case. The next run should
record the `pickupStores` nested shape and retry the now-exact opener;
do not report any A–E result without verified store IDs and usable states.

## Offline analyzer and A–F classification

tools/obi_locations_research.py is a research-only helper imported by
tools/test_obi_live_contract_probe.py for deterministic offline tests.

- **A_ONE_SHOT_ALL_STORES:** one product-bound response has one usable
  non-null numeric/qualitative value per every canonical store ID.
- **B_ONE_SHOT_SUBSET:** one product-bound response covers two or more
  verified stores but not all; **omissions remain unknown**.
- **C_FRONTEND_PRELOADED:** at least two verified store states are already
  in initial Nuxt structured data owned by exactly one verified product.
  Global store directories are ignored.
- **D_MULTI_REQUEST_BOUNDED:** two to five distinct product-bound
  response records jointly describe at least two stores.
- **E_PER_STORE_FANOUT:** at least three distinct store-scoped responses
  for different canonical markets under the same product.
- **F_INCONCLUSIVE:** insufficient or contradictory evidence.

These are research hypotheses; no type A–E is claimed for live OBI.
Importantly, *initial:page* may now contribute to classification only for
a trusted, exact product-bound observed availability route (including
the verified `/api/pdp/v1/availability/sp/{OBIK}`), with canonical rows
and a usable recognized availability state. A click is **not** necessary
to authenticate product scope. Structural field names alone never count.
The separate `hd` response cannot be merged with `pickupStores` and
does not count unless it independently proves canonical market inventory.

### Identity, privacy and zero semantics

Product identity must come from an exact singular OBIK path under
product-like route, explicit safe product ID in query/body, verified
response root product ID, or the exact product-owned Nuxt subtree.
Timing alone never binds a request; conflicting response identity
wins over a matching path and is rejected. Only HTTPS obi.pl hosts
and explicitly whitelisted safe fields are captured. Store numbers
must be three-digit values that occur in OBI_STORES. Duplicate,
unknown, ambiguous or conflicting store IDs invalidate trusted
coverage. Stock 0 is known zero; null is unknown; omitted store is
absent; qualitative states stay qualitative and never get fake numbers.

## Request economics: conditional, NOT OBSERVED

- If A is actually observed, a bounded one-request-per-product strategy
  might work, subject to freshness/coverage checks.
- If B is found, cost each observed subset separately; never pretend
  it covers omitted markets.
- If D/E is found, count the actual 075/003/074 requests before
  deciding a safe hard limit. Do **not** recommend a 60+ store fan-out.
- If C is found, ensure product ownership and freshness before use.
- If browsing another market mutates selection state, production must
  use isolated temporary state rather than silently changing any
  conversation/global WorkingProfile.

## Future shared tool: DESIGN SKETCH ONLY

A single provider-neutral find_product_locations tool should accept
an already verified provider/product reference and return bounded,
identity-verified locations (canonical branchId and display name),
nullable numeric stock and/or typed qualitative availability,
coverage and verifiedAt. KWANT's known numeric quantities cannot
force OBI into fictitious numbers. Exact schema and cost limit await
real OBI evidence. No production provider, parser, Worker, Advisor,
protocol, Room, UI, prompt, WorkingProfile or endpoint call changes
are included in this PR.

**Exit gate:** run #2 confirmed the exact product, selected store, harmless
opener and two real pre-click availability endpoints, but **not their market
stock semantics**. Audit a second manual browser run after the safe parser
and exact-click changes, classify only product-bound verified market states,
and plan production in a separate PR. **Do not merge this research PR.**
