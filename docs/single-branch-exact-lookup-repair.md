# Authorized single-branch exact lookup repair

Base main: `dfee248a86049a10d2d70add2bafd7189517c087`.
Design input: [single-branch lookup findings](single-branch-lookup-findings.md).

## RED and root cause

The existing provider contract already accepts `lookup(ProductRef, BranchId)`. OBI implements market-specific product retrieval; KWANT implements product HTML verification followed by `/current?depstock=BRANCH`. The missing decision was in `AdvisorLocationsTool.execute(authority)`: every authorized inventory operation used the locations service, even when `fullNetwork=false` and there was exactly one branch. KWANT consequently fetched `/departments?extended=1` for those singleton requests.

Before changing routing, the permanent `SingleBranchExactLookupTest` suite ran against main behavior with only injectable provider/clock dependencies added. **13 methods ran: 3 passed, 10 failed.** The routing witness printed:

```text
SINGLETON_ROUTE exact=[] inventory=[[BranchId(value=205)]] stock=7
```

The expected fresh exact fixture quantity was 17; historical quantity was 999. These are synthetic controls, not live inventory. Other RED methods covered different target branches, OBI lookup, null/failure, cancellation and identity mismatch. Multiple/network scopes and authorization negatives already passed.

Permanent remote RED checkpoint: **`ba7af82366ecd28ccd1e4df19c7972575b024ef6`**, tree **`8deb9deacf766d13c1e9c3fbd4760caa7f086e0c`**. Its tree is identical to the locally executed RED commit `f3264e32881997905803c95dd163afa67ffe485c`; the remote parent is audited main. It contains the regression test plus unused constructor injection, not the routing fix.

To reproduce RED in a normal Android build environment, check out that checkpoint and run:

```bash
./gradlew :app:testDebugUnitTest --tests '*.SingleBranchExactLookupTest'
```

This verifies a routing defect against the required contract. It does **not** identify the original device/API cause of INC-008 UNKNOWN.

## Minimal implementation

Only two production files change:

- `agent/AdvisorLocationsTool.kt`: after P0-B typed authorization, `!authority.fullNetwork && authority.branches.size == 1` selects `readSingleBranch`. It calls the existing provider `lookup(ref, target)` on `Dispatchers.IO`, without textual discovery or inventory fallback. `ref` comes from the independently authorized product; `target` comes from the authorized branch list.
- `agent/AdvisorController.kt`: one factory-wiring line passes the existing provider registry into the locations tool. Discovery and singleton verification therefore reuse the same provider instances and existing clients.

The result must have the same provider/product reference and target branch. A negative quantity or mismatched identity fails closed. A verified numeric quantity, including zero, receives `requested_subset` coverage and a fresh post-lookup observation timestamp. Only fresh KWANT product data may supply separate nonnegative central stock; it never substitutes for branch stock. OBI does not acquire central stock.

Null, failed, invalid or unavailable verification returns unavailable/unknown evidence with a canonical null-stock row, no returned IDs, no timestamp and no central stock. No historical quantity is copied. Cancellation propagates. If the canonical branch mapping is absent, no provider lookup runs.

Multiple branches and explicit full-network scope retain the existing adapter path, including a full-network request with only one directory entry. KWANT does not fan out into one current-stock request per branch. OBI batching and inventory limits are unchanged.

The original `find_product_locations` continuation envelope, response/call IDs and conversation profile remain unchanged. Existing product-card whitelisting and final rendering remain in place; this path does not promote historical cards or create a new card type.

## Regression evidence

- New focused routing class: **13/13 passed** after the fix. Real KWANT HTTP-client/parser fixtures assert branch 204's `/current?depstock=204` path and no search/departments request. Real OBI provider/repository/parser fixtures assert the store-selection request for 075 and 003. Other methods cover 580/205 → 580/204 → 580/205, a separately verified synthetic MBN316E identity at 206, positive/zero/null/failure, returned product/provider/branch mismatch, negative stock, cancellation, conflicting hints, ambiguous identity, unknown branches and multi/network scope.
- New compatibility class: **3/3 passed**. It checks actual Android serialization for positive, zero, null, failure and identity mismatches; canonical rendering; unchanged response/call/profile identity; exclusion of historical cards; and no fourth exact lookup after the three-call limit.
- Extended current-source native regression: **364 methods, 357 passed, seven environment blocks, no assertion failures**. The count includes both new classes, P0-B, existing follow-up/recovery/Controller suites, discovery tools, provider parsers, branch resolution, multi-location adapters and D1 diagnostics. It is not an Android CI count.
- The seven blocks are MockWebServer `SocketException: Operation not permitted`: one each in `Inc007MultiTurnRecoveryTest`, `Inc007ObiScopeRegressionTest`, `AdvisorLocationsControllerTest`, `KwantLocationWireIntegrationTest`, `AdvisorLocationHintsWireCompatibilityTest`, `ProductLocationsTest` and `AdvisorProductShapingTest`. They are not passes or ignored tests.
- **6/6 actual serialized Android singleton continuations accepted by the unchanged Worker `parseContinueRequest` validator**, with profile 205, requested branch 204 and correct zero/null values preserved.
- Node 22 `npm run check`: TypeScript check and **12/12 Worker test modules passed**. No Worker source/schema/prompt changed.
- Python deterministic tooling: **174/174 passed**.
- Local `./gradlew check --offline` could not start: `FileLockContentionHandler` / “Could not determine a usable wildcard IP for this machine.” Final-head GitHub Android checks must supply the full Gradle/MockWebServer result; the PR records the actual run and conclusion. No local AAB or assemble task was invoked.

Native execution compiled current relevant production and test source with Kotlin 2.3.21 and JUnit 4.13.2 against cached library dependencies. Controller, proxy serializer, authorization, provider/client/parser and tool implementations were compiled from current source, without cached application Kotlin classes or production stubs. For the D1 response-store dependency, its unchanged JVM-only class and projection were extracted verbatim from the current source; Android UI/Room integration is reserved for full CI.

Canonical final verification commands in a normal environment:

```bash
./gradlew :app:testDebugUnitTest --tests '*.SingleBranchExactLookupTest' --tests '*.SingleBranchLookupCompatibilityTest'
./gradlew check assembleDebug
PYTHONPATH=tools python3 -m unittest discover -s tools -p 'test_*.py'
(cd proxy && npm run check)
```

Existing singleton orchestration fixtures now inject fresh ordinary-provider results, without delegating those lookups back to inventory adapters. Their authorization negatives, identity selection and budgets remain checked. Endpoint-specific D1 and wire fixtures explicitly request a network with one canonical entry so they continue testing the unchanged inventory adapter and report contract. Multi-location adapter fixtures remain on inventory. The changed assertions identify the new retrieval route; no authorization negative was relaxed.

## Security, compatibility and remaining limits

P0-B remains the sole admission boundary. Model IDs/hints cannot create authority or veto a valid independently resolved USER selection. Current explicit USER identity still overrides history; new/ambiguous products use unchanged discovery. No profile save, budget increase, hidden retry or fallback to inventory/stale stock was introduced.

No BranchResolver/reference/inflection/regex, Worker prompt/schema/tool, provider HTTP/parser, diagnostic implementation, UI, database or application-version code changes. The exact provider API does not expose the inventory transport diagnostic metadata, so singleton results honestly leave that optional local metadata unavailable; no diagnostic protocol was added.

This repair is limited to already-authorized requests. Unsupported natural-language phrasing and unresolved references still reject/clarify through the existing logic. Fresh exact lookup can fail and then stock remains UNKNOWN; UNKNOWN never means zero. Full KWANT product verification includes directory/product HTML work as well as current stock; neither fewer requests nor better live reliability is claimed. Fixture product/branch mappings and quantities are controlled evidence, not live observations.

No live model/provider requests, merge, deployment, AAB or release work is part of this change. Stop after the focused PR and final-head CI for independent audit.
