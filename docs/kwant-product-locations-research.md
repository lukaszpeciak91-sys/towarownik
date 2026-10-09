# KWANT: product availability across locations — live-contract research

Status: **INCONCLUSIVE — a per-branch one-shot endpoint has not been verified** (9 October 2026).
This document separates existing repository-backed live evidence from questions still requiring a
new **manual** Playwright network observation. Do not use this document as permission
to implement a production stock endpoint or to invent branch quantities.

## Evidence and reproduction

The repository already records live-tested frontend paths (see
`docs/decisions.md`, 2026-10-06 and `KwantProductProvider.kt`):

| Operation | Observed public contract | Trust limitations |
| --- | --- | --- |
| Product discovery | `POST https://services.kwant.net.pl/api/front/search-engine/page`; JSON `q,page,limit,tags` | Returns `hits[]` with product `id/slug/code/name`; all candidates still require exact verification |
| Canonical product | `GET https://kwant.net.pl/produkt/580` -> canonical `/produkt/<slug>-580` | Product `id=580` must match; product-level `stock` is **central** stock |
| Selected branch | `GET https://services.kwant.net.pl/api/front/products/580/current?depstock=205` | Root `product_id=580`; nested `department_stock.department_id=205`; nested `stock` is selected-branch quantity |
| Branch directory | Public `/lista-hurtowni-elektrycznych` Next pageProps `departments.list[]` | `department_id` is `ProviderBranch.branchId`; `name/postcode/street` are display metadata |
| Branch aggregate | Public product/listing UI `W oddziałach: N szt.` | **Not** a breakdown; historical aggregate; no guarantee that its scope equals the directory |

The exact-product page's central stock must never be added to local or aggregate
stock. The `/current?depstock` response is scoped to **one** department, not to all
departments. No discovered live evidence currently proves endpoints of types
A–D (one-shot list, existing all-branch response, page structured payload, modal
API), nor rules out type E (one call per department). In particular, **do not infer**
an all-departments endpoint by removing `depstock` or guessing new paths.

### New browser evidence capture

The existing manual-only workflow `KWANT live contract probe` now also navigates
to the *known* `/produkt/580` route, checks canonical product identity, and
attempts harmless clicks on visible `W oddziałach`, pickup, and availability
labels. It records only actually observed public `xhr/fetch` JSON responses on
the exact trusted `services.kwant.net.pl` host, together with sanitized method,
path, query/body field names and safe values, status, response top-level field
names, and bounded stock-shaped department rows. No guessed endpoint calls.

To reproduce, manually dispatch the existing `.github/workflows/kwant-live-contract.yml`
workflow selecting **this research branch** and the defaults `580`, `MBN116E`,
`Nowy Sącz`. Download `kwant-live-contract-summary` (3-day retention) and inspect
`summary.json > locationsResearch`. Do not commit raw HTML, complete JSON,
cookies, secret-bearing URLs, or account data. The existing raw HTML artifacts
are short-lived (1 day). This research does **not** make CI contact live KWANT.

The observer exposes `productIdentityMatches`, `branchRows[]`, directory-ID
membership, confirmed `known_zero` / `known_positive` / `unknown_null` /
`invalid` field distinctions and search-hit field names. An unrecognized JSON
shape is **unknown**, not an all-branch contract. An observed response containing
multiple different directory IDs is only a *candidate* until product identity,
branch scope, and completeness are proven. A browser navigation/click error is
not proof that no public endpoint exists.

### Evidence checklist still open

- **A/B/C/D vs E:** no one-shot all-branch request verified; frontend modal
  response method/host/path/body/stock field remain unconfirmed.
- **Branch IDs/names:** `department_id` → `BranchId` is verified for directory
  metadata, with historical examples Nowy Sącz `205`, Tarnów `204`,
  Rzeszów `20`, Kraków `210`. All new availability response IDs must be
  joined **only** to the live directory, not inferred from display text.
- **Zero/missing semantics:** confirmed `0` from the existing selected-branch
  endpoint is real zero; missing, null, mismatched ID, failed request, or
  malformed stock is **unknown**. A future all-branch result requires its own
  zero-vs-omitted-branch validation. The observer never interprets omission as zero.
- **Central:** structured product `product.stock` is a separate central value.
  It is not a branch, even if a future API includes it in the same body.
- **Aggregate consistency:** no same-time complete per-branch list captured;
  therefore `sum(confirmed branch stocks)` vs `W oddziałach` cannot be evaluated.
  Do not reconcile with synthetic values. Potential exclusions, reservations,
  update latency, and scope differences are hypotheses, not findings.
- **Scope/auth:** public unauthenticated product and selected-branch queries are
  already used by Android. The new all-branch workflow is not verified for
  authentication, cookies, selected WorkingProfile or changed branch context.
- **Full vs positive-stock-only:** not verified. An absent directory branch
  must remain unknown, not zero.
- **Search payload:** Android consumes `hits[].id/slug/code/name`; whether raw
  search hits expose reliable selected-branch stock, central/aggregate fields,
  or a batch inventory service has **not** been live-confirmed. Captured
  `searchHitFields` and `searchHitStockFields` are intended to settle this.

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
      {"branchId": "205", "branchNameFromDirectory": "Nowy Sącz",
       "branchKnownInDirectory": true, "stock": 0, "stockState": "known_zero"},
      {"branchId": "204", "branchNameFromDirectory": "Tarnów",
       "branchKnownInDirectory": true, "stock": 18, "stockState": "known_positive"}
    ]
  }
}
```

## Request-count economics

**Existing selected-branch contract:**
one search POST for candidate identities; each independently exact-verified
KWANT product costs a canonical product-page GET plus one `/current?depstock`
GET, with shared public directory metadata acquired once per lookup scope.
A stock-only probe for one *known* product and known branch costs **one** current
GET; for `B` departments it costs **B** GETs unless another contract is proven.
For product 580, requesting all directory branches by this fallback would mean
`B` requests, not a bounded one-shot call. Do not implement unbounded fan-out.

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

A future proven one-product/all-branches API would be roughly **one** request
per known product (plus identity lookup if needed); absent that, the cost is
`B` requests per product. This is an estimate, not a measured performance
benchmark. Search-hit stock or batch stock benefits must be validated before use.

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
`find_product_locations` tool, initially implemented only by KWANT once the
endpoint is verified and bounded. OBI returns explicit `unsupported_provider`
until a separate OBI contract is proven. Do **not** overload normal
`find_products` discovery: only explicit multi-location logistics intent for
a **verified exact product identity** should permit this lookup. Android keeps
control of provider/branch authorization, resolves references from verified
current-turn snapshots, and forwards bounded sanitized location facts to the
Worker. Do not allow the model to invent product IDs or branch IDs; do not
silently switch WorkingProfile.

## Non-goals and acceptance gate

No Android app changes, Worker code, model prompts, protocols, Room, UI,
or production network calls in this PR. Before a production PR: obtain the
actual sanitized Playwright evidence, verify the one-shot response against
the live directory including zero/unknown and completeness, compare a
same-time full sum to public aggregate without forcing equality, and test
branch-context/cookie independence and unauthenticated access. If the one-shot
contract is absent, explicitly reject an unbounded per-branch implementation.
