# OBI product availability across markets — research

**Status: B_ONE_SHOT_SUBSET — FINAL LIVE VALIDATION COMPLETE (run #7, 10 October 2026). The product-bound batch returned trusted numeric stock for all 10 requested canonical OBI markets; full 62-market one-shot coverage remains unproven.**
Research only. A public frontend **10-market subset contract is verified**, but no production provider/tool integration or full-directory one-shot contract is implemented.
Synthetic regression quantities are distinct from the real runs #6–#7 request/response **identity, schema and coverage** evidence; they are not actual market quantities.

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

## CONFIRMED: live run #3 — empty prefetch, SVG-influenced button name

Manual **OBI live contract probe** [run #37994385878](https://github.com/lukaszpeciak91-sys/towarownik/actions/runs/37994385878) succeeded on HEAD `b9aecc29e14901b3aacac59e9e4124a3235dc332` (before the current selector change). Result: **`F_INCONCLUSIVE`**.

- Exact OBIK **3496072**, selected market **075**, product-owned Nuxt identity and selected-store proof were verified again.
- Initial Nuxt **did not contain product-owned multi-market stock/availability**.
- The real browser's **initial:page** request `GET /api/pdp/v1/availability/sp/3496072` returned HTTP 200 with **`pickupStores=[]`**; research-only structural diagnostic: `containerType=list`, `containerLength=0`, **zero canonical store rows**. This does not mean other markets have zero stock or that a subset was checked.
- Independently, `GET /api/pdp/v1/availability/hd/3496072` returned `deliveryDataPerSeller`, one observed item with field names `sellerId`, `deliveryDate`, `deliveryOption`, `deliveryCost`. This **delivery structure is not store-pickup inventory** and must remain separate.
- Exact harmless opener remained present as an SSR **`<button data-component="PdpLink">`** with exact visible `innerText` **„Sprawdź dostępność w innym sklepie”**. Its nested SVG has an `<title>arrow-right</title>`, potentially changing Playwright's **accessible name**. The previous `get_by_role(..., name=..., exact=True)` returned zero matches; `EXACT_BUTTON_MISSING` was a **selector false negative**, *not* evidence the button disappeared.
- No opener click was completed in run #3. **No post-open request, store-picker behavior, mutation of selected-store state, or multi-market stock contract has been proven.** Run #3 must remain **F**.

### Research follow-up after run #3 (no new live evidence yet)

- The opener now resolves **native** `button[data-component="PdpLink"]` candidates and requires exactly one with normalized **innerText equal to the observed entire phrase**. DOM index and generic availability substring never select it; zero/duplicate/hidden/disabled/changed controls fail closed. The click scrolls into view, uses a normal non-forced click and emits only a bounded failure category.
- After an actual successful click, the probe records **new post-open UI structure** separately: visible dialog/region counts, bounded safe headings/keyword tags, input count and type, keyword-only search hints, candidate row counts, safe store-related **data-attribute names** and verified canonical OBI market numbers from explicit ID data attributes or exact **repository city + address** matching. Raw modal addresses, coordinates, arbitrary strings, postal codes, identifiers and cookies are **not emitted**.
- A store search input alone is **observation only**, never permission to type guessed text. Market 003/074 selection is attempted only for one uniquely identified enabled row inside one visible dialog. No city-only match or page-wide recommendation click.
- Browser requests are attributed to the action during which they **began**. Initial `pickupStores=[]` stays `initial:page`; if the frontend later issues a new product-bound request and returns non-empty `pickupStores`, that is a **separate `availability:open` observation**. Existing strict product identity, canonical store ID, typed usable state, zero/null/missing distinctions and A–F gates are unchanged. A complete proven initial response would make additional market clicks unnecessary; no such response has yet been observed.

## CONFIRMED: live run #4 — exact opener resolved, Playwright actionability timeout

Manual [OBI live contract probe #37997658969](https://github.com/lukaszpeciak91-sys/towarownik/actions/runs/37997658969)
completed successfully on HEAD
`7ac03a23a6787740ec8f477aac5e0fe542544561`.
The research classification remained **`F_INCONCLUSIVE`**.

- The exact native `button[data-component="PdpLink"]` was identified
  **uniquely**, visible, enabled, with normalized `innerText` exactly
  **"Sprawdź dostępność w innym sklepie"**. It is not a recommendation,
  cart, purchase, or reservation control. Do **not** broaden its selector.
- Normal Playwright `button.click(timeout=5000)` failed with a bounded
  `CLICK_FAILED / TIMEOUT`. The safe run did **not** produce
  `availability:*` network traffic or new post-open store/dialog evidence.
  This is an **actionability timeout**, not evidence the button has no
  effect or that other-market availability is absent.
- The initial frontend-issued
  `GET /api/pdp/v1/availability/sp/3496072` returned
  **`pickupStores=[]`** again. No initial multi-market rows were
  verified. Omitted stores are **not** zero and neither A nor B can be
  inferred. The separate `/availability/hd/3496072` structure remains
  delivery-specific and is **not** used as pickup-store stock.

### Next research run — strictly bounded native DOM click after timeout

The probe still performs the **normal Playwright click first**. **Only if**
that exact harmless opener was uniquely resolved, visible and enabled,
and the native Playwright click raises an actual Playwright actionability
`TimeoutError`, the probe **re-resolves** the same exact native
`button[data-component="PdpLink"]` with normalized exact text and
revalidates it **inside the page JavaScript**, then calls only that
element's standard DOM `element.click()`. No coordinate click, broad
substring, `force=True`, other-control fallback or purchase action is
permitted. A failed re-resolution or failed DOM click is bounded and
non-authoritative; exception text is never persisted.

An attempted DOM `element.click()` is **provisional**, not proof of
an availability action. After a bounded wait, at least one observable
change is required: newly visible store/dialog UI, availability-specific
button expansion, a new store-search/canonical store-row UI structure,
or an **action-phase** frontend request related to availability,
pickup, stores or locator. Unrelated recommendations, teasers, CMS and
delivery data cannot qualify as store-stock facts. Without an effect,
the recorded status becomes **`DOM_CLICK_NO_OBSERVABLE_EFFECT`**,
and availability classification remains F.

The phase `availability:open` is set **before the normal click and
any DOM fallback**. Initial `pickupStores=[]` remains separately
attributed to `initial:page`; any actual new `/availability/sp/3496072`
response after the handler is a separate observation. The existing
safe `postOpenUi` inspection records only bounded dialog, heading,
search-input and canonical market-ID metadata. If a search field is
shown without verified market rows, it is observed but **never filled
with guessed text**. Markets 003/074 are clicked only if uniquely
identifiable within one visible dialog. Numeric stock, qualitative
availability and A/B/D/E coverage still require their **own**
trusted product/store identity and stock-state evidence.

**No successful availability-opening action or multi-market OBI contract
is claimed until a later live run actually supplies such evidence.**

## CONFIRMED: live run #5 — frontend-issued stock batch request after DOM click

Manual [OBI live contract probe #37999536515](https://github.com/lukaszpeciak91-sys/towarownik/actions/runs/37999536515) succeeded on HEAD
`6b4f56e748ad59c78fcfb7c5cf0c386804fe7d88`.
Android checks on the same HEAD were successful. The machine correctly
reported **F_INCONCLUSIVE**.

- Exact OBIK **3496072**, current store **075** and product-owned Nuxt
  context were verified, as in previous runs.
- Only the exact, unambiguous, visible and enabled
  `button[data-component="PdpLink"]` with exact normalized
  `innerText="Sprawdź dostępność w innym sklepie"` was targeted.
  Playwright normal click timed out; an **identity-revalidated native
  DOM click** was successfully dispatched with mode
  **DOM_CLICK_AFTER_ACTIONABILITY_TIMEOUT**. No force/coordinate click,
  cart, reservation or purchase action was performed.
- **Immediately after that action, the real browser issued**
  `GET /api/pdp/v1/stock/3496072?storeIds=...` on trusted
  `www.obi.pl` with action phase **availability:open**.
  Response was **HTTP 200**, JSON root **list**. The singular exact
  OBIK in the real request path is deterministic product identity.
  This route was **observed**, **not guessed or manually called**.
- The old event-effect checker did not recognize this newly observed
  stock path and incorrectly returned `DOM_CLICK_NO_OBSERVABLE_EFFECT`.
  This is a **probe false negative**: the frontend request itself proves
  the availability-button event had an observable effect. It does not
  establish that stock rows have been decoded.
- The old safe sanitizer exposed the `storeIds` **field name** but not
  its canonical ID values. Its response parser found no trusted rows
  within the list. Neither request coverage nor response store IDs /
  stock state have been semantically established. **No A/B/D/E result
  is justified yet.**
- Independently, initial-page
  `/api/pdp/v1/availability/sp/3496072` returned
  **`pickupStores=[]`**. Initial preloading and post-open stock are
  separate observations. Initial empty list is **not zero stock** in
  omitted markets. Initial `/availability/hd/3496072` retains its
  delivery-only `deliveryDataPerSeller` structure.

### Run #5 corrective research instrumentation — no new live result yet

- Recognize **only** exact observed `/api/pdp/v1/stock/{7-digit OBIK}`
  for product binding, safe response shape, availability action effect
  and classification eligibility. Wrong ID/generic routes fail closed.
- For **this observed stock route only**, safely parse `storeIds` from
  bounded comma-separated and repeated parameter occurrences; preserve
  canonical three-digit values (including leading zeroes), the occurrence
  sequence and duplicates, an independent deduplicated coverage set,
  and a flag for unknown/invalid/truncated tokens. Never emit an
  unknown token or full query string. Other unobserved encodings
  remain UNKNOWN, not fabricated.
- Record a **structural-only** `stockResponseStructure` for the response
  root LIST: length, representative safe field names/types, explicit
  canonical ID scalar + owning field name, stock/quantity candidate
  field types and strictly allowed numeric / qualitative candidates.
  An unfamiliar store identifier field is **not** promoted to
  `STORE_KEYS`; field-name similarity alone does not prove availability.
- Correlate validated requested IDs against only trusted response rows:
  `requestedCanonicalIds`, `returnedTrustedIds`,
  `missingRequestedIds`, `unexpectedReturnedIds`,
  `returnedSubsetOfRequest`. Every omitted requested market stays
  UNKNOWN, not 0. A future **A** requires the request itself to cover
  the complete canonical directory and every store to have usable
  verified state; a future **B** requires multiple usable verified
  requested stores without complete directory coverage.
  Neither is claimed on run #5.
- DOM click effect now recognizes an exact browser-generated
  `availability:open` stock request without requiring a visible modal
  to appear. Strict product/store/availability classification gates
  remain independent of that event-effect finding.
- This remains **research only**; no production endpoint, providers,
  WorkProfile, Advisor, Worker, protocols or UI were modified.

## CONFIRMED: live run #6 — OBI product-bound multi-market stock batch

**Authoritative browser evidence:** manual
[OBI live contract probe #38000880352](https://github.com/lukaszpeciak91-sys/towarownik/actions/runs/38000880352),
HEAD `ee4555f0d5be454c7d87bbdf901569f1d4ed5280`. Android checks and
manual live research completed **green**. The old machine classifier still
reported `F_INCONCLUSIVE` because `candidate_rows()` did **not** recognize
the actual `storeId` / `availableQuantity` schema. That was a research
parser limitation, **not** a lack of authoritative frontend data.

The real public browser, after activating the exact harmless availability
button with `DOM_CLICK_AFTER_ACTIONABILITY_TIMEOUT`, issued:

```text
GET /api/pdp/v1/stock/3496072?storeIds=...
```

- Trusted OBI HTTPS host, HTTP 200; exact seven-digit OBIK **3496072**
  bound by the *singular observed path*. **No endpoint was guessed.**
- Exact canonical store IDs in `storeIds`: **037, 038, 078, 073, 008,
  053, 061, 022, 029, 070**. `canonicalCount=10`, `tokenCount=10`,
  `duplicatesPresent=false`, `invalidOrUnknownPresent=false`.
- JSON root **list of exactly 10 objects**. Every observed object had
  precisely two fields: **`storeId`** (canonical three-digit market
  identifier) and **`availableQuantity`** (non-negative integer stock).
- The 10 returned canonical `storeId` values matched **exactly** the ten
  requested IDs, regardless of row order. The request **did not** include
  all **62** canonical OBI stores. **No** availability is implied for
  the other 52 stores.
- `selectedStoreMutation=COOKIE_STATE_UNCHANGED` after this observed
  interaction: querying other-store availability **did not require changing
  the selected browser store cookie**. The future Taksula operation must
  remain read-only and **must not mutate WorkingProfile**.
- Post-open UI: **one visible dialog**, **no visible search input**, and
  no canonical store row resolvable via existing safe DOM inspection.
  The attempted market 003 DOM selection had no unambiguous match.
  This does **not** weaken the successfully observed ten-store network batch.
  Do not keep forcing per-market UI clicks to prove a batching ability that
  the actual browser network already demonstrates.
- Initial `GET /api/pdp/v1/availability/sp/3496072` remained
  `pickupStores=[]` (not a zero for any omitted store).
  `/availability/hd/3496072` stayed separately delivery-related
  via `deliveryDataPerSeller` and is **not** stock inventory.

**Classification after promoting the exact observed schema:**
`B_ONE_SHOT_SUBSET` with research reason
`PRODUCT_BOUND_REQUESTED_STORE_BATCH`.
Exactly one product-bound request covered ten explicitly requested markets
and returned one trustworthy numeric quantity per requested ID. This is
**not** `A_ONE_SHOT_ALL_STORES`, which requires the *request itself*
to cover the complete canonical directory, plus one trusted state per store.

### FINAL VALIDATION: live run #7 — trusted 10-of-10 batch, machine B

The final manual [OBI live contract probe #38028530589](https://github.com/lukaszpeciak91-sys/towarownik/actions/runs/38028530589)
completed **successfully** on PR #100 HEAD
`3bc167ec7d695695972ed7d2c6f4d529181dd9d7`.

- The research classifier returned **`B_ONE_SHOT_SUBSET`**, reason
  **`PRODUCT_BOUND_REQUESTED_STORE_BATCH`**, with
  **`observedStoreCount=10`**.
- Requested canonical stores: **10**; returned trusted stores: **10**.
  **No missing requested IDs** and **no unexpected returned IDs**.
  Every requested market has a **trusted numeric stock state**.
- Run #7 is the **final live validation** of the product-bound ten-store
  batch contract observed in run #6. The older run #6 machine
  `F_INCONCLUSIVE` was a parser limitation; the corrected research
  classifier now returns **B** on live data.
- This is **not A**: only batch size **10** is live-proven, not a
  single batch covering all **62** canonical OBI markets. The other
  52 stores remain **unknown**, not zero or unavailable.
- This PR remains **research-only**: production
  `find_product_locations` is **not implemented**. Any future OBI
  integration must keep `WorkingProfile` **read-only**.
  **No additional live run is required for this research PR.**

### Strict research parser, coverage and limitations

Only the exact observed
`/api/pdp/v1/stock/{7-digit OBIK}` response uses a special
`parse_observed_stock_rows()` decoder. It never broadens generic
`candidate_rows()` or production parsers. The trust gate requires:

- exact request/product identity, trusted host, HTTP 200 and GET;
- JSON root list; each row a map with canonical three-digit `storeId`
  and present **integer >= 0** `availableQuantity`;
- no unknown or duplicate market IDs, invalid/null/negative/float/string/
  boolean quantities, or missing quantities;
- an explicitly decoded valid canonical `storeIds` request and returned
  response IDs restricted to those requested. Request coverage comparison
  reports `requestedCount`, `returnedTrustedCount`,
  `missingRequestedIds`, `unexpectedReturnedIds` and
  `everyRequestedStoreHasTrustedState` without fabricating any omitted row.

A literal integer **0** is trusted known zero, not `null` or a missing
store; missing requested or non-requested stores are **unknown**.
Invalid/ambiguous rows cause the batch's trusted state to fail closed.
Response ordering is not assumed to have semantics.

The only live-proven batch size is **10**. This proves **bounded batching**,
not unlimited list support or whole-directory stock in one request.
Do **not** assume a higher maximum or fan out to 62 individual requests.

### Production-facing design recommendation — NOT IMPLEMENTED

One shared future model-facing **`find_product_locations`** tool should
have provider-specific adapters with one provider-neutral response.
For **OBI**, use exact verified OBIK and **bounded canonical store IDs**
(choose no more than the live-observed **10** in one batch without further
evidence); use the real frontend-observed
`/api/pdp/v1/stock/{OBIK}?storeIds=...` contract to read
`storeId` and integer `availableQuantity`. The query is read-only and
must not alter `WorkingProfile`. For **KWANT**, the already independently
verified transport is `/api/front/products/{productId}/departments`.
Do not impose KWANT's all-branch response shape or transport assumptions
on OBI. The model-facing tool is shared; **this PR implements neither
adapter nor the tool**.

## OBSERVED BUT NOT YET PRODUCTION-TRUSTED

The initial `/api/pdp/v1/availability/sp/3496072` (empty `pickupStores`)
and separate `/availability/hd/3496072` (delivery fields) are real
frontend calls, but neither proves stock in other markets. Their
unknown deeper semantics are not promoted to the **independently verified
stock batch** contract. Only the exact `/api/pdp/v1/stock/{OBIK}` route
and its `storeId` / integer `availableQuantity` list are trusted for
this research classification.

The exact native `PdpLink` control and DOM fallback were confirmed in
runs #4–#7. The fallback is used only after normal-click actionability
timeout on the one visible enabled control matching exact `innerText`.
Run #6 proved post-open batch traffic (validated again in run #7), while the modal store rows were
not individually actionable in the safe DOM investigation.

## UNKNOWN / NOT PROVEN after final run #7

| Subject | Status |
| --- | --- |
| Exact product-bound `/stock/{OBIK}` query | **CONFIRMED**, ten canonical `storeIds` in one request |
| Exact `storeId` / integer `availableQuantity` response list | **CONFIRMED**, ten rows matching requested IDs |
| Contract classification | **B_ONE_SHOT_SUBSET**, not complete-directory A |
| Product identity / selected market | **CONFIRMED**, OBIK 3496072 / 075 |
| Market stock values outside the 10 requested IDs | **UNKNOWN**; never zero-filled |
| More than 10 market IDs in one request | **NOT PROVEN**; do not assume support |
| All 62 markets in one request | **NOT PROVEN**, A must not be claimed |
| Generic `sp` preload | **CONFIRMED EMPTY** (`pickupStores=[]`) in run #6 |
| `hd` delivery rows | **CONFIRMED SEPARATE**, never used as store stock |
| Cookie state after availability interaction | **CONFIRMED UNCHANGED** in run #6 |
| WorkingProfile production behavior | **NOT IMPLEMENTED**; future shared tool must be read-only |
| Exact UI DOM store rows / search | Unresolved; does not negate observed network batch |

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

## Manual workflow — final run #7 completed

Existing workflow: **OBI live contract probe**
(.github/workflows/obi-live-contract.yml), dispatched manually against
branch **research/obi-product-locations-contract**.

| Workflow input | Final run #7 value |
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
2. Inspect bounded, visible/enabled control metadata and the exact
   opener confirmed in run #3. Resolve native `button[data-component="PdpLink"]`
   by **exact normalized innerText**, not SVG-influenced accessible name,
   generic substring or DOM index.
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

The manual job can technically succeed without confirming a contract
(as in runs #2–#5). Run #6 established the exact stock batch
schema; final run #7 confirmed **B_ONE_SHOT_SUBSET** on live data,
with all 10 requested canonical IDs covered by trusted numeric states
and no missing or unexpected IDs. This completes the research PR's live
validation; **no further manual workflow dispatch is needed**.
No A/complete-directory behavior is claimed. Do not enlarge the
live batch or guess extra URLs.

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

**B_ONE_SHOT_SUBSET** is supported by run #6's live browser request/response and exact schema, and independently confirmed by the final run #7 machine classifier. A, C, D and E are not yet verified. Synthetic full-directory A cases do not prove real A.
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
The generic field `status` may describe store operations, while `quantity`
may mean requested units; both can appear in structural diagnostics but do
**not** authorize stock classification without independently observed
availability semantics. Known product-stock/availability-specific field
names remain separate from the exploratory candidate field list.

## Request economics — confirmed bounded OBI batching

The observed OBI frontend issued **one** exact-product batch request with
**10** canonical `storeIds` and received **10** matching numeric stock
rows. Cost for this proven set: **1 public frontend request per product
and bounded requested subset**, not 10 separate market requests.

Only batch size **10** is proven. Do not assume 62 IDs work in one
request, invent a maximum, or advise 62 individual per-market requests.
An eventual production adapter should cap a batch to the observed size
until deliberate additional live proof. A request omitting a market leaves
that market's stock unknown. The observed selected-store cookie remained
unchanged, supporting read-only profile-preserving design. For other
products or future frontend versions, source identity and response
coverage still require verification.

## Future shared tool: DESIGN SKETCH ONLY

One shared provider-neutral `find_product_locations` should receive a
verified product reference and requested canonical locations, returning
location-specific numeric stock (nullable when genuinely unknown),
coverage and verification time. For OBI, use only bounded
`/api/pdp/v1/stock/{OBIK}?storeIds=...` batch requests (live-proven
batch size **10**) and exact `storeId` / `availableQuantity` fields.
For KWANT, use the independently verified
`/api/front/products/{productId}/departments` transport. This is
**architecture advice**, not production support. Keep queries
read-only and never silently alter WorkingProfile. Production provider,
Worker, Advisor, Room, Compose, prompts and protocols are unchanged.

**Validation gate completed:** run #6 established the requested ten-store
batch and exact numeric response schema; final live run #7 on the updated
research classifier confirmed **B_ONE_SHOT_SUBSET**, trusted numeric states
for all 10 requested stores, and no missing or unexpected IDs. The offline
regressions and final manual live workflow are complete. **No additional
live run is required for PR #100.** Do not claim A/full 62-market
coverage. Production `find_product_locations` remains separate and
unimplemented; future integration must never modify `WorkingProfile`.
PR #100 is ready for final audit; merge is a separate decision.
