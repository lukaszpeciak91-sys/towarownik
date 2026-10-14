# Progress

## 2026-10-14 — INC-007 R1 OBI recovery (independent audit pending)

Base/latest main verified as `fe9e8d79f1a736cd4cb2289b525d5bc872ce554b`, including merged INC-006 #123/#124. AGENTS, source/contracts, complete INC-007 report and original reproduction were read. Reference SHA-256: report `c0477907f1021d0d479a154ad36f73b904131f41781918dc16dacac3db8506ff`; script `62759f79d43ca3956c0ef3c6a8cee4a635557b0ddb7a0ab19c36e6559c9715df`. Re-ran the pinned script: compile exit 0; existing native **215 PASS / 0 FAIL / 0 SKIP**; original **17 total / 12 PASS / 5 FAIL / 0 SKIP**, focused JVM exit 1.

Test-only local RED commit `bf64921ce3b4171090ecc6572f55a5e5e07a8c51` (tree `923debce4b9948694f32b863fe662fbff92e5cb6`) preserves the pre-repair regression fixtures: **20 total / 10 PASS / 10 FAIL / 0 SKIP**. Remote PR ancestry preserves the identical RED tree; its commit identity and final SHA are recorded in the PR. RED includes all 40 bare OBIKs rejected, exact two-turn discovery failure, trusted plural inventory incorrectly checking 075, unknown adjective/broad-scope authorization and supported EAN rejection. Do not silently remove these witnesses.

Patched only BranchResolver, AdvisorController and AdvisorLocationsTool; added permanent Inc007ObiScopeRegressionTest. Existing canonical directory confirms Kraków 003/019/059/072 (ObiStores.kt), with IDs dynamically derived in production. Current profile 075 is preserved on continuation while fresh identity is verified in seed 003; separate fresh inventory checks the complete city set. Two-call discovery/inventory and three-call rejected-inventory/discovery/retry paths retain exact response/call IDs and the existing budget. Model hints 075/003/999 and empty/003/075/Atlantyda locations cannot alter USER scope.

Actual final native results: **25/25 focused PASS**, **215/215 existing PASS**, **0 SKIP**. Original 17 diagnostic fixtures/expectations are unchanged: **15 PASS / 2 expected RED / 0 SKIP**. The original script deliberately reads git objects at its pinned HEAD; final replay uses its byte-identical Inc007E2eTest/Inc007Runner helpers with refreshed current production copies. It is not claimed that the pinned script itself accepts a different HEAD. Intermediate strict noun parsing caused 26 legacy failures, then one legitimate “w markecie OBI 075 ten produkt” failure; the bounded canonical-label suffix was repaired without deleting/respecifying existing tests. A negative directory fixture's Success cast was corrected to inspect the actual Error state. Final security witnesses also reject wrong freshly discovered identity before stock and prevent a false verification claim.

Executed commands (paths shown relative to the evidence workspace; all provider responses synthetic or loopback):

```bash
python3 reference-reproduce.py --repo ../taksula-inc007 \
  --deps ../inc007-evidence/deps --out pinned-red
# From current/: copies actual production Kotlin; disables only Android factory/network wiring.
python3 refresh_sources.py
python3 run_native.py
java -cp 'green-legacy-classes:deps/*' pl.lukaszpeciak.towarownik.agent.R1RunnerKt
java -cp 'green-legacy-classes:deps/*' pl.lukaszpeciak.towarownik.agent.R2NativeRunnerKt
java -cp 'green-legacy-classes:deps/*' pl.lukaszpeciak.towarownik.agent.Inc007RunnerKt
# Node 22.23.3, actual serialized synthetic payloads, unchanged compiled Worker:
node --test wire-replay.mjs
# Reproducible repository equivalent for permanent focused suite:
./gradlew :app:testDebugUnitTest --tests '*.Inc007ObiScopeRegressionTest'
# Required complete final-head PR CI:
./gradlew check assembleDebug
```

Runtime: OpenJDK 17.0.20, Kotlin 2.3.21, coroutines 1.10.2, JUnit 4.13.2, serialization 1.9.0, OkHttp/MockWebServer 4.12.0. Real ProxyClient serializer/parser exercised in the focused suite; four actual v2/v3 continuations replayed into unchanged Worker parseContinueRequest: **4 PASS / 0 FAIL / 0 SKIP**. No schema/transport blocker. Offline Kotlin excludes Android factories, BuildConfig and attachment storage/network default wiring; it is not a full SDK/device run. Final-head CI URLs/status are recorded in the PR checks/description, not inferred from native tests. Proxy source/workflow unchanged, so its path-filtered workflow is not affected. No live requests, merge, deploy, AAB, version change or production configuration change.

| Acceptance witness | Executed result |
| --- | --- |
| Exact 6346951; 40 distinct bare seven-digit searches in one conversation | Fresh provider-verified cards in selected 075; no historical permission gate |
| 40 further independent OBI and KWANT product queries | Per-turn execution retained; MESSAGE identity/profile preserved |
| Exact original two-turn OBI history, fresh new identity then inventory | Seed 003; exact product 6346951; checked 003/019/059/072; no inventory GET for 075 |
| Trusted matching product, all location hints; explicit all-markets positive control | Complete canonical Kraków scope, not hint-selected subset |
| Premature stock tool without identity | Rejection, actual discovery, fresh inventory within three calls |
| Singular Kraków; unknown/conflicting city/market; wrong provider/product | No unauthorized inventory; singular city clarifies instead of choosing |
| Missing/duplicate/empty directory; provider verification unavailable | No inventory; honest Error/unavailable/no card |
| Omitted, malformed, null or transport stock | Exact checked scope retained; unknown never rendered as zero or stock 99 |
| Model stops after identity verification | Honest incomplete full-scope statement; no city-wide stock claim |

Remaining diagnostic REDs are INC-007 R2: exact KWANT six-turn history rejects turns 4/5/6 when stock-tool routed; independent bare-code discovery variant rejects turns 4/5. No new failure. KWANT 206/204 actual stock/coverage and the separate OBI CONTINUE 502 remain unknown; no root-cause attribution or provider change here. Live model call ordering, live OBI city coverage and device product links/dedicated availability UI require controlled release verification after independent review. GO for focused PR audit only; merge requires final-head CI and independent approval, broader Android release remains NO-GO until R2 and device acceptance are complete.

## 2026-10-14 — INC-006 R2 natural follow-ups and honest local reasons (audit pending)

Preflight/main: `0b2a483a1193337032a04d32382dae74c116fe31`, merge of PR #123; ancestor check confirmed R1 `fffc2ca2b9b2a719cf696e483d38183b03ed98c6`. Rechecked main before publication. Read AGENTS, architecture/decisions/progress and exact Android/Worker location CONTINUE contracts before patching. Original INC-006 native harness remains unchanged (SHA-256 `e2ad0b2dc4f165fcce5770b093dd5f5e6bcd21944ff0c125bd0ff50f78bd3746`); reuse its offline fixture/source-extraction approach and the permanent R1 regression suite.

Test-only RED local commit `9af744026998e687bafa59f04849756313368d1d`: **18 JUnit tests, 5 PASS / 13 expected FAIL / 0 SKIP**, exit 1 on unchanged merged-R1 production Kotlin. Captured status/reason, exact synthetic product-bound reads, checked IDs, returned cards and rendered text. Witnesses include plain `w Bochni` failing `location_intent_required`, model hint 205 blocking independently selected Bochnia/203, missing product/directory/unknown-place failures leaving generic model denial visible, `u nas`/`tam` failure, and unverified article / price-only inventory safety defects. No historical live tool arguments were inferred.

Expanded runs deliberately exercise actual provider adapters and one real ProxyClient MockWebServer parser path. One intermediate **201-test / 19-failure** run exposed outdated single-city/model-veto assertions plus implementation regressions in legacy article/quantity/conflict forms; these were investigated rather than labeled baseline RED. Respecified USER-scope assertions, retained original unknown/conflicting/provider/HTTP negatives, restored valid INC-003 forms and the exact OBI 054-vs-052 no-read reason. Later added ambiguity/short-ID/latest-anchor safeguards and fresh R1 discovery -> R2 stock-read integration.

First full Android CI [38524442366](https://github.com/lukaszpeciak91-sys/towarownik/actions/runs/38524442366) at `42367fcc224784fcf3e24fb422b14f2e8dbc70ca`: **753 tests completed, 1 failed** (`AdvisorLocationHintsWireCompatibilityTest`). This was an additional obsolete wire-coverage fixture asking only “Sprawdź w Krakowie” while asserting four checked markets in one inventory read. The new single-city ambiguity rule correctly rejected it. Kept ambiguous-city/no-read coverage; made this coverage fixture explicitly ask all markets in Kraków. The real parser/serializer path is unchanged, so this is not a transport/schema blocker. Expanded native suite executed both wire classes: before that fixture correction **212 tests / 1 failure**, after correction **212/212 PASS**. Final full-head CI is recorded in PR checks/description.

Prior integration HEAD `17ebdbead91779c6cd8c51b9d1bc6dc026445799` passed full Android [38525630017](https://github.com/lukaszpeciak91-sys/towarownik/actions/runs/38525630017). Two final review scenarios then produced real supplemental RED: **214 native tests / 2 expected failures** (saved `late-boundaries-red.log`): exact OBI A “czy jest w OBI” was not an inventory anchor; a location retry after fresh same-turn discovery still saw only historical identity candidates. Added the bounded provider/profile availability-question anchor and current-first verified identity candidates, keeping USER intent/scope mandatory. Supplemental GREEN **214/214** includes rejected missing identity -> actual FindProviderProductsTool verification -> successful stock retry within the unchanged three-call budget, checking distinct response/call IDs and saved profile. Final CI must rerun on the updated HEAD; prior GREEN is not presented as final-head evidence.

Additional integration HEAD `63649f013ba1843502955a378bdf6e6edcd6645d` passed full Android [38530596017](https://github.com/lukaszpeciak91-sys/towarownik/actions/runs/38530596017). A final product-selection witness produced **215 tests / 1 expected failure** before repair (`current-fact-selection-red.log`): unrelated fresh discovery could take priority over a historical USER-selected product in a short location follow-up. Filter current facts to the independently resolved inherited product set and reject ambiguous inherited identity before inventory. Actual GREEN **215/215**, including the zero-inventory-read wrong-product test; correct same-turn product recovery remains GREEN. Final-head CI is recorded in PR #124 checks/description rather than using either prior run as final proof.

Final local native result: **215 PASS / 0 FAIL / 0 SKIP**, across 13 JUnit classes; **34** permanent focused R2 tests and **181** existing/respecified Controller/R1/location authorization/coverage/follow-up/rendering tests. A Result-based runner records actual run/failure/ignore counts independently of test console capture. Native command/reproducibility (OpenJDK 17.0.20, Kotlin 2.3.21, coroutines 1.10.2, JUnit 4.13.2; OkHttp/MockWebServer 4.12.0, Okio 3.6.0, kotlinx serialization 1.9.0):

```bash
# From inc006-r2-evidence; refresh copies current production source, retaining
# only the existing documented Android factory/network-wiring exclusions.
python3 refresh_sources.py
python3 run_native.py
java -cp 'green-legacy-classes:deps/*' \
  pl.lukaszpeciak.towarownik.agent.R2NativeRunnerKt
# Canonical repository/CI equivalent for permanent focused tests:
./gradlew :app:testDebugUnitTest --tests '*.AdvisorNaturalFollowUpRegressionTest'
# Full PR CI gate:
./gradlew check assembleDebug
```

The standalone runner compiles the actual Controller/follow-up/identity/scope/rendering/provider-adapter/ProxyClient methods. Android factories and network repository defaults are omitted or disabled; BuildConfig and attachment-storage wiring are explicit offline stubs, not credentials. Every provider request uses injected synthetic fetchers or loopback MockWebServer. This is not a local full Android SDK/Gradle/device run. CI must execute the final PR HEAD's complete `check assembleDebug`; definitive URLs/status are recorded in the focused PR description/checks.

Additional executed checks under Node **22.23.3**:

```bash
cd proxy
npm ci
npm run typecheck
npm test
npm run eval:build
node --check evals/run-taksula-behavior.mjs
# From the evidence directory against this compiled, unchanged Worker:
node --test inc006-r2-wire.test.mjs
# Repository root:
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=tools python3 -m unittest discover -s tools -p 'test_*.py'
```

Proxy **636/636 PASS**, typecheck/eval build/CLI syntax PASS; focused wire **44/44 PASS / 0 SKIP** covers v2–v5 bounded local reasons, trace propagation, coherent positive/zero/unknown stock rows; Python **174/174 PASS**. No Proxy files changed, so the path-filtered Proxy PR workflow is not required to run. These overlapping JVM/Proxy runs are not summed. Skipped locally: full Android SDK/device and live model/provider/deployment checks; these are unexecuted activities, not fabricated test PASS/SKIP counts.

| Scenario | Executed result |
| --- | --- |
| KWANT `w Bochni`, `we Włocławku`, `a w Tarnowie?` | Fresh exact-product reads in 203, 141, 204 |
| OBI `w Krakowie na Wielickiej`, prefixed variant | Unique canonical market 003 |
| `u nas`, no USER place + wrong model hint | Saved conversation KWANT 205 / OBI 075 |
| `a tam?` / repeated `tam` | Last unique USER-selected branch only; no ASSISTANT-suggestion scope |
| New R1 discovery -> `w Bochni` | Card freshly verified in 203 with stock 9; separate new product-bound stock read 7; no new historical-card promotion |
| Price/advice/unrelated turn, wrong provider, corrupt directory | No inventory HTTP |
| Unknown/ambiguous USER place, unverified new ID/article, failed new anchor with different older card | No inventory HTTP; bounded truthful reason |
| Two explicit product identities or shared article base | Ambiguous product; no model/ASSISTANT narrowing |
| `obu` for either provider | `multi_product_tool_contract`; no partial reads |
| Historical card quantity 99 versus new read 7 | Only new stock/time evidence rendered; no old stock substitution |

No Worker/model/prompt/schema/token/tool-budget/timeout/provider-API/UI/persistence changes. R1 discovery and separate multi-location stock window remain intact. No live request, secret extraction, merge, deploy, version bump or AAB. GO for independent audit after final CI; AAB 0.1.25 (26) and real-device verification remain after audit + owner merge. Old phone tool name/arguments and production behavior remain UNKNOWN.


## 2026-10-14 — INC-006 R1 core discovery routing (merged PR #123)

Base/main verified twice during preflight: `8b1b24c25a3cafb33c0446e3c046ceb2433498dc`, app 0.1.24 (25). Reused the completed INC-006 native harness and compiled-history evidence. Original harness was executed again: **79 checks, 63 PASS, 16 intentional RED, 0 unexpected failures, 0 SKIP**. Preserved original evidence; R1 does not claim every INC-006 finding is fixed.

New permanent `AdvisorDiscoveryRoutingRegressionTest` executed on pre-repair Android production Controller/resolver: **15 JUnit tests, 6 PASS / 9 expected RED / 0 SKIP**. A failed with no lookup; B with hint 205/null executed 205 instead of 203; C executed 203 for `203 m`; D rejected the selected profile. RED local commit `203c81d` contains tests only. Additional RED covered generic provider context, city/street resolution, ambiguous city, conflicting descriptors and known-city/current-alias conflict.

GREEN local native execution: **49 JUnit tests, 49 PASS / 0 FAIL / 0 SKIP**: 19 discovery Controller tests, 14 unchanged BranchResolver tests, 2 additional directory/locative tests (63 canonical/locative/lowercase city inputs), and 14 unchanged KWANT location-authorization tests. Exact B covers 205/null, 205/Bochni, 205/Bochnia, 203/Bochni, 203/Bochnia and conflicting 128/Zamość; asserts actual provider reads and verified-card branch 203. A covers matching/conflicting hints and verified profile cards. Tests cover measurements/codes/dimensions, known market numbers, unknown/ambiguous/conflicting locations, duplicate/unavailable directory, wrong provider, street disambiguation and explicit numeric comparison containment. Three existing Controller security assertions were respecified from false rejection to verified profile lookup while retaining the forbidden-branch invariant.

First full Android PR CI [38517785528](https://github.com/lukaszpeciak91-sys/towarownik/actions/runs/38517785528) at `a24a692d59b84d91b24da0d7303f806c6ec2baea` executed **721 unit tests, 719 passed / 2 failed** and failed the build. These were additional unexpected full-suite failures, not the intentional pre-repair RED: the old unscoped-product-number assertion expected total rejection, while the prior-turn “tam” case exposed an unresolved deictic location falling back to the profile. The former now asserts profile lookup for a product-shaped token plus zero lookup for explicit unknown market 999; the latter retains rejection with a current-turn unresolved-location guard. No historical location is inherited. Added a permanent “tam / w innym markecie” negative regression. Final local rerun broadened to the complete existing Controller test class: **93 JUnit tests, 93 PASS / 0 FAIL / 0 SKIP** (44 existing Controller tests + the 49-test subset). Full final-head Android CI remains the PR check, not inferred from local execution.

Commands actually executed locally (OpenJDK 17.0.20; Kotlin 2.3.21/coroutines 1.10.2/JUnit 4.13.2; offline synthetic provider data):

```bash
# From the existing INC-006 evidence directory:
java -cp 'classes:deps/*' pl.lukaszpeciak.towarownik.agent.Inc006HarnessKt
# New evidence directory, source copies and existing dependency jars:
python3 refresh_sources.py
java -cp 'deps/*' org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -classpath deps/kotlin-stdlib-2.3.21.jar:deps/kotlinx-coroutines-core-jvm-1.10.2.jar:deps/annotations-26.0.2.jar:deps/junit-4.13.2.jar \
  -d green-classes src/*.kt
java -cp 'green-classes:deps/*' org.junit.runner.JUnitCore \
  pl.lukaszpeciak.towarownik.agent.AdvisorDiscoveryRoutingRegressionTest \
  pl.lukaszpeciak.towarownik.product.provider.BranchResolverTest \
  pl.lukaszpeciak.towarownik.product.provider.BranchResolverDiscoveryTest \
  pl.lukaszpeciak.towarownik.agent.KwantLocationAuthorizationTest
# Expanded native run after full-CI findings, same compiler options with
# -d green-full-controller-classes and unchanged complete Controller tests:
java -cp 'green-full-controller-classes:deps/*' org.junit.runner.JUnitCore \
  pl.lukaszpeciak.towarownik.agent.AdvisorControllerTest \
  pl.lukaszpeciak.towarownik.agent.AdvisorDiscoveryRoutingRegressionTest \
  pl.lukaszpeciak.towarownik.product.provider.BranchResolverTest \
  pl.lukaszpeciak.towarownik.product.provider.BranchResolverDiscoveryTest \
  pl.lukaszpeciak.towarownik.agent.KwantLocationAuthorizationTest
PYTHONPATH=tools python3 -m unittest discover -s tools -p 'test_*.py'
cd proxy
npm ci
npm run typecheck
npm test
npm run eval:build
node --check evals/run-taksula-behavior.mjs
```

The native harness compiles unchanged production domain/authorization methods; only unused Android network/factory wiring and network repository implementation are omitted, using the same explicit offline stubs as INC-006. The repository JUnit tests are executed unchanged. This is not full Android Gradle locally; PR `Android checks` must execute `./gradlew check assembleDebug` against final HEAD. No Android SDK/device execution is claimed locally. Local Python **174/174 PASS**; Node **22.23.3**, Proxy **636/636 PASS**, typecheck and eval build/CLI syntax PASS. Proxy source is unchanged, so its path-filtered PR workflow is not required to trigger; local full Proxy validation was nevertheless executed. Final CI URLs/status are recorded in the focused PR description/checks.

Security: provider matching, invalid-directory rejection, USER-only scope and numeric/descriptor containment remain tested before any lookup; real FindProviderProductsTool verifies synthetic provider results and Controller filters refs to current-turn snapshots. Separate location-tool and follow-up source is unchanged; their existing full Android suites remain required in CI. No stock, price or identity is inferred; no raw prompts/product data/errors/secrets are logged. No prompt tuning, Worker change, HTTP/API change, UI change, PR merge, deployment or AAB build. R2 plain “w Bochni” follow-up after a failed turn, location-tool hint mismatch, two-product locations and live OBI coverage remain excluded. Independent audit and separately authorized bounded device smoke tests are required before treating the release as proven on-device.


## 2026-10-13 — INC-005 F4 production exhaustion → bounded Worker repair (audit pending)

Timeline/evidence: R5.1 could not access production identity/logs; later the incident investigator supplied a real Cloudflare record: trace **`3d3e40fa-5b3b-4abf-bbcd-f587c3dcf49e`**, Worker version **`cd218e15-8264-4f4f-a32c-ff2d14e81314`**, October 13 `/v1/agent/continue`, KWANT `kwant-pl`/`205`, protocol v3, 3 verified groups / 5 products, upstream 200 → Worker 502, `upstream_incomplete_output_budget`, 6070 ms, investigated source cap 384. CONFIRMED exhaustion for that request; exact incident timestamp/token split and deployed git SHA were not supplied. The October 12 pump failure remains **UNKNOWN**. No independent production-log access or live traffic was performed by the implementer.

Base main **`51ba932df85391776e2940bcefd439862faeff5f`** includes F1/F2/F3. Published test-only RED commit **`0cee7c1eb7d14937daa138929f782b3dfc798e7b`** (same tree as locally executed RED `045241cd8aa20862b05d01c920e1ae3ba2eb7ef6`): focused **75 total / 39 PASS / 36 expected FAIL / 0 SKIP**, exit 1. Failures establish absent scoped budget and discarded incomplete usage. The explicit 384 witness remains in the final fixture; its scripted 700-token threshold is synthetic, not a tokenizer or live-model sufficiency measurement. Production patch changes only budget selection and safe metadata carriage; final JSON/tool validation remains strict.

Executed locally under Node **22.23.3** / TypeScript **5.9.3**, from `proxy` (Node 22 prepended to PATH):

```bash
npm ci
node node_modules/typescript/bin/tsc --noEmit false --outDir .test-dist
node --test tests/output-budget.test.mjs # RED before patch, GREEN after
npm test
npx tsc --noEmit
node --test tests/behavior-eval.test.mjs tests/instruction-composition.test.mjs tests/tool-plan.test.mjs tests/blank-output-contract.test.mjs
npm run eval:build
node --check evals/run-taksula-behavior.mjs
```

GREEN: focused **76/76** (including one added positive location-budget boundary), full Proxy **636/636**, combined existing behavior/instruction/F1/F2/F3 regression **350/350**; all have 0 FAIL / 0 SKIP, exit 0. Clean install, typecheck and eval build/CLI syntax check passed. These overlapping runs are not summed. The original investigation harnesses remain unchanged; a scoped `inc005-f4-postfix.test.mjs` points to this Worker and replaces only the obsolete “incomplete discards usage” expectation. Executed from the investigation directory with `node --test inc005-f4-postfix.test.mjs`: **132/132 PASS / 0 FAIL / 0 SKIP**, exit 0. Original harness SHA-256 remains `4584e944591526a279ce270786a1f93d3cde8d7b9af08787de93723b5daed82c`. No live behavioral eval was run; deterministic harness regression is not a live model PASS.

CI: [PR #122 Checks](https://github.com/lukaszpeciak91-sys/towarownik/pull/122/checks) is the authoritative final-HEAD status. Initial implementation HEAD `b028eecd5576a32af33703260ffa5582afbf3f27` started [Proxy checks 38449636333](https://github.com/lukaszpeciak91-sys/towarownik/actions/runs/38449636333) and [Android checks 38449636379](https://github.com/lukaszpeciak91-sys/towarownik/actions/runs/38449636379); this documentation-only follow-up records those identities, not a completion claim. Exact final-HEAD run URLs/results are recorded in the PR delivery after completion; no AAB workflow is authorized. Remaining unknowns: 1024 live sufficiency, token split/usage availability for the recorded incident, effective production source SHA, live error rate/deadline behavior, Android accounting for failed paid calls, location/simple/START/MESSAGE adequacy at 384 and the October 12 root cause. Independent audit required; no merge/deploy/AAB or production configuration change.

## 2026-10-13 — INC-005 F2/F3 output contract repair (independent audit pending)

Base main `bae7bd0fddfb2c67fdecb5b64c191af3bc86f296` includes merged F1. Focused RED on unchanged Worker: **138 tests, 56 PASS / 82 expected FAIL**, covering provider requestedBranch blanks and v2–v5 opaque call/response IDs; response ID mismatch separately reproduced for final/tool envelopes. Existing F1-adjusted investigation harness: **130 PASS / 2 expected RED**. Minimal Worker-only predicates now reject location blanks after matching Android's control/Unicode whitespace semantics and reject unnormalized opaque ID blanks; accepted strings are returned unchanged. Node 22.23.3 / TypeScript 5.9.3 GREEN: **560/560** full proxy tests, **138/138** focused, typecheck PASS. Original harness and F1 copy preserved; a scoped copy updates F2/F3 witnesses and expected rejections: **132/132 PASS**. Added five native Android MockWebServer parser regression methods (no production Android edits), including Kotlin vs JS whitespace differences, valid-location normalization, opaque continuation identity and trace. Native execution must be confirmed through final PR Android CI; local Gradle/SDK remains unavailable. No model/schema/prompt/budget/retry/timeout/auth/config changes, live model/Cloudflare testing, deployment or AAB; historical pump root cause UNKNOWN. Independent audit required before merge.

## 2026-10-13 — INC-005 F1 aggregate tool-plan repair (independent audit pending)

Base main `63e81838bc78c2334588e4a2f2f6280b4e8b9e26`. Test-only RED commit `6c763859bf630a619330635338ba667c6408f09b`: focused Node suite **141 tests, 105 PASS / 36 expected FAIL**, covering allocation absent in v2 and OBI/KWANT v3–v5. Original INC-005 harness reproduced **132 tests, 129 PASS / 3 expected RED**; its schema-valid `[3,3]` plan returned 502. Minimal Worker-only opt-in now reduces aggregate overflow after complete argument validation; strict CONTINUE and default parser remain unchanged. Local Node 22.23.3 / TypeScript 5.9.3 GREEN: full proxy **422 PASS / 0 FAIL**; focused suite **141 PASS / 0 FAIL**, including all **3,905** valid limit vectors per v2/v3/v4/v5 (15,620 total) for order/minimum/non-increase/budget invariants. `npm ci`, `npm test`, `npx tsc --noEmit` executed. Existing Ajv-backed harness retained unchanged for RED evidence; a scoped post-fix copy replaces only obsolete F1 expectations: **2 focused F1 PASS**, full **130 PASS / 2 expected RED** for out-of-scope F2/F3. An old in-repo 502 assertion is replaced with a `[3,2]` envelope assertion rather than silently removed. Authorization is Android-owned; Node tests prove wrong provider/branch hints are preserved, while existing Kotlin guards remain subject to Android CI. No local Android execution (Gradle/SDK unavailable), live model/device/Cloudflare certification, deployment or AAB; historical pump root cause still UNKNOWN. Independent audit and PR CI required before merge.

## 2026-10-12 — INC-003 KWANT location follow-up repair (PR #119, independent audit pending)

Android RED CI **38301141867**: 693 tests, one new failure (`inc003ContextualSameProductUsesBoundedHistoryAndLocationIntent`, line 709), proving the exact `A ile tego samego produktu jest w Nowym Sączu?` phrase failed the deterministic clarification-admission predicate. Existing `KwantLocationAuthorization` canonical aliases omitted `w Sączu`, the second bounded failure boundary. Implemented a KWANT-only, directory-derived abbreviation; exact contextual quantity grammar and normalized local-tool user intent; a fail-closed guard forbidding previous-product inheritance after any unrelated USER turn. Added synthetic 5-turn history tests (model tool outputs supplied by the fixture, not a live model), provider-bound adapter reads and authorization/security negative cases. This proves the deterministic Android boundaries, **not** a production model narration or live inventory outage; real stock numbers 340/23/424 are observations, not test fixtures. Final Android `check assembleDebug` and independent audit required; no PR merge, deployment, Worker change or signed AAB.


## 2026-10-12 — INC-004 Advisor failure-report evidence ownership (PR #118, pending audit)

A real KWANT 205 Advisor failure (HTTP 502/upstream_failure) was incorrectly reported through an older persisted assistant-response report. Existing `ASSISTANT_RESPONSE` reports are intentionally bound to historical assistant messages, whereas `advisorState.Error` is transient. RED Android CI **38221136071** proved there was no dedicated `ADVISOR_FAILURE` report type (one focused runtime test failed). Added a distinct error-bubble reporting action and bounded in-memory failed-turn snapshot keyed by conversation ID and an opaque new UUID; the snapshot captures only the safe diagnostic (stage, HTTP code, proxy code, failure class, validated trace when available). It is pinned when opening the report editor, cleared on retry, successful completion, conversation switch/cancellation or matching deletion, and never assigned to an earlier assistant response. The resolver checks conversation existence and optional context consent; stale/missing snapshot fails closed instead of emitting wrong evidence. No Room migration, Worker change, raw prompts, attachment bytes or secrets. Dedicated tests cover scope, TTL-by-lifecycle, START/MESSAGE/CONTINUE examples, privacy canaries, prior successful trace exclusion, deleted conversations and existing report kinds. **Limitation:** the snapshot is not persisted across process death; such reports become unavailable rather than misattributed. Independent review and full Android CI required before merge.


## 2026-10-11 — INC-002 CONTINUE stock-unit repair (pending independent audit)

Initial RED run 38178340364 confirmed v2 but included a malformed test helper injection for v3. Corrected RED Proxy CI run **38178594839**, using the exact baseline validator and properly versioned v2–v5 fixtures, yielded **267 pass / 14 expected failures**: each new `stockUnit`-carrying verified CONTINUE returned 400 instead of expected 200; historical fixtures without `stockUnit` stayed green. The strict Worker/Android mismatch is therefore executable across all affected versions. Added optional bounded `stockUnit` acceptance to product validators and types plus synthetic v2–v5, historical omission, malformed type and unknown-field tests. No production requests, Android changes, AAB build or Worker deployment. Track GREEN Proxy and Android CI on final HEAD before merging.

## 2026-10-11 — R4.1 rapid duplicate-submit admission boundary (awaiting independent audit)

Extracted the unchanged synchronous `advisorJob?.isActive != true && !attachmentImporting` predicate to a pure helper used by MainActivity. Deterministic coroutine tests verify that an immediate second admission fails while a Job is active, that admission resumes after completion/cancellation, that attachment import blocks sending, and that guard invalidation rejects a stale conversation token. **This does not prove a full Compose UI → Room user-message persistence → proxy-request lifecycle**: the synthetic counters in the test are not actual repository or OkHttp calls. Existing `ConversationRepositoryTest` covers independent failed-turn recovery and `AdvisorRequestGuardTest` covers stale callbacks. Full B05 remains NOT TESTED at integrated UI boundary; perform rapid double-send with local request counting or a controlled device smoke test before five-person pilot. No network limits, protocols, Worker or persistence schema changed.


## 2026-10-11 — R3 offline fault-injection evidence (PR #115)

Added one deterministic deferred OpenAI START test with injected `upstreamFetch` and no sleeps or live requests, and an inspectable scenario-to-test reliability matrix at `docs/reliability/r3-fault-injection-matrix.md`. Existing Kotlin/JUnit + MockWebServer and Node tests cover transport failure classification, continuation traces, R2 completion status, location/provider rules and multipart boundaries. This is **contract-level**, not a true connected Android-to-Worker runtime test; real device timeouts, user recovery and production Cloudflare behavior remain unverified. Do not merge pending independent audit.

## 2026-10-11 — R2 Worker Responses completion attribution (pending independent audit)

Added fail-closed top-level Responses status validation before answer/tool normalization. Structured `max_output_tokens` incomplete reason receives separate privacy-safe category; all other incomplete/nonterminal/missing/invalid statuses reject with bounded diagnostics. Synthetic tests cover completed answer/tool, noncompleted answer/tool, HTTP upstream categories, logs and trace behavior. No Worker deployment or changed public wire/limits; independent PR audit and CI required.

## 2026-10-11 — R1 bounded Android Advisor failure diagnostics (PR #113)

Android-only diagnostic extension: recognize DNS, TLS, timeout and generic IO failures; preserve HTTP stage and 502 status/allowlisted error/UUID trace; report malformed successful envelopes as response parsing failures and mark unexpected local product-tool failure separately. Current failed-turn state and report formatter carry only bounded enum/endpoint/status/trace fields. Cancellation still cancels OkHttp calls and is not reported as a retryable failure. Existing 10/20/20/30 second OkHttp limits, no-retry behavior, protocols v1–v5, attachments, provider authorization, Worker and Room stay unchanged. Deterministic Android classification and MockWebServer regressions added; independent audit required before merge.

## 2026-10-11 — PR #112 canonical 21-branch locative corrections

Validated the current public KWANT directory's 21 cities and reviewed research #111. Expanded shared canonical locative aliases and `a we` follow-ups, corrected Tarnów 204 vs Białystok 216 in synthetic Android tests and in the device smoke guide, and added bounded resolver + Controller-to-real-KWANT transport regressions including two-city and unknown/conflicting scopes. No static production branch IDs, OBI regression, reliability retries, persistence or Worker schema edits.


## 2026-10-11 — Audit fix in existing PR #112

Aligned Android unavailable evidence with strict Worker JSON validation by adding canonical null-stock entries for the entire checked/missing set, including HTTP failure, timeout, malformed JSON and NO_TRUSTED_ROWS; unavailable directory returns empty coherent arrays. Replaced KWANT's first-city-only matching with full bounded `i/oraz/comma` name/numeric-ID chain parsing and paired-label conflict rejection. Added focused resolver, Controller-to-real-adapter, real Android ProxyClient serialization and Worker validation tests. No production search, Worker schema, OBI or reliability retry changes. Test status is tracked by PR #112 CI.


## 2026-10-11 — PR #112 guarded production KWANT product locations

Updated only location-specific Worker instructions (not protocol or schema) so the model no longer describes verified KWANT extended as unverified. Proxy checks are required for this focused Worker text change.

Implemented observed `extended=1` with existing read-only HTTP, one exact product GET and bounded response validation. Directory-based user scope and model-hint containment prevent a Zamość-only request leaking the full public branch list, while explicit nationwide queries are permitted. Numeric stock, zero, null, missing, invalid and unknown branch identities remain distinct; root `unit` is optional and never inferred. Deterministic KWANT location output now labels oddziały rather than OBI markets and shows verified units, timestamp and incomplete status. Current `obu` produces a typed single-product-tool limitation rather than an unverified endpoint claim. Added synthetic provider, scope, MockWebServer and UI regressions; production Worker and normal provider find_products remain unchanged. Manual Android checks: see `docs/kwant-product-locations-production-smoke.md`.


## 2026-10-11 — PR #110 inherited "obu" supersession regression

Added explicit-current-identity precedence in `AdvisorLocationFollowUps`: verified 7027 overrides a preceding `obu`, while explicit unknown 9999 cancels old `obu` before normal trusted-identity rejection. Controller → follow-up → actual locations tool regressions also verify that current `obu` and location-only Tarnów follow-ups preserve the intended two-product selection. Production KWANT adapter with disabled extended and a failing synthetic network fetcher verifies no inventory HTTP. Prior OBI, shared identity, tool-budget and grouped `find_products` suites remain in full Android checks.


## 2026-10-11 — PR #110 Controller ↔ follow-up identity consistency

Replaced raw numeric mention matching in the follow-up resolver (both older USER turns and current USER input) with the same bounded product-identifier policy used by `AdvisorLocationsTool`. Added an offline Controller → real follow-up resolver → real location-authorization regression checking that earlier 7035 stays selected over a 1000 W measurement, explicit verified product 1000 takes precedence, unknown short ID fails before inventory adapter HTTP, model mismatch is rejected, and dimensions stay non-identities. Also covered OBI market ID 003/052/075 identity isolation. Existing KWANT grouped `find_products` and prior test suites remain part of full Android checks.


## 2026-10-11 — PR #110 final natural short-ID regression

Extended the existing exact trusted-product guard to constrained `Sprawdź stan 7027`, `Sprawdź dostępność 7027`, and `Jaki jest stan 921861` forms. Added rejection/no-HTTP tests for unknown IDs and wrong model product; verified-ID and article tests for selecting the requested product; checks for `1000 W`, `25 m`, `5 szt.`, OBI 003/052/075 and unknown EAN; plus Controller coverage for two KWANT grouped `find_products` queries in the current and an explicitly selected other branch, without location requests. Existing authorization, 61-OBI-market and three-local-call tests are retained.


## 2026-10-10 — PR #110 final security regressions

Narrowed cross-turn inventory clarifications to complete canonical city/location forms so prices, mounting and compatibility prompts cannot inherit stock intent. Added short KWANT ID/article fail-closed checks with synthetic rejection-to-newly-verified transition; made unlabeled location questions with contradictory OBI market numbers reject without HTTP. Added targeted price/advice/market tests and Controller KWANT `find_products` selected/other-branch regression asserting no dependence on disabled `find_product_locations`. Existing 052/054, 61-store bounded read, KWANT `obu` and logical-call-limit tests are retained.


## 2026-10-10 — PR #110 audit follow-up

Closed three review findings: generic short unrelated requests break authorization, current explicit verified OBIK overrides prior history selection, and product-trust denials are not reused after in-turn discovery. Added positive and negative ordered-report tests plus a Controller location→discovery→location regression ensuring exactly one post-verification inventory read and all three logical requests counted. Report A/B regressions remain in place.


## 2026-10-10 — Inventory conversation follow-up regression work

Added a 12-message / four-user-turn history evidence contract and a bounded verified-product referent resolver for Advisor location tool calls; MainActivity passes only preceding persisted messages. Added Miejsce Piastowe alias, explicit 054/052 conflict reason, and relative-clause all-other stock request parsing. KWANT exactly-two-products "obu" remains typed unavailable before unverified extended HTTP. Added reconstructed OBI/KWANT report tests, real Worker-envelope-to-Controller authorization with trace, deterministic 054/052 clarification presentation, a duplicate-rejection/fourth-call budget test, and preserved all existing provider/parser and Worker protocols.


## 2026-10-10 — Verified stock units for OBI/KWANT cards

Implemented optional stock-unit metadata from KWANT verified `unit` fields tied to exact product/branch evidence. OBI selected-store stock still has no adjacent unit, but exact PDP dedicated sales-unit facts are now accepted when explicit (for example `Sprzedaż: na metry` -> `m`); products without such evidence remain unitless. Unit travels through snapshots, Room v13 migration, chat saved state, manual search and Advisor cards; central KWANT stock uses the same verified unit. Numeric stock never falls back to "szt." and stock=0/null meanings are unchanged. Parser, unit-formatter, saved-state, migration and history-reopen regressions added.


## 2026-10-10 — Advisor chat selection and reopen-to-bottom

Assistant answer text is selectable/copyable with native clickable inline citation links. Existing history opens at the newest message and live turns follow the tail while the reader remains near the bottom; scrolling back to older messages suspends automatic movement. Empty new chats, persisted citations, product cards, transport and Room are unchanged. Focused annotation and scroll-policy tests added.


## 2026-10-10 — Location hint equivalence fix

Reused canonical-city aliases and existing market-label matching when validating model location hints in Android. Added regressions for Kraków/Krakowie/empty hints and four canonical stores; Nowy Sącz/Nowym Sączu/OBI 075; contextual OBI 003; contradictory/unknown hints; unknown user city; full-network empty hints and 100 cm product dimension. Added a Worker-compatible JSON envelope → Android proxy parse → typed locations execution integration test with synthetic stock. No Worker, product transport, Room, WorkingProfile or UI changes.


## 2026-10-10 — Shared product-image preview

Added a full-screen, warm-dark click-to-enlarge Dialog in VerifiedProductThumbnail across OBI/KWANT Advisor, manual-search and conversation-history card surfaces. Coil displays a larger Fit image within the actual dialog bounds; image failures stay safe and noninteractive for missing thumbnails. Added localized PL/EN accessibility/error labels and provider snapshot preservation regression. No modifications to Worker, provider parser, Room, attachments or WorkingProfile.


## 2026-10-10 — lightweight text files Phase A2

Android document picker and replacement now select ten allowlisted text-like formats alongside image/PDF. TEXT is checked and normalized from bounded provider MIME quirks to strict Phase A1 MIME contracts, validated as UTF-8 before ownership publication, and persisted in existing Room v12 message_attachments rows. Composer and USER history show distinct filename/type/size badges without file contents. Multi-file order, replace/remove, failed-turn recovery, restart, 1 MiB per-file + aggregate text budget, 24 MiB total and private-file cleanup are retained. No Worker/provider changes or Office/archive support.


## 2026-10-10 — lightweight text files Phase A1

Added strict v5 multipart support for ten allowlisted UTF-8 text extensions in the Worker and Android transport: extension/MIME validation, safe bounded decode, binary/control rejection, untrusted ordered Responses `input_text` with sanitized filename, and focused regression tests. Image/PDF v5, v4, Room v12 ownership, current pickers, provider evidence boundaries and JSON-only continuation remain unchanged. XLS/XLSX/DOCX/ZIP deferred.


## PR #102 — location completeness (2026-10-10)

Replaced partial city-suffix and numeric-ID extraction with one Android-side complete restricted-scope parser. Added regression cases for Kraków and inflected Nowy Sącz, unsupported multi-word cities, trailing polite text, comma/`i`/`oraz` lists of 2–3 market IDs, and unknown market IDs/malformed mixed lists (no HTTP). Existing 61-market batching, cancellation, partial results, 45-second deadline and provider integration remain unchanged.


## PR #102 — 2026-10-10 scope parser regression closure

Replaced single-token unknown-city detection and `i`-only checks with complete named-locality phrase validation. Added deterministic tests for four generic all-other requests, seven known/unknown multi-city conjunction examples with empty model hints and canonical four-market Kraków scope. Existing 61-market batching, partial failure, execution deadline, trusted product, KWANT and WorkingProfile behavior remain untouched.


## PR #102 — 2026-10-10 focused location authorization hardening

Scope authorization hardened: focused explicit user inventory-intent gate before any location network access; unknown city without model hints rejected; store ID extraction requires explicit market context; partial 61-market headings state all-other scope and incomplete results; 45-second total bounded OBI scan with injectable fast deterministic timeout test and verified partial-result preservation. No WorkingProfile write, discovery regression or KWANT contract change.


## 2026-10-10 — PR 2 opt-in find_product_locations

The Android Advisor orchestration now supports one typed, provider-neutral location inventory call with current-conversation verified-product authorization and OBI current-user canonical location authorization. The Worker advertises it only when the new Android client sends `X-Taksula-Locations-Capability: 1`; legacy v1 and old v2–v5 clients continue with their original discovery tools, which enables Worker-first deployment. The location call shares the existing three-call budget and does not run automatically for ordinary product search. Recent persisted assistant product cards may authorize product identity but do not auto-generate new product cards or override live stock evidence. For explicit "where else" follow-ups, the Advisor derives the full canonical OBI directory scope (61 other markets excluding the selected market) and orchestrates at most four sequential service reads (20 IDs/read, 10 IDs/HTTP request, seven HTTP reads total). Explicit user cities/markets restrict the scope; Kraków expands to its four canonical stores, while Tarnów is not a canonical OBI market. Each missing market is returned with unknown/null stock, full checked IDs and partial coverage instead of a fabricated zero. This is still **one** logical Advisor local-tool call. The per-service PR #101 budgets remain intact and no ordinary search gains new location requests. KWANT returns `UNVERIFIED_REQUEST_CONTRACT` without transport until the independently observed `extended` query VALUE can be recovered; two-provider live readiness is not claimed. PR is unmerged until independently audited.


## 2026-10-10 — Product locations transport PR 1

Read-only Android internal `ProductLocationsService` now dispatches to isolated OBI and KWANT adapters, independently of current Advisor and manual search. OBI canonical requested subsets use max-10 market batches and max-20 logical budget, strict JSON trust checks and partial-coverage semantics. KWANT one-shot 21-branch parser and transport are implemented behind a **fail-closed missing-`extended` contract gate**: original live research redacted the query value, and production does **not** send unverified requests. Until an exact reproducible value is established, do not claim production-ready two-provider support. No existing normal-turn network cost changes. Dedicated offline regression tests cover quantities, product/store IDs, request budgets and malformed/partial responses; only final checks/CI can establish their pass status. Future PR 2 will connect the service to Advisor/Worker with trusted references.


## Current phase

Multi-attachment phase 2: Android composer, Room v12 ordered relations and 11→12 migration, multi-picker/camera, private-file ownership reconciliation, per-file remove/replace, 24 MiB total guard and failed-turn restoration. All attachment turns select v5; text-only behavior remains unchanged.


Multi-attachment transport phase 1: opt-in provider protocol v5 supports 1–3 JPEG/PNG/PDF parts for START/MESSAGE, with a 16 MiB per-file bound and 24 MiB aggregate raw-file ceiling (plus 16 KiB multipart overhead), stable Responses input order, Android transport list overloads, and unchanged JSON-only continuation. Existing single-file UI/v4, v2/v3, Room and ownership remain unchanged; UI multi-selection is deliberately deferred.


**Final Advisor contract validation + release readiness**

Observability follow-up: Worker-side advisor upstream failures now retain the same public 502/upstream_failure response while Cloudflare logs distinguish safe structural failure categories and optional upstream HTTP status without payloads, content, identifiers, filenames, or secrets.

Protocol-continuity blocker follow-up: tool-assisted advisor turns now retain the transport family selected by their initiating START/MESSAGE, so an OBI attachment turn cannot drop from provider-aware v4 `find_products` to legacy v2 during `/continue`. OBI local retrieval remains `FindObiProductsTool`; text-only OBI v2 and KWANT v3 remain unchanged.

Current branch/location integration follow-up: prompt behavior now follows the shared Android BranchResolver authorization model, branch selector labels retain real IDs alongside names/addresses, explicit only-confirmed-local-stock intent requires selected-branch stock > 0, and unsupported internal citation/entity tokens are removed at the proxy normalization boundary while real URL citation annotations remain authoritative.

PR #17 established the production-shaped chat shell and bounded human manual OBI browsing. PR #18 added real local conversation state, multi-turn continuation, retention, and deletion. PR #19 added persistent app-owned verified product cards. Localization, Warm Modular Utility, Settings, PL/EN selection, diagnostics/reporting, the Taksula rename, provider-aware WorkingProfile, OBI + KWANT integration, usage/cost measurement, GPT-6 Luna, richer verified product facts, selective web search, branch routing, attachments, shared Advisor policy, behavioral evals, and B2 observability are complete. The current release gate is the final real-model behavioral run on both providers followed by final Play AAB validation.

Implemented direction:

- Room schema v1 for conversations/messages;
- real drawer history sorted newest-updated first;
- fully local phrase search across title plus USER/ASSISTANT text;
- first USER send creates the conversation and local bounded title;
- completed messages, timestamps, drafts, and final response ID survive database recreation;
- recent useful conversation restores on app restart where practical;
- composer re-enables after a completed answer for real follow-up turns;
- first turn uses `/v1/agent/start`;
- follow-up turns use authenticated `/v1/agent/message` with the stored final `lastResponseId`;
- tool responses still use `/v1/agent/continue`;
- only the final answer response ID replaces the persisted conversation context;
- local tool allowance is three calls per USER turn and resets on every new USER message;
- interrupted USER-only tails recover to editable draft without automatic resend;
- switching/new conversation cancels active work and stale callbacks are rejected;
- current provider facts continue to be refreshed through the active Android local provider tool;
- conversations are treated as individual customer cases, with “Nowa rozmowa” recommended for a new customer/problem;
- startup-only local retention removes conversations strictly older than 30 days by `updatedAt`;
- the exact 30-day cutoff remains retained and message rows cascade on deletion;
- each history row can be deleted manually after explicit confirmation;
- deleting the active conversation cancels its request, invalidates stale callbacks, and opens a fresh empty chat;
- final model answers use strict structured `{text, productRefs}` output with at most five selected verified references; OBI v2 uses `{storeNumber,obik}` while provider-aware v3/v4 uses `{providerId,branchId,productId}`;
- exact `LocalProduct` lookups retain local-only snapshots containing trusted URL and verification time while OpenAI still receives only OBIK/name/stock/price;
- product selection resolves only against verified snapshots from the current USER turn under provider-owned composite identity; OBI retains `(storeNumber, obik)` while provider-aware paths use provider/branch/product identity;
- Room schema v3 persists each conversation store and each message-product store; v2→v3 deterministically assigns historical rows to `075`;
- ASSISTANT text, selected snapshots, and final response ID commit atomically;
- historical cards reopen without network access and display their verification timestamp;
- the existing `VerifiedProductCard` boundary is shared by manual search and advisor history;
- current Android UI text is moved to the Polish/default `values/strings.xml` resource set;
- a complete matching English `values-en/strings.xml` resource set is provided;
- Compose resolves user-visible chrome through Android string resources and formatted placeholders;
- advisor/search errors use stable language-neutral enum state and are translated only in UI;
- saved manual-search errors no longer persist rendered language-dependent sentences;
- Android UI uses the complete PL/EN resource sets and Settings controls the supported per-app locale through AppCompat;
- production UI uses the approved warm dark palette and centralized semantic Compose colors;
- automatic Material light/dark switching and dynamic-color-style defaults are replaced by the approved dark-first scheme;
- top bars, conversation drawer, chat bubbles, persistent composer, progress/error states, verified product card, and manual OBI search share one visual hierarchy;
- strong amber is reserved for primary/verified/active emphasis rather than ordinary buttons and cards;
- character/emoji action controls are replaced by local vector drawable icons with accessible descriptions;
- no launcher PNG or other binary asset is created or modified by the visual-system work;
- Settings is pinned to the bottom of the conversation drawer independently of history length/search state;
- Settings exposes exactly Polski / English through AndroidX AppCompat per-app locales;
- AppCompat auto-stores the selected locale below API 33, while Android 13+ uses the platform per-app locale path with declared `pl` and `en` support;
- switching UI locale recreates the normal Android UI but does not send locale to the proxy/model or translate persisted conversation text;
- diagnostics is reached through Settings and the old hidden title long-press entry is removed;
- Diagnostics returns to Settings, while Settings returns to Advisor;
- Report problem and Privacy policy are visible disabled future rows with no fake destination/content;
- About uses `app_name` plus the current BuildConfig version, keeping later naming work cheap;
- each persisted ASSISTANT response has a quiet contextual Report/Zgłoś action backed by its persisted message ID;
- Settings Report problem is now active while Privacy policy remains future work;
- assistant/general report categories use stable technical enums with localized labels;
- transcript inclusion defaults OFF and is always user-controlled;
- assistant reports always include the exact persisted response and its persisted product snapshots, while optional context stops at that response;
- general reports require a description and may include the current persisted conversation only after opt-in;
- unsent drafts are excluded from report evidence;
- existing sanitized OBI diagnostics are offered only when already enabled and populated, through a separate checkbox that defaults OFF; reporting never starts diagnostics/probes/network work;
- reports are temporary UTF-8 TXT files under `cacheDir/reports/` and are not stored in Room;
- a non-exported FileProvider exposes only the report cache path and Android ACTION_SEND opens the system chooser with the centralized Nepahu Studio recipient;
- Taksula never sends the report automatically; chooser launch failure is handled with a bounded UI error and deletion of the fresh TXT; screenshots are added manually in the chosen mail/share client;
- report contents exclude OpenAI/tool IDs, response-chain IDs, secrets/auth headers, cookie values, raw model data, OBI HTML/Nuxt, account/network/location identifiers.
- public-facing product branding is **Taksula** in Android resources, diagnostics/report labels, TXT headings, and current product documentation;
- package/application ID `pl.lukaszpeciak.towarownik`, repository name, Worker/service/token identifiers, database/persisted contracts, internal theme symbols, and launcher asset filenames remain intentionally unchanged;
- launcher icon artwork and Warm Modular Utility styling are unchanged; no binary asset is modified.
- one persistent WorkingProfile selects the provider + branch for new conversations (default `obi-pl / 075`), while saved conversations keep their own profile;
- the advisor top bar exposes the provider/branch context and one-off cross-branch requests never mutate that persisted conversation profile;
- direct OBIK and selected EAN/text candidates exact-verify against the active selected store while search discovery remains store-independent;
- local Advisor tooling is protocol-shaped: text-only OBI uses v2 `find_obi_products(storeNumber, queries[])`; provider-aware text uses v3 `find_products(...)`; attachment turns use v5 `find_products(...)` (legacy v4 still accepted). Grouped calls remain bounded to at most five query groups and five requested products in total;
- customer-kit requests can verify several categories in one local call, with grouped `verified` / `not_found` / `unavailable` results and successful groups retained independently;
- the per-USER-turn ceiling is three local provider calls; a fourth requested batch performs zero provider work, returns `local_tool_limit_reached`, and forces the final continuation to proceed without another local function call;
- Android's shared `BranchResolver` authorizes one-off branch changes from only the CURRENT USER message against the active provider's real branch directory; exact IDs remain valid, natural metadata requires explicit branch/location intent, and ambiguous/unknown references fail closed;
- START/MESSAGE carry the conversation provider/branch context and the initiating transport family is preserved through CONTINUE; OBI text remains v2, provider-aware text v3, attachments v5, and unversioned Worker requests remain pinned to grouped v2 compatibility;
- unsupported/unauthorized branch or store requests fail closed before provider retrieval and never substitute a default branch;
- verified cards/history/reports preserve their own snapshot store and changing the conversation selector never rewrites historical facts;
- store `075` remains the deterministic regression/live-probe baseline.
- broad advisor searches now preserve OBI's reported search-result count locally; when that count exceeds a multi-result bounded advisor subset, the assistant message can persist a deduped “Zobacz więcej (N)” action without changing the five-card/productRef boundary;
- Room schema v5 adds `message_search_actions` for exact query/store/reported-count metadata, and the existing manual search can reopen that historical query/store without mutating the conversation store;
- manual search remains capped at 25 parsed candidates with the existing five-at-a-time enrichment/show-more behavior; no OBI pagination or Worker/protocol change is added.
- exact OBI product parsing now keeps one nullable trusted primary `bilder.obi.pl` image from structured product data; it stays Android-local, is persisted in Room v6 `message_products.imageUrl`, and is reused by advisor cards and manual exact-enrichment cards;
- Coil 3 Compose + OkHttp owns asynchronous cached thumbnail loading; image failure remains presentation-only and adds no product/advisor error or OBI retry.

No local transcript is replayed as a hidden fallback if an old OpenAI response chain cannot continue. No compaction/summarization is added. `previous_response_id` reduces application-level transcript replay but prior context tokens remain billable input.

Android test version: **0.1.10 (11)**.

- every successful OpenAI START/MESSAGE/CONTINUE response can carry bounded usage metadata without making telemetry a correctness dependency;
- GPT-5.6 Luna remains historical baseline data, while production now uses GPT-6 Luna with versioned pricing and the same usage accounting boundaries;
- local cumulative requests, USER turns, tool-assisted turns, tokens, model-grouped totals, known USD cost, and unpriced-request count are persisted without conversation content;
- Settings exposes AI Usage with adaptive sub-cent cost formatting;
- NBP USD/PLN is fetched only from the usage UI, cached for 24 hours, and fails soft to a dated stale rate or unavailable PLN;
- a user-configured local Taksula remaining budget uses the current cumulative cost as its baseline and is explicitly not OpenAI balance/credit data;
- the below-USD-1 budget warning is one-shot per threshold crossing and re-arms after budget reset/increase.

- exact verified OBI lookups now carry optional model-context brand, short description, and bounded technical facts from the same decoded product payload;
- live proof used OBIK 3496072 (adhesive), 6743009 (drill), and 7156243 (LED fitting), confirming productDescription/productOverview/technicalData shapes across categories;
- optional rich sections fail soft while OBIK/store identity, stock, and price rules remain unchanged;
- advisor tool payload remains bounded to brand 80, description 220, six facts, fact label 60, fact value 100;
- message-product snapshots/cards/reports remain unchanged; rich facts are transient model context only;
- raw OBI HTML/Nuxt, URLs, EAN, cookies, diagnostics, and verification timestamps remain outside OpenAI tool results.

- final Taksula instructions define a practical provider-aware retail/wholesale advisor rather than a literal product-answer bot;
- ordinary technical/sales advice uses normal model knowledge when sufficient and does not automatically invoke the provider merely because a product category can be inferred;
- product selection uses the shared three-state model: unknown category clarifies without lookup; known category with a decision-critical variant unresolved clarifies/resolves while safely browsing plausible candidates in the same turn; sufficiently specified intent verifies immediately. Provider browsing is never used to infer the missing parameter;
- understood job/project requests receive practical essentials-first advice without automatic OBI lookup, while explicit concrete-product or complete verified selected-market requests may use efficient multi-query verification;
- complements are restrained and are proactively mentioned only when materially helpful for correctness, compatibility, safety, or avoiding obvious failure; direct current price/stock questions remain direct and avoid unnecessary cross-sell;
- model-facing instructions do not expose the numerical local-tool guard; Android independently enforces three local calls per USER turn and graceful `local_tool_limit_reached`;
- stock `0`, null stock, grouped `not_found`, and grouped `unavailable` remain distinct evidence states and must produce distinct wording;
- zero-stock/not-found requested items may lead to a verified current-store substitute and an offer to check another market, but no other-market number or availability is invented;
- ordinary general technical knowledge remains allowed, specific OBI SKU/current-store claims remain under the verified tool trust hierarchy, and missing SKU-specific facts are not invented;
- GPT-6 Luna, low reasoning, multi-query/schema/productRefs boundaries, hard tool/product limits, multi-store authorization, web search, pricing, UI, persistence, and parser remain unchanged.

- current Responses `web_search` is available selectively alongside the protocol-appropriate local provider tool, with `tool_choice=auto` and `max_tool_calls=1` for built-ins;
- Android local provider verification remains authoritative for current selected-branch stock/price/availability and only current-turn verified snapshots may enter productRefs/cards;
- final answers preserve at most six normalized HTTPS sources from actual OpenAI url_citation annotations; real annotation offsets are mapped only when exact, safely mapped citations render as clickable inline markers, and unmappable sources remain in the compact fallback source list;
- Room schema v11 retains message sources/search actions/product images and additionally persists provider/branch ownership, provider-owned product identity, central stock, message attachments, and nullable Advisor trace correlation through explicit non-destructive migrations;
- searched pages are explicitly untrusted reference data and cannot alter role/tool/trust/privacy rules;
- AI Usage counts completed web searches independently from optional token usage; current pricing adds USD 0.01 per search action when cost is priceable;
- web availability alone does not count as a call, while missing/malformed usage still preserves the actual search count and keeps existing unpriced/known-minimum budget semantics;
- no deep research/background/streaming/file search/image search/RAG/MCP/general autonomous agent behavior is added.

## Next implementation milestone

The Advisor architecture/policy/observability stage is complete. The immediate release gate is one final real-model behavioral suite across OBI and KWANT, followed by final contract review of any true regressions and a signed Play AAB. Context compaction, privacy-policy content, broader attachment formats, and other feature work remain separate future milestones.

## Not started

- Context summarization/compaction
- Explicit continue-as-new-context fallback
- Strong per-device/user identity
- General chat
- Additional agent tools
- Streaming
- Server-side OBI implementation
- Camera barcode scanning
- Nearby-store fallback behavior
- Final Play release polish


## 2026-10-03 — KWANT provider integration

- Added `kwant-pl` behind the neutral `ProductProvider` boundary using the researched public KWANT frontend contract.
- Added provider-owned KWANT branch metadata/`departmentCookie` construction and selected-branch stock parsing.
- Added explicit neutral price scope so KWANT public prices are `ONLINE` while current OBI prices remain `BRANCH`.
- Production provider registry now resolves both OBI and KWANT; UI/advisor/persistence remain unchanged.
- Fresh KWANT lookup route is live-proven: `/produkt/580` -> HTTP 308 -> canonical `...-580` product URL -> HTTP 200, with final public product ID `580`; the reusable live probe records only safe status/path/URL/product-ID evidence.


## 2026-10-03 — persistent WorkingProfile

- Added persistent global provider/branch selection with default `obi-pl / 075`.
- Added neutral branch discovery to ProductProvider; OBI uses its existing supported-store allowlist and KWANT uses the public branch directory with `department_stock_id` identity.
- Added Room schema v7 conversation ownership of provider + branch; v6 historical conversations migrate to OBI with their existing store number preserved as branch.
- New conversations capture the current global working profile and saved conversations retain it.
- Manual search now routes through the provider selected by the global working profile and keeps provider-owned ProductRef identity.
- Provider-specific manual product labels distinguish OBIK from KWANT article numbers.
- Existing advisor tooling remains OBI-only and does not automatically switch providers.


## 2026-10-04 — provider-aware advisor / KWANT

- Added advisor protocol v3 using provider + branch identity and neutral `find_products`.
- Added Android local advisor tool execution through `ProductProviderRegistry`.
- KWANT conversations now enter the advisor using their saved WorkingProfile (e.g. `kwant-pl / 205`) instead of returning unsupported-provider.
- Kept existing OBI advisor transport on protocol v2 for rollout compatibility.
- Added Room v8 provider-owned advisor product persistence. The already-shipped v6→v7 migration remains unchanged; v7→v8 adds providerId/productId/branchId/articleNumber and maps historical OBI products safely. Price scope is restored from provider identity rather than adding another database column.
- Added provider isolation: a KWANT conversation cannot execute an OBI tool request and vice versa.
- Added Worker and Android coverage for protocol v3, KWANT 205, no branch fallback, provider-owned product refs, and KWANT online price scope.


## 2026-10-04 — advisor product intent and exact lookup

- Clear concrete-product, recommendation, assortment, price/stock, and identifier intent now proactively uses the current conversation provider; general technical advice remains lookup-free by default and decision-critical ambiguity still clarifies first.
- Exact seven-digit OBIK tool queries bypass OBI text search and use the existing exact store lookup directly.
- KWANT advisor input exposes no model-controlled direct product-ID path; article number, EAN, product name, manufacturer text, and other user-supplied identifiers remain search-then-exact-verification inputs.
- KWANT branch metadata is resolved once per provider-tool execution and reused for all verified candidates without adding a broad cache.


## 2026-10-04 — provider-aware behavioral parity harness

- Extended the existing A–I behavioral evaluation families across OBI protocol v2 and KWANT protocol v3, with provider-shaped direct-product fixtures and the same provider-neutral quality bar.
- Preserved the OBI fixtures and OBIK baseline; KWANT direct-product variants use article-number search text, provider-owned references, branch `205`, and online-price fixtures rather than OBIK semantics.
- The manual behavioral workflow runs both paths by default and can select either path for focused audits.
- No OBI or provider-v3 production advisor instruction change was needed; deterministic parity coverage exercises decision-critical clarification, proactive lookup, availability distinctions, advice-first jobs and technical questions, bounded assortment wording, and restrained complements.
- After the first real-model parity run, scenario A was calibrated so a broad low-risk commodity request such as black cable ties may browse several verified variants immediately; decision-critical compatibility or safety ambiguity still clarifies first.
- The concrete-product recommendation fixture no longer exposes `mock` or eval-only wording to the model.

## 2026-10-04 — KWANT logistics visibility

- KWANT parsing now exposes selected-branch and central stock separately; a
  missing central value remains null.
- KWANT cards label selected-branch stock, optional central stock, and the
  indicative online price. Existing OBI presentation remains unchanged.
- Explicit other-location requests can perform one uniquely resolved KWANT
  branch lookup while the conversation WorkingProfile remains unchanged.
- Room v9 persists nullable central stock; migration 8→9 only adds that column.

## 2026-10-04 — advisor attachment foundation

- Added provider-neutral IMAGE/PDF metadata with exactly one attachment per USER message and no binary content in domain or Room models.
- Added app-private attachment import/read/delete storage using opaque identifiers; no picker, composer, upload, Worker, or OpenAI request behavior changed.
- Room v10 adds the normalized `message_attachments` relation. Historical messages migrate with zero attachments and metadata cascades with message/conversation deletion.
- Explicit deletion, 30-day retention cleanup, and interrupted-turn recovery now perform best-effort cleanup of only the related private attachment files; recovery captures the trailing USER attachment id before Room deletion and preserves draft recovery even if filesystem deletion fails.
- Attachment import now enforces a temporary 16 MiB per-file Android private-storage ceiling before copying while retaining exact copied-byte validation; no Worker/OpenAI transport limit is introduced.

## 2026-10-04 — Android attachment acquisition and composer UX

- Added the approved in-composer attachment menu, private image/PDF acquisition, compact private-file preview/removal, and existing WorkingProfile selector hook.
- Added Android Photo Picker, system camera temporary capture, constrained system document selection, bounded EXIF-aware image normalization, localized acquisition errors, and single-pending-file lifecycle cleanup. A one-localId ownership marker now closes the cold-start orphan gap while preserving restored pending state and persisted message attachments.
- Preserved text-only advisor behavior and explicitly gated every attachment send until multimodal protocol/Worker transport arrives in PR #3.

## 2026-10-05 — multimodal transport / protocol v4

- Enabled one persisted JPEG, PNG, or PDF to stream from Android private storage through Worker multipart validation to GPT-6 Luna.
- Added image `input_image`/high-detail and PDF `input_file` data-URL request shaping while preserving response chaining and the local-tool continuation loop.
- Kept protocol v2/v3 and text-only paths compatible, with attachment bytes restricted to the initial `/start` or `/message` request.

## 2026-10-05 — attachment turn lifecycle

- Enabled attachment-only USER submission and attachment-derived conversation titles without placeholder message text.
- Completed pending-to-Room ownership handoff: pending ownership remains until USER metadata commits, then marker release is attempted synchronously without deleting the persisted private file; release failure is harmless and reconciled later.
- Kept failed-turn retry behavior intact, including restored text/attachment state and preserved private bytes, while current conversation reloads retain USER attachment metadata.

## 2026-10-05 — sent attachment rendering + final attachment UX polish

- Added persisted USER image/PDF rendering in current chat and restored history using the existing private attachment file; PDFs show sanitized filename and readable size, images render above optional user text, and timestamps retain the existing bubble layout.
- Added graceful missing/corrupt-file presentation with no private path/content disclosure.
- Finalized composer import UX with a compact loading/preview row, always-available remove action, send disabled during preprocessing, and generation-guarded serialized imports so stale results cannot cross conversations or replace newer selections.
- Tightened large-image decode sampling while preserving the existing normalization pipeline and 4096 px output cap.
- Added focused coverage for persisted image/PDF render state, database recreation, missing/truncated files, loading send gating, stale selections/conversation invalidation, stale-file cleanup, removal, and text-only behavior.
- Kept provider behavior, advisor protocol v4, local-tool authority, response chaining, and PR #76 ownership/recovery semantics unchanged; no proxy runtime change is required.


## 2026-10-06 — live KWANT search and stock repair

- Follow-up live Android-vs-browser auditing showed that Next-data returns only the search page shell; actual results come from `POST https://services.kwant.net.pl/api/front/search-engine/page`.
- Android search now calls that frontend API directly with `q/page/limit/tags` and parses candidates from `hits[]`; buildId and Next-data are no longer part of KWANT search discovery.
- MBN116E, EAN 3250614312762, and the plain-text Hager query are covered by realistic frontend-API fixtures and preserve candidate product ID `580` followed by the existing exact lookup.
- Central stock still comes from structured main-product `product.stock`; selected branch stock remains bounded to the main product availability block so recommendation cards cannot leak their stock into the verified product.
- Live-probe evidence now records the trusted `services.kwant.net.pl` search API POST and uses its body `q` as query-specific evidence instead of treating a Next-data request as search proof.
- OBI paths, proxy runtime, prompts, provider routing, protocol versions, WorkingProfile behavior, branch lookup, and Room schema remain unchanged.


## 2026-10-06 — KWANT selected-branch stock repair

- Live probe of product 580 / branch 205 showed raw Android-like HTML has the selected branch name but no branch quantity, while the hydrated frontend calls `/api/front/products/580/current?depstock=205` and receives root `department_stock` for the main product.
- Exact KWANT lookup now keeps page parsing for identity, online price, canonical URL, and central stock, then performs one fail-soft current-product request for selected-branch stock only.
- Parser validation requires exact `product_id` and `department_id`; zero is preserved, missing/malformed data remains null, and recommendation stock cannot contaminate the result.
- KWANT search API from #79, OBI, proxy, prompts, WorkingProfile, advisor routing, and Room remain unchanged.


## 2026-10-06 — advisor product facts bounded before continuation

- Added one shared Android text/fact shaping policy used by both `ProviderProduct -> AdvisorVerifiedProduct` and OBI `LocalProduct -> AdvisorVerifiedProduct`.
- `FindProviderProductsTool` plus normal-search and exact-OBIK paths in `FindObiProductsTool` now all pass through that same policy before continuation serialization.
- Oversized descriptions/facts are normalized and bounded to the existing Worker contract (220-char description, six facts, 60-char labels, 100-char values); blank facts are discarded in stable source order.
- Added oversized provider, serialized v3 `/continue`, and oversized OBI search/exact regressions while preserving OBIK/provider identity, price, branch stock, central stock, and already-bounded content.
- Proxy limits were not loosened; provider parsers and all routing/persistence/prompt behavior remain unchanged.


## 2026-10-07 — conservative advisor fact relevance rescue

- Extended the shared OBI/provider advisor shaper with source-order baseline + one optional query-relevant rescue when more than six valid technical facts exist.
- Normal/no-match behavior remains the first six valid normalized/bounded facts in source order. A later fact is admitted only for a clear deterministic lexical/value signal and only if it is stronger than the weakest selected fact.
- Added exact-value coverage for 16 A, 400 V, IP65, 2,5 mm2 and 3 phases, plus no-match/no-aggressive-reorder, minimal displacement, OBI/provider parity, and tool-level OBI/provider query propagation.
- Existing continuation bounds, identity/price/stock fields, parsers, proxy contract, protocol versions, prompts, WorkingProfile, Room, attachments, and web-search policy remain unchanged.


## 2026-10-07 — shared OBI + KWANT branch/location routing

- Added one provider-neutral branch resolver using real `ProviderBranch` IDs, names and addresses.
- Replaced OBI's number-only branch metadata with a canonical 62-market directory verified against OBI Poland's official customer-relations list; Kraków Wielicka is market 003 and Nowy Sącz remains 075.
- Advisor authorization resolves the current USER message against only the active provider directory. Natural metadata now requires an explicit branch/store location phrase and adjacent location metadata; incidental city/street words stay `NotMentioned`. Unique authorized references rewrite only the turn-local tool branch, ambiguous/unknown explicit references reject instead of falling back, and raw model `requestedBranch` cannot authorize a switch.
- KWANT routing uses the same resolver over its existing live branch metadata; verified examples include Nowy Sącz 205 and Zamość 128.
- Current-branch aliases (`u nas`, bounded Nowy Sącz/Sączu forms) remain on the conversation branch. Cross-branch lookup does not mutate WorkingProfile, protocol versions, product search/lookup contracts, stock endpoints, Room, attachments, prompts, or advisor fact shaping.


## 2026-10-08 — Advisor observability B2.1

- Added one privacy-safe structured `advisor_protocol` Cloudflare log event per protected Advisor Worker request, including protocol/provider/branch/stage, observable response/tool counts, normalized usage/cost when present, total request latency, and safe failure categories.
- Added turn correlation through `X-Taksula-Trace-Id`: START/MESSAGE always create a new trace, while CONTINUE reuses a valid propagated trace or fails soft to a new trace when correlation is unavailable. Each HTTP request also receives an internal requestId.
- CONTINUE logging records only deterministic aggregate local-result counts and safe rejection category; no user/model/query/product/attachment content, product identifiers, stock/price values, response/call IDs, upstream bodies, credentials, or chain-of-thought enter structured logs.
- JSON protocol bodies, prompts, provider behavior, tool schemas, retrieval, attachments, usage pricing, Android, and protocol versions are unchanged. Android propagation/reporting remains B2.2.


## 2026-10-09 — Android Advisor observability B2.2

- Android now captures the Worker's optional `X-Taksula-Trace-Id`, validates it as UUID-form metadata, and propagates one trace across every CONTINUE in the same USER turn without sending an old trace on START/MESSAGE.
- A valid trace returned by a continuation becomes authoritative for later continuations; a missing/invalid later trace does not erase an already-established turn trace.
- Successful ASSISTANT responses persist the final trace in Room schema v11 via migration 10→11. Historical pre-B2.2 messages keep null trace and conversation/history reload restores new traces normally.
- Existing problem-report TXT output now carries the persisted assistant trace, while failure diagnostics include the known Worker/current-turn trace when available. Unrelated general reports do not receive a random previous Advisor trace.
- No Advisor behavior, Worker implementation, JSON schema, tool contract, provider/retrieval behavior, attachment lifecycle, pricing, chat layout, telemetry database, or dashboard changed.


## 2026-10-09 — behavioral eval fidelity cleanup

- Scenario B now distinguishes a bounded factual surfaced-count statement from an actual completeness/exclusivity claim; wording such as three verified variants is not a failure unless the answer semantically claims those are the whole assortment.
- Scenario D now evaluates the approved State-2 behavior: keep the decision-critical parameter unresolved, safely browse and surface both plausible candidates in the same turn, and prohibit premature compatibility/correctness claims. A literal question mark is not required when the answer gives a concise actionable way to establish the missing parameter.
- Deterministic protections for local browse, both ambiguity branches, surfaced candidate refs, grounding, web policy, and premature-match rejection remain intact. No production Advisor prompt or runtime behavior changed in the eval cleanup.
