# INC-007 R2 — executed recovery evidence

Date: 2026-10-14, Europe/Warsaw. One focused Android PR for independent audit. No merge, deployment, AAB, version bump, prompt tuning or provider transport changes.

## Revisions and reference integrity

- Fetched base/main: `717d2deba901272c60b9a9177bc147bfb80c5cb4`, confirmed merge of [PR #125](https://github.com/lukaszpeciak91-sys/towarownik/pull/125).
- Original audited source: `fe9e8d79f1a736cd4cb2289b525d5bc872ce554b`.
- Permanent test-only RED commit: `8bcc149`.
- Final SHA and final-head CI URLs: PR delivery/checks; do not use an earlier CI run as final-head proof.
- Supplied diagnostic SHA-256: `c0477907f1021d0d479a154ad36f73b904131f41781918dc16dacac3db8506ff`.
- Supplied reproduction SHA-256: `62759f79d43ca3956c0ef3c6a8cee4a635557b0ddb7a0ab19c36e6559c9715df`.
- Original and permanent Inc007E2eTest SHA-256: `f98e5920d714b66585e8635f0989ca3ed21eb8294f3a19a6ef15548afc2412e0`.

The supplied report's read-only/proposal labels describe that earlier investigation. This implementation follows the user's separately supplied R2 action request. The original reproduction file and all 17 original fixtures/expectations are unchanged.

## Minimal root causes and repair

1. Retry/filler admission was not a canonical clarification, so turn 3 failed inventory intent and severed turn 4's USER chain. Normalize only a complete location/retry clause, then resolve it through the canonical active-provider directory. Unrelated suffixes still fail.
2. Location-tool admission ran inventory gates on an independent bare identifier. Classify standalone current identifiers separately before history permission. An incorrect location selection performs bounded existing discovery of that exact USER query; it never searches a model-guessed internal ID.
3. The previous explicit-article slot missed a new catalog code appearing before the stock command, allowing an older single card to supply identity. Check all bounded explicit catalog tokens, including unknown tokens, before selection. Numeric dimensions are excluded.
4. A scope negative exposed the existing watt guard dropping `w` after a numeric product ID. Unknown/ambiguous USER cities could become profile discovery. Narrow that guard; canonical explicit locations remain authoritative, quantities do not authorize market IDs.

Recovery of a bare identifier consumes the original location-call allowance plus one real discovery allowance, at most two of three calls for this operation. Discovery verifies up to five bounded candidates and requires exactly one matching identity. It returns only the fresh selected-branch facts already retrieved by normal lookup through the original location-result shape. No departments/all-market scan is authorized by a bare identifier. Its original response ID, call ID, protocol, profile and trace remain unchanged.

For a new explicit identifier plus stock/location request, the model may use the existing rejected-location -> discovery -> location retry sequence: exactly three calls, no guessed product trust, same USER scope throughout. This is covered for Tarnów 204. The client does not synthesize model call/response IDs or force unrestricted free-form search.

## Actual executed counts

Counts below describe separate executions; overlapping suites must not be summed.

| Executed run | PASS | FAIL | SKIP |
| --- | ---: | ---: | ---: |
| Original pinned baseline, existing native 13-class suite | 215 | 0 | 0 |
| Original pinned diagnostic, 17 cases | 12 | 5 expected RED | 0 |
| Current-main native baseline | 215 | 0 | 0 |
| Current-main original diagnostic, 17 cases | 15 | 2 expected RED | 0 |
| Permanent original Gradle RED before changes | 15 | 2 | 0 |
| New focused baseline against immutable current-main production, 24 cases | 8 | 16 | 0 |
| Final complete Android unit suite | 822 | 0 | 0 |
| Final original diagnostic subset | 17 | 0 | 0 |
| Final new recovery/security subset | 24 | 0 | 0 |
| Final existing R1 OBI scope subset | 25 | 0 | 0 |
| Final existing natural-follow-up subset | 34 | 0 | 0 |
| Unchanged Proxy tests plus typecheck | 636 | 0 | 0 |
| Actual serialized continuations accepted by unchanged Worker | 8 | 0 | 0 |
| Python tooling preflight | 174 | 0 | 0 |

Native source extraction disables only the original documented Android factory/default network wiring; it is not a device run. Initial native execution used shell Java 21; an explicit JDK 17 recheck confirmed pinned/current native results. Gradle launcher/daemon is Temurin 17.0.16+8, Gradle 9.5.0; native Kotlin compiler is 2.3.21, Node 22.23.3, TypeScript 5.9.3. Three initial current-baseline MockWebServer tests were blocked by sandbox socket permission and passed when rerun with loopback permission; they are not source regressions. Fixture compilation errors are not executed test failures.

Final build command is `./gradlew check assembleDebug`. Its completion and final-head GitHub Actions run/job URLs are recorded in PR delivery. No signed bundle workflow is invoked.

## Exact KWANT conversation and fresh evidence

Both original six-turn paths preserve all exact USER strings and all original persisted ASSISTANT replies in [Inc007E2eTest.kt](../app/src/test/java/pl/lukaszpeciak/towarownik/agent/Inc007E2eTest.kt). The fixture stores those original replies even when corrected local rendering differs, so a successful test does not erase the historical refusals from the next turn's context.

**All replay provider responses are synthetic.** Only the supplied device report observed first-turn 203=0 / central=53. Synthetic 206=11, 204=13 and turn-5 205=0 are controls, not measured live stock. Verification times 1000/2000 are fixture timestamps. No live KWANT/OBI/OpenAI requests were made.

| Turn / exact USER | Fresh operation and result | Departments GETs |
| --- | --- | ---: |
| 1. `sprawdź proszę stan A9Z01440/SCH w Bochni` | START -> ordinary discovery -> exact 77556@203, snapshot stock 0 / central 53 | 0 |
| 2. `a w oddziale w Mielcu?` | MESSAGE -> exact 77556 departments read -> checked 206, synthetic 11 | 1 |
| 3. `nie sprawdzisz mi w Mielcu?` | MESSAGE -> new exact 77556 departments read -> checked 206, synthetic 11; no false intent refusal | 1 |
| 4. `dobrze a w oddziale Tarnów?` | MESSAGE -> new exact 77556 departments read -> checked 204, synthetic 13 | 1 |
| 5. `A9Z01440/SCH` | MESSAGE -> ordinary discovery, or bounded discovery inside mistaken location request -> fresh 77556@205, synthetic stock 0 | 0 |
| 6. `co do kurwy? a co to niby jest jak nie prośba? A9Z01440/SCH weź kurwa sprawdź stan w Tarnowie` | MESSAGE -> new exact 77556 departments read -> checked 204, synthetic 13; no 205 fallback | 1 |

Each history executes START once, MESSAGE five times and CONTINUE six times. Assertions verify `resp_1…resp_6` / `call_1…call_6`, previous final-response chaining and saved kwant-pl/205 on every continuation. There are **four departments GETs per history, eight across these two fixtures**; every URL ends in `/77556/departments?extended=1`. There are also two ordinary exact-product lookups per history (203 at turn 1, 205 at turn 5). No historical branch/central stock supplies later inventory.

Discovery-selected cards are current-turn snapshots. Location-only turns do not promote historical cards. In the original mistaken-tool fixture the model chooses no productRefs on turn 5, so no card is forced into that answer; the separate recovery/card test selects the fresh 77556@205 snapshot and verifies its stock/time. Wrong refs cannot create a card.

Actual captured full-history turn logs, first discovery-at-turn-5 and then mistaken-location-at-turn-5:

```text
INC007 provider=kwant-pl turn=1 plan=discovery continuation=Verified status=null reason=null checked=null getDelta=0 cards=[77556@203] text=Synthetic model answer
INC007 provider=kwant-pl turn=2 plan=locations continuation=Locations status=verified reason=null checked=[206] getDelta=1 cards=[] text=Wskazane oddziały KWANT: sprawdzono 1, dodatni stan: 1, zero: 0, niepotwierdzone: 0 (stan zweryfikowany nie wcześniej niż 01.01.1970 00:00)|206 — Mielec — 11
INC007 provider=kwant-pl turn=3 plan=locations continuation=Locations status=verified reason=null checked=[206] getDelta=1 cards=[] text=Wskazane oddziały KWANT: sprawdzono 1, dodatni stan: 1, zero: 0, niepotwierdzone: 0 (stan zweryfikowany nie wcześniej niż 01.01.1970 00:00)|206 — Mielec — 11
INC007 provider=kwant-pl turn=4 plan=locations continuation=Locations status=verified reason=null checked=[204] getDelta=1 cards=[] text=Wskazane oddziały KWANT: sprawdzono 1, dodatni stan: 1, zero: 0, niepotwierdzone: 0 (stan zweryfikowany nie wcześniej niż 01.01.1970 00:00)|204 — Tarnów — 13
INC007 provider=kwant-pl turn=5 plan=discovery continuation=Verified status=null reason=null checked=null getDelta=0 cards=[77556@205] text=Synthetic model answer
INC007 provider=kwant-pl turn=6 plan=locations continuation=Locations status=verified reason=null checked=[204] getDelta=1 cards=[] text=Wskazane oddziały KWANT: sprawdzono 1, dodatni stan: 1, zero: 0, niepotwierdzone: 0 (stan zweryfikowany nie wcześniej niż 01.01.1970 00:00)|204 — Tarnów — 13

INC007 provider=kwant-pl turn=1 plan=discovery continuation=Verified status=null reason=null checked=null getDelta=0 cards=[77556@203] text=Synthetic model answer
INC007 provider=kwant-pl turn=2 plan=locations continuation=Locations status=verified reason=null checked=[206] getDelta=1 cards=[] text=Wskazane oddziały KWANT: sprawdzono 1, dodatni stan: 1, zero: 0, niepotwierdzone: 0 (stan zweryfikowany nie wcześniej niż 01.01.1970 00:00)|206 — Mielec — 11
INC007 provider=kwant-pl turn=3 plan=locations continuation=Locations status=verified reason=null checked=[206] getDelta=1 cards=[] text=Wskazane oddziały KWANT: sprawdzono 1, dodatni stan: 1, zero: 0, niepotwierdzone: 0 (stan zweryfikowany nie wcześniej niż 01.01.1970 00:00)|206 — Mielec — 11
INC007 provider=kwant-pl turn=4 plan=locations continuation=Locations status=verified reason=null checked=[204] getDelta=1 cards=[] text=Wskazane oddziały KWANT: sprawdzono 1, dodatni stan: 1, zero: 0, niepotwierdzone: 0 (stan zweryfikowany nie wcześniej niż 01.01.1970 00:00)|204 — Tarnów — 13
INC007 provider=kwant-pl turn=5 plan=locations continuation=Locations status=verified reason=null checked=[205] getDelta=0 cards=[] text=Wskazane oddziały KWANT: sprawdzono 1, dodatni stan: 0, zero: 1, niepotwierdzone: 0 (stan zweryfikowany nie wcześniej niż 01.01.1970 00:00)|205 — Nowy Sącz — 0
INC007 provider=kwant-pl turn=6 plan=locations continuation=Locations status=verified reason=null checked=[204] getDelta=1 cards=[] text=Wskazane oddziały KWANT: sprawdzono 1, dodatni stan: 1, zero: 0, niepotwierdzone: 0 (stan zweryfikowany nie wcześniej niż 01.01.1970 00:00)|204 — Tarnów — 13```

## Security and regression witnesses

| Witness | Executed outcome |
| --- | --- |
| 45 consecutive mistaken location selections per provider | 45 fresh exact lookups per provider; per-turn budget reset; no departments scan |
| Existing 40 normal searches per provider and 40 bare OBIKs | R1 suite remains GREEN, independently verified current cards |
| Bare EAN routed to locations | Existing search/exact verification, fresh profile-bound card, no guessed-ID inventory |
| Product A -> prices -> independent B -> `a w Tarnowie?` | B=77557, fresh check only 204; no A leakage |
| Provider failure -> independent B | New query succeeds; failure does not poison next turn |
| New explicit article before command, or known + unknown article | Reject untrusted identity, zero inventory GETs |
| Price/advice/mounting text and suffixes, unrelated short messages | No inventory GETs and no native recovery lookup |
| ASSISTANT-only place / missing USER-owned `tam` | No stock read; unique USER place positive control ignores suggestion |
| Wrong provider, missing/duplicate directory | No discovery recovery or inventory |
| Unknown/ambiguous USER city after numeric ID | No discovery lookup or inventory; no profile substitute |
| Corrupt fresh ID / duplicate exact catalog candidates | No card or inventory; untrusted/ambiguous reason |
| Explicit Tarnów with missing identity | Rejection -> discovery @204 -> exact stock @204, three distinct continuations |
| Repeated bare-tool requests | Only one discovery; subsequent requests hit original shared call cap |
| Zero / null / provider transport failure | Zero preserved; null/missing rows unknown; transport reason preserved; stale 99 excluded |
| R1 bare OBIK / complete Kraków | All four 003/019/059/072, no 075 fallback |
| Original UNKNOWN fingerprints | Transport, malformed identity/extra field, omitted/null rows retain truthful unavailable evidence |

Four R2 real ProxyClient serializations cover v2/v3 zero and unknown shapes, preserving original opaque response/call IDs. Four R1 serializations retain discovery-seed/profile separation and full city scope. All eight actual JSON bodies pass unchanged Worker `parseContinueRequest`; no protocol/schema change is required.

## Reproduction and limits

Executed external reference commands:

```bash
# With the supplied original file and official cached Maven dependencies:
export PATH="$JAVA_HOME/bin:$PATH"
python3 TAKSULA_INC-007_Reproduce.py --repo audited --deps deps --out pinned-jdk17
# Expected diagnostic exit 1: baseline 215/215, original 17/12/5.
# Current source replay keeps original test helpers byte-identical; only source HEAD changes.
java -cp 'current-red/classes:deps/*' pl.lukaszpeciak.towarownik.agent.R2NativeRunnerKt
java -cp 'current-red/classes:deps/*' pl.lukaszpeciak.towarownik.agent.Inc007RunnerKt
# Current-base diagnostic exit 1: original 17/15/2.
```

Repository final validation:

```bash
./gradlew :app:testDebugUnitTest
./gradlew check assembleDebug
cd proxy
npm run typecheck
npm test
# External wire replay uses actual bodies captured by MockWebServer:
node wire-replay.mjs
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=tools python3 -m unittest discover -s tools -p 'test_*.py'
```

Residual limits: this proves deterministic Android orchestration with scripted model output and injected provider/loopback data, not live model ordering or device behavior. A mistaken location request for an unrestricted product name still requires model discovery; a new explicit stock/location identifier can require the existing three-step recovery sequence. Missing historical EAN aliases are not invented. Real Mielec/Tarnów availability, directory freshness and the separate OBI CONTINUE 502 remain unknown; no parser/502 attribution or fix is claimed.

**GO for independent audit once final-head CI is GREEN. No merge/release authorization is implied.** Device acceptance and any live/AAB work remain separately authorized after audit. Stop after the focused PR.
