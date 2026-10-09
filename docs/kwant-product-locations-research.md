# KWANT: product availability across locations — live-contract research

Status: **CONFIRMED PUBLIC ONE-SHOT ALL-BRANCH INVENTORY CONTRACT FOR CONTROL PRODUCT 580** — live run #18, 9 October 2026.
A public frontend request returned 21/21 directory branches including explicit zero-stock rows. This is sufficient research evidence to design a future `find_product_locations` integration, **not** evidence that Taksula already implements it. Exact-product binding, zero-vs-null and central-vs-branch separation remain mandatory in production.

## Evidence and reproduction

The repository already records live-tested frontend paths (see
`docs/decisions.md`, 2026-10-06 and `KwantProductProvider.kt`):

| Operation | Observed public contract | Trust limitations |
| --- | --- | --- |
| Product discovery | `POST https://services.kwant.net.pl/api/front/search-engine/page`; JSON `q,page,limit,tags` | Returns `hits[]` with product `id/slug/code/name`; all candidates still require exact verification |
| Canonical product | `GET https://kwant.net.pl/produkt/580` -> canonical `/produkt/<slug>-580` | Product `id=580` must match; product-level `stock` is **central** stock |
| Selected branch | `GET https://services.kwant.net.pl/api/front/products/580/current?depstock=205` | Root `product_id=580`; nested `department_stock.department_id=205`; nested `stock` is selected-branch quantity |
| Branch directory | Public `/lista-hurtowni-elektrycznych` Next pageProps `departments.list[]` | `department_id` is `ProviderBranch.branchId`; `name/postcode/street` are display metadata |
| **All branch stock — run #18** | **`GET https://services.kwant.net.pl/api/front/products/580/departments` with observed query-name `extended`** | Identity bound by singular request path; 21/21 directory IDs, one numeric `stock` each, explicit zeros, 205 = 424. Query value/semantics were not inferred |
| Branch aggregate | Public product/listing UI `W oddziałach: N szt.` | **Not** a breakdown; historical aggregate; no guarantee that its scope equals the directory |

The exact-product page's central stock must never be added to local or aggregate
stock. The `/current?depstock` response is scoped to **one** department, not to all
departments. The separately observed `/products/580/departments` response is
a **one-shot complete directory stock list for product 580**. Do not infer other
product availability, optional query-parameter semantics, authentication
requirements or any guessed URLs from this control-product observation.

### New browser evidence capture

The existing manual-only workflow `KWANT live contract probe` now also navigates
to the *known* `/produkt/580` route, checks canonical product identity, and
attempts harmless clicks on visible `W oddziałach`, pickup, and availability
labels. It records only actually observed public `xhr/fetch` JSON responses on
the exact trusted `services.kwant.net.pl` and `kwant.net.pl` hosts, together with sanitized method,
path, query/body field names and safe values, status, response top-level field
names, and bounded stock-shaped department rows. No guessed endpoint calls.

To reproduce, manually dispatch the existing `.github/workflows/kwant-live-contract.yml`
workflow selecting **this research branch** and the defaults `580`, `MBN116E`,
`Nowy Sącz`. Download `kwant-live-contract-summary` (3-day retention) and inspect
`summary.json > locationsResearch`. Do not commit raw HTML, complete JSON,
cookies, secret-bearing URLs, or account data. The existing raw HTML artifacts
are short-lived (1 day). This research does **not** make CI contact live KWANT.

The observer exposes `productIdentityMatches`, `branchRows[]`, live-directory ID
membership and **candidate** `candidateField` / `candidateValue` /
`candidateState` for `known_zero`, `known_positive`,
`unknown_null` and `invalid`. The explicit bounded diagnostic field
vocabulary accepts `stock`, `stock_num`, `stockNum` and other
clearly stock-shaped spellings, not arbitrary quantity or account fields.
The identity probe also accepts `department_id`, `department_stock_id`,
`departmentId` and `departmentStockId`. Numeric IDs are joined **only**
to the verified public directory. Field names and numeric values are hypotheses
about inventory, not production-authoritative stock facts. Unknown/omitted IDs
or missing field values never imply zero.

`observedResponses[]` preserves all bounded sanitized frontend responses;
`strongestCandidate` is a separate deterministic diagnostic ranking by:
exact product identity, at least two verified directory IDs, numeric candidate
fields, availability-UI trigger and broader directory coverage. The source
endpoint name/URL never supplies a confidence score. A missing exact product ID
is **not** equivalent to verified identity. `oneShotPerBranchResponseObserved` is true only when the research classifier
establishes a product-bound one-shot response. Run #18's initial `F_INCONCLUSIVE`
was caused by incorrectly requiring the response itself to repeat `product_id`
despite the singular, verified request path. The fix preserves separate identity
evidence without trusting generic, batch or unrelated click-timing requests.

For an identity-verified structural candidate, `completeness` reports
`ALL_DIRECTORY_BRANCHES` (all live directory IDs represented),
`POSITIVE_ONLY_CANDIDATE` (proper subset, only numeric positive
candidate quantities), `PARTIAL` (other incomplete subset), or
`UNKNOWN` (insufficient product/branch evidence). This is a
**directory-ID coverage diagnostic**, not a statement that a stock field has
verified logistics semantics. Omitted branches are never classified as zero.
`sumConfirmedObservedBranchStock` is a diagnostic candidate-value sum
only when *every* observed row uniquely maps to a directory ID and has one
valid, nonnegative numeric candidate field, with matching product identity **or independently verified one-product request
scope**; it may be a **partial** subtotal. Duplicate fields, unknown/null/invalid
quantities, extra unknown branch IDs or unverified product identity block it.

`aggregateReconciliation` is `MATCH` or `MISMATCH`
only for all-directory-branches coverage, unambiguous numeric candidate
quantities, verified product scope and a strictly parsed public
`W oddziałach: N szt.` aggregate. Other identity-verified but
incomparable cases become `NOT_COMPARABLE`; without a
credible candidate they remain `NOT_EVALUATED`. Neither state
proves that a candidate field corresponds to the storefront's actual
per-branch sellable stock; matching an aggregate is diagnostic, not a contract.
Never force equality or invent the reason for a mismatch.

`searchRankingEvidence` distinguishes `NO_STOCK_FIELD_OBSERVED`,
`CENTRAL_OR_AGGREGATE_LOOKING_ONLY`,
`STOCK_SHAPED_SCOPE_UNKNOWN` and
`SELECTED_BRANCH_STOCK_PROVEN_FOR_CONTROL`. The last classification
requires an exact product-and-branch hit candidate **corroborated by an
independently observed existing `/current?depstock` response** for the same
control product, branch and quantity. Even then this proves only the observed
control, not all search hits. Stock-like fields alone never justify local
stock-first ranking. The search classification is not an Advisor authorization.

An unrecognized JSON shape is **unknown**, not an all-branch contract.
Run #18 supplies the specific positive evidence described below; no subsequent
manual live run of the corrected classifier is claimed.

### Evidence status after run #18

- **Confirmed:** product-bound one-shot `GET /api/front/products/580/departments`, response with 21 distinct numeric `department_id` values matching the live 21-branch directory, 21 numeric `stock` values, zero-stock rows, and local branch 205 = 424.
- **Confirmed:** available-only UI filter changed displayed directory branches from 21 to 18, hid zero-stock rows and generated **no additional API request**. The browser filtered the already-loaded complete list client-side in this run.
- **Confirmed separately:** selected-branch search `POST /api/front/search-engine/page` with `depstock:205`, corroborated against identity-matched `GET /api/front/products/580/current?depstock=205` for the control product.
- **Unknown:** whether `extended` changes the returned scope/fields, other products' behavior, cross-session auth/cookie/branch-context independence, or freshness/stock reservations. Do not generalize beyond the observed public frontend contract.
- **Unreconciled:** no safely bound `W oddziałach` aggregate from the control product to compare with the full response; aggregate mismatch or match must not be fabricated.
- **Rejected:** generic/related-product quantities and UI-timing-only evidence cannot identify the control product. A missing branch is **unknown**, never zero.
- **Unproven alternative:** `GET /api/front/products/prices/<ids>?depstock=205` has batch-shaped research evidence but is not needed for the simplest manual-search path.

## First live run — verified observation and unresolved availability (9 October 2026)

Manual GitHub Actions \`KWANT live contract probe\` run
[\`37967702694\`](https://github.com/lukaszpeciak91-sys/towarownik/actions/runs/37967702694),
at commit \`afe62f2d29e212f62384587ece34b9bd1c630e4e\`,
**completed successfully**. It establishes the following observations for
public control product 580 / MBN116E/HAG / selected Nowy Sącz branch 205:

- The selected-branch search request is \`POST /api/front/search-engine/page\`
  on \`services.kwant.net.pl\` with **\`depstock: 205\`** in the public
  request body. The control hit contains \`department_stock.department_id=205\`
  and stock-shaped \`stock\` and \`stock_num\` fields.
- Independently observed \`GET /api/front/products/580/current?depstock=205\`
  matched the same product, branch and quantity. Search probe verdict:
  \`SELECTED_BRANCH_STOCK_PROVEN_FOR_CONTROL\`. This does **not** prove
  all other search hits or branches.
- A public batch request was also observed with shape
  \`GET /api/front/products/prices/<multiple-product-IDs>?depstock=205\`.
  The first run did not conclusively validate per-row product identity and
  completeness of that batch response; it remains a candidate for future
  bounded stock enrichment, not a production dependency.
- The first probe **did not open** the cross-branch availability UI, so
  no A–E location contract was verified. The real product page showed
  a non-cart button whose accessible label is
  **\`Sprawdź stan i kup towar w oddziałach Kwant\`** and text equivalent to
  \`Pokaż tylko oddziały w których produkt jest dostępny\`.
  This button is the target of the next *manual* browser probe.
- Historical generic whole-page text regexes produced e.g.
  \`selectedBranchStock=127 szt.\` and \`centralStock=5699 szt.\`
  from unrelated page text. These figures are **rejected** as exact
  product facts and should not be reused as stocks. The current-product
  response showed selected-branch 424 in this run. Exact structured
  main-product \`product.stock\` is the appropriate separate central source,
  rather than any occurrence of a \`Centrala\` label in body text.

### Live run #17 — availability control DOM diagnosis (9 October 2026)

The next real manual workflow
[\`37974563999\`](https://github.com/lukaszpeciak91-sys/towarownik/actions/runs/37974563999)
completed successfully at
\`86471d5dace4fd3c9ffab4f1f4b66eb21130b765\`, but still reported
\`F_INCONCLUSIVE\`: the availability view was **not** activated.
Its raw product HTML established that the markup separates the clickable
ancestor from its labelled child:

\`\`\`html
<div role="button" tabindex="0">
  <div aria-label="Sprawdź stan i kup towar w oddziałach Kwant">
    424 szt. w Nowy Sącz
  </div>
</div>
\`\`\`

Two descendants share that \`aria-label\`; the other belongs to a
\`Zapytaj eksperta\` row. A role/button locator searching for this
accessible name therefore missed the real clickable element.
The safe run also reported \`branchPageIdentifier=205\` from the
publicly resolved branch before locations research, but
\`locationsResearch.selectedBranchStock=UNKNOWN\` because the function
was passed \`departmentStockId\` *before* its later cookie mapping set it
to 205. Independent selected-branch search/current corroboration and the
exact structured central-stock evidence remain intact.

**No all-branch inventory API was identified in run #17**. The
HTML is evidence for how to open the UI, not evidence for the API it may
invoke.

### Instrumentation after run #17 (requires another manual live probe)

- **Exact selector:** Playwright
  \`page.locator('[aria-label="Sprawdź stan i kup towar w oddziałach Kwant"]')\`
  on the canonicalized, identity-verified product page. For **each**
  visible descendant, resolve its closest
  \`ancestor::*[@role='button'][1]\`; require visible, enabled, uniquely
  matching **selected-branch inventory row** text containing the public
  branch label (e.g. \`424 szt. w Nowy Sącz\`). Reject
  \`Zapytaj eksperta\`, contact/purchase rows and multiple eligible matches.
  Never select by DOM order or click the labelled child. Dedicated
  recording action: \`locations:open-branches\`. No checkout/cart click.
- **Early branch ID:** require confirmed deterministic branch selection,
  numeric branch ID from its resolved public branch URL and matching
  ID+label in the live public directory **before** invoking locations
  research. If any element disagrees, the ID remains unknown.
  Later cookie-constructor evidence can still populate the normal safe
  \`departmentStockId\` report, but cannot be a prerequisite for
  \`selectedBranchStock\`. Exact \`/current?depstock\` product and
  department identity gates remain unchanged.
- Sanitized \`xhr/fetch\` recordings preserve availability responses and
  reserve capacity after ordinary search traffic. A scoped visible
  availability dialog/drawer snapshot reports branch names **as display
  evidence only**, explicit numeric \`data-*\` branch IDs when mapped to the
  verified directory, quantity/zero labels and separate central label.
  No full page text is treated as per-branch stock.
- If the harmless accessible **\`Pokaż tylko oddziały ...\`** filter can be
  identified, it is toggled once. \`REQUEST_TRIGGERED\` versus
  \`NO_API_REQUEST_OBSERVED\` distinguishes an observed new request from
  a likely client-side filter; UI counts/zero-label visibility are compared.
  It is not possible to infer backend completeness from the label alone.
- The probe's \`contractClassification.type\` is research-only:
  \`A_ONE_SHOT_ALL_BRANCHES\`, \`B_ONE_SHOT_POSITIVE_ONLY\`,
  \`C_FRONTEND_PRELOADED\`, \`D_MULTI_REQUEST_BOUNDED\`,
  \`E_PER_BRANCH_FANOUT\`, or \`F_INCONCLUSIVE\`. A/B are *structural*
  candidates (B positive-subset coverage is **not** proof that omitted
  branches have zero stock), C requires an exact structured product page,
  D bounded multi-response coverage, and E at least three different
  product-bound, branch-scoped requests. Product binding must be verified
  from exact response product identity, a singular product-ID request path,
  or matching body identity; a generic branch directory alone cannot pass.
  A match must still pass a manual endpoint-level audit before production.
- The new \`locationsResearch\` inventory diagnostics distinguish:
  \`selectedBranchStockSource=CURRENT_IDENTITY_AND_DEPSTOCK_VERIFIED\`
  (exact root product ID, matching \`department_stock.department_id\`,
  trusted request host/path/\`depstock\`) and
  \`centralStockSource=NEXT_DATA_EXACT_PRODUCT_ID\`
  (main Next pageProps product, exact \`id\`). Aggregate remains
  \`UNKNOWN\` unless explicitly present *inside the exact availability
  control*, never regexed from the page body. The top-level safe summary
  and its nested before/after snapshots no longer present contaminated
  general-body stock figures.
- Batch-prices evidence captures only bounded row identity and nested
  department stock shapes, with independent IDs per row; a record's
  positional order is not proof of the requested product. This does
  **not** supersede search-with-\`depstock\` when that produces
  independently corroborated control stock.

**Next required gate:** dispatch the updated manual workflow from PR #98
*after these fixes* and audit sanitized \`locationsResearch\` only if the
correct stock-row parent opens. **The all-branches contract remains
unresolved; there is no new post-fix live finding.** No guessed endpoints
or production network calls were added.

## Live run #18 — confirmed public frontend contract (9 October 2026)

Manual [KWANT live contract probe #37978509979](https://github.com/lukaszpeciak91-sys/towarownik/actions/runs/37978509979) **succeeded**, head `c0a861a10ef651b8407baa58e43367923ebb1920`. After clicking the verified exact-product availability row for **580 / MBN116E/HAG**, the frontend issued:

```http
GET https://services.kwant.net.pl/api/front/products/580/departments?extended
```

The safe network observer recorded the query field name **`extended`**; no semantics or value are invented. Response top-level fields were `list`, `total_stock`, `unit`. The `list` contained **21 rows**, with **21/21 distinct numeric `department_id` values present in the public branch directory**, and one numeric `stock` per row. Several rows contained **literal `0`**, rather than null or omission. **Nowy Sącz / `department_id=205` had `stock=424`**, matching the previously independently verified selected-branch current response. The product identity is fixed by the **singular request path** `/products/580/departments`, even though response top-level `product_id` is omitted.

The availability UI displayed **all 21** directory names. Toggling **„Pokaż tylko oddziały w których produkt jest dostępny”** changed the visible set to **18**, removed zero-stock rows and issued **no new API request**: client-side filtering over the already-fetched full list was observed.

**Contract verdict: `A_ONE_SHOT_ALL_BRANCHES` / `PRODUCT_BOUND_FULL_DIRECTORY_RESPONSE`.** The original run's `F_INCONCLUSIVE` was a probe **identity-propagation false negative**, not missing frontend evidence. Research-only fix passes independently verified request product scope to completeness evaluation and candidate ranking without changing response-internal identity handling. No subsequent live run of that fix has been performed.

This public frontend contract is a credible foundation for a **separate future production `find_product_locations` PR**; provider interface, Android client, Worker, Advisor and UI remain untouched here. Retain a required production gate for exact product ID, trusted URL/host, numeric directory ID, explicit zero/null/missing semantics, bounded single-product lookup and separate central stock.

### Separate manual-search conclusion

Run #18 also preserved `searchRequestDepstockObserved=true` and `SELECTED_BRANCH_STOCK_PROVEN_FOR_CONTROL`: `POST /api/front/search-engine/page` with selected `depstock=205` provided a product-580 local-stock field independently corroborated by `/products/580/current?depstock=205`. **The future simplest manual search is search with selected `depstock`, then relevance-bounded local-stock ranking, then exact verification of surfaced cards.** This does not require calling the all-branch endpoint for every search hit. Batch `/products/prices/<ids>?depstock=205` remains optional structural research only.

## Sanitized example (SYNTHETIC, not a captured KWANT response)

The test fixture below describes the **observer's** safe output shape.
Do not mistake it for a public endpoint schema or real stock quantities.

```json
{
  "method": "GET",
  "host": "services.kwant.net.pl",
  "path": "<only a path emitted by the actual browser>",
  "shape": {
    "productIdentityMatches": true,
    "branchRows": [
      {"branchId": "205", "branchIdField": "department_id",
       "branchNameFromDirectory": "Nowy Sącz", "branchKnownInDirectory": true,
       "jsonPath": "$.branches[]", "candidateField": "stock_num",
       "candidateValue": 0, "candidateState": "known_zero"},
      {"branchId": "204", "branchIdField": "departmentId",
       "branchNameFromDirectory": "Tarnów", "branchKnownInDirectory": true,
       "jsonPath": "$.branches[]", "candidateField": "stockNum",
       "candidateValue": 18, "candidateState": "known_positive"}
    ]
  }
}
```

## Request-count economics

**Existing selected-branch contract:**
one search POST for candidate identities; each independently exact-verified
KWANT product costs a canonical product-page GET plus one `/current?depstock`
GET, with shared public directory metadata acquired once per lookup scope.
A stock-only probe for one *known* product and branch costs **one** current
GET. Previously checking `B` branches via that fallback would mean `B`
requests; **run #18 instead demonstrated one bounded frontend `/departments`
GET covering 21/21 branches for product 580**. Do not implement
per-branch fan-out for multi-location inventory.

**Manual-search recommendation (conditional):**

1. Discover bounded candidates via the known search POST, keep textual relevance
   and stable product IDs; do not interpret a search hit as a verified card.
2. First validate from actual captured hit fields whether any stock is
   branch-scoped, identity-keyed, and trustworthy. If yes, rank positive
   selected-branch stock first, zero after, unknown last; keep relevance as a
   stable tie-breaker. Do not rank on aggregate or central stock instead.
3. If hits do **not** provide selected-branch stock and no batch endpoint is
   proven, restrict early stock checks to a **small relevance-bounded pool**
   (e.g. at most 5–8 candidates). Each requires one existing
   `/current?depstock` request, identity-checking root and branch; cache those
   observations **within that one query** to avoid duplicate stock calls during
   exact verification. All unknowns remain unknown.
4. Exact-verify the visible top items by canonical product page and
   identity-matched branch stock; avoid claiming the entire catalogue has been
   stock-sorted. No production changes in this PR.

The **observed** `/departments` lookup costs one frontend request for the
verified control product and returns its 21-branch breakdown; product identity
discovery may cost additional requests. Manual-search selected-branch evidence
comes directly from search with `depstock`; no need to fan out or call
`/departments` for every hit. No latency benchmark is claimed.

## Proposed future provider contract (not implemented)

```kotlin
// Research sketch only; not a production API.
sealed interface ProductLocationsResult {
    data class Available(
        val productRef: ProductRef,
        val locations: List<LocationStock>,
        val centralStock: Int?,   // separate, independently verified
        val verifiedAt: Instant,
        val coverage: Coverage,   // ALL_BRANCHES / POSITIVE_ONLY / UNKNOWN
    ) : ProductLocationsResult
    data class Unavailable(val reason: Failure) : ProductLocationsResult
    data object Unsupported : ProductLocationsResult
}
data class LocationStock(
    val branchId: BranchId,
    val branchName: String,       // join against real ProviderBranch directory
    val stock: Int?,              // 0 != null
)
```

The parsing layer owns identity validation and transport-to-domain mapping.
Display sorting, showing positive branches first, central as a **separate**
line, hiding zeros vs showing unknowns, and pagination/limits belong above
provider transport. No synthesized combined total or fabricated branches.

**Advisor recommendation:** introduce a distinct provider-neutral
`find_product_locations` tool, initially implemented only by KWANT in a
**separate production PR** using the verified, bounded public
`/products/{productId}/departments` contract. OBI returns explicit `unsupported_provider`
until a separate OBI contract is proven. Do **not** overload normal
`find_products` discovery: only explicit multi-location logistics intent for
a **verified exact product identity** should permit this lookup. Android keeps
control of provider/branch authorization, resolves references from verified
current-turn snapshots, and forwards bounded sanitized location facts to the
Worker. Do not allow the model to invent product IDs or branch IDs; do not
silently switch WorkingProfile.

## Non-goals and acceptance gate

No Android app changes, Worker code, model prompts, protocols, Room, UI,
or production network calls in this PR. The required one-shot observation,
21/21 live directory mapping and explicit zeros were obtained in run #18.
Before production: independently validate stable scope across more products
and branch contexts, bound request/response costs, confirm public access,
treat missing/null/mismatch as unknown, and reconcile any safely attributed
aggregate without forcing equality. Reject unbounded branch fan-out.
