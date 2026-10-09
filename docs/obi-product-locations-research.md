# OBI product availability across markets — research

**Status: F_INCONCLUSIVE — no new live browser run yet (9 October 2026).**
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

## OBSERVED BUT NOT YET PRODUCTION-TRUSTED

**None** from the new OBI interactive phase yet. The next live run must
discover the exact visible store/availability controls and actual
frontend-generated requests. This PR does not claim OBI supports one-shot
multi-store inventory.

## UNKNOWN: first-run research questions

| Subject | Current status |
| --- | --- |
| Cross-market availability UI / exact accessible label | UNKNOWN |
| Browser-requested host, method, path, query/body names | UNKNOWN |
| Response OBIK and store identity binding | UNKNOWN |
| One-shot all stores, subset, preload, bounded requests or fan-out | UNKNOWN |
| Exact numeric stock versus qualitative availability | UNKNOWN |
| Explicit zero, unavailable, null and omitted stores | UNKNOWN |
| Store switching versus read-only checking; persistent state effects | UNKNOWN |
| Multi-store product-owned initial __NUXT_DATA__ | UNKNOWN |
| Request count to check several markets | UNKNOWN |

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

## Manual workflow and first run

Existing workflow: **OBI live contract probe**
(.github/workflows/obi-live-contract.yml), dispatched manually against
branch **research/obi-product-locations-contract**.

| Workflow input | First-run value |
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
2. Inspect bounded, visible/enabled accessible control metadata for
   market, availability and pickup. No guessed exact Polish labels.
3. Click a single unambiguous harmless store/availability selector if
   one is found, else record the candidates and stop safely.
4. If the selector reveals uniquely identified canonical market rows,
   try at most two real additional markets. Never match only a city.
5. Record trusted browser-generated request shapes, product/store
   identity evidence and before/after browser state-change flags.
   Treat unknown/missing controls or blocked product page as F, not
   proof no contract exists.

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
remains F_INCONCLUSIVE. After first live run, inspect saved evidence,
adjust targeting only from actual observed DOM, and re-run if needed.

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

**Exit gate:** perform and audit at least one real manual OBI browser
probe, establish actual product/store identity and request semantics,
then plan production separately. **Do not merge this research PR.**
