# TAKSULA R3 — Offline Reliability Fault-Injection Matrix

> **Scope:** read-only source evidence + synthetic offline tests on PR #115. Baseline `4b80a40c00cec12ab4f2e93b91c502c0c7c14b98`. These are **contract-level Android and Worker tests**, not an Android-to-deployed-Worker E2E execution. No production traffic.

## Reproduce

- Proxy: `cd proxy && npm ci && npm run typecheck && npm test && npm run eval:build && node --check evals/run-taksula-behavior.mjs`
- Android: `./gradlew check assembleDebug`
- CI: `.github/workflows/proxy.yml` and `.github/workflows/android.yml`.
- Synthetic fixtures: `proxy/tests/*.test.mjs`, Android `app/src/test/java/pl/lukaszpeciak/towarownik/**`.
- **Interpretation:** PASS means the cited offline test coverage passed in the reported CI HEAD; it is not device certification. NOT TESTED indicates no fully verified scenario-level coverage for the specific statement.

## Scenario-to-evidence matrix

| ID | Scenario | Existing/new test or fixture | Expected result | Failure category | Trace behavior | Status |
|---|---|---|---|---|---|---|
| A01 | Worker completed START/MESSAGE/CONTINUE | agent.test.mjs:532,1632,1716 | 200, valid normalized result | success | START/MESSAGE fresh; CONTINUE propagated | PASS |
| A02 | OpenAI HTTP 429/500 and rejected fetch | agent.test.mjs:upstreamFailureCases | 502 upstream_failure; rate_limit/http_5xx/network | bounded upstream category | UUID response trace | PASS |
| A03 | Malformed JSON/envelope/tool call | agent.test.mjs:upstreamFailureCases,1588,1601 | 502; no tool execution | invalid_json/envelope/tool_call | UUID trace | PASS |
| A04 | Incomplete output-budget, failed/cancelled/nonterminal | agent.test.mjs:noncompletedResponseCases | 502 even with valid-looking output | incomplete_output_budget/incomplete_other/response_not_completed | UUID trace | PASS |
| A05 | Deterministically delayed upstream START | agent.test.mjs:R3 delayed upstream remains pending | pending before release; 200 after release | success; measured once | fresh UUID | PASS |
| A06 | Failed and repeated CONTINUE | agent.test.mjs:1754,1776 | 502 on failed; repeated requests not deduplicated | network on failure | valid prior trace retained | PASS |
| A07 | Missing/invalid trace headers | observability.test.mjs:203-290 | regenerate invalid; keep valid CONTINUE | none | new UUID | PASS |
| B01 | Android DNS/TLS/timeout/IO classification | AdvisorFailureDiagnosticsTest.kt:23-42 | conservative bounded enum; no exception text | DNS/TLS/TIMEOUT/NETWORK_IO | none before server | PASS |
| B02 | Android HTTP 502 trace and proxy code | AdvisorFailureDiagnosticsTest.kt:44-68 | SERVICE + HTTP_ERROR; allowlisted error | HTTP_ERROR | validated UUID | PASS |
| B03 | Android malformed HTTP 200 | AdvisorFailureDiagnosticsTest.kt:69-85 | fail closed | RESPONSE_PARSE | if valid UUID | PASS |
| B04 | Android cancellation and delayed callback | AdvisorFailureDiagnosticsTest.kt:86-115 | no resumed success after cancellation | cancellation propagated | no erroneous publication | PASS |
| B05 | Duplicate send and failed-turn recovery | MainActivity/AdvisorRequestGuard tests (existing; no combined Worker run) | single active send, recovery scoped | existing UI error | turn-scoped | NOT TESTED |
| B06 | START/MESSAGE/CONTINUE stage and traces | AdvisorControllerTest.kt:1829-1915 | stage-specific diagnostics, no cross-turn trace | NETWORK | fallback trace on CONTINUE | PASS |
| C01 | Verified/not_found/unavailable partial groups | agent.test.mjs:1926; AdvisorControllerTest.kt:180-260 | group statuses preserved; no invented facts | provider unavailable separate | turn-scoped | PASS |
| C02 | OBI/KWANT branch authorization | AdvisorControllerTest.kt:750-1053,1141-1250 | unauthorized cross-branch rejected | local rejected | no hidden branch ID in new logs | PASS |
| C03 | Location stock null vs zero | product-locations.test.mjs:124,175,260 | zero preserved; missing stays unknown | validation failure for malformed | continuation trace | PASS |
| C04 | KWANT two-product limitation | provider-specific Android tests not exhaustively linked | enforce documented per-query behavior | provider-specific | turn scoped | NOT TESTED |
| C05 | Three local calls and fourth rejection | AdvisorControllerTest.kt:224,562,623,662 | no fourth provider call | local budget reached | turn-scoped | PASS |
| D01 | v4 single image/PDF and JSON continuation | protocol.test.mjs:809,824,951; AdvisorAttachmentTransportTest.kt:458-537 | transport format preserved | invalid_request for malformed | request-scoped | PASS |
| D02 | v5 1–3 attachments, text validation | protocol.test.mjs:487-712 | order and validation preserved | invalid_request | request-scoped | PASS |
| D03 | Payload exact size and 24 MiB limits | protocol.test.mjs:569-771 | accept boundaries/reject excess | 413 request_too_large | request-scoped | PASS |
| D04 | Truncated streaming and malformed multipart | AdvisorAttachmentTransportTest.kt:415,807; protocol.test.mjs:686,845-939 | fail locally or before OpenAI | PROTOCOL / invalid_request | no spurious trace | PASS |
| D05 | Failed attachment turn recovery | Android conversation recovery tests; not a combined injected pipeline | user attachments recover only in active turn | UI error | active turn only | NOT TESTED |
| X01 | True cross-language Android app → Worker runtime | No full Android+Worker harness in CI | real integrated execution | N/A | N/A | NOT TESTED |
| X02 | Cloudflare runtime cancellation and real device DNS/TLS | no production/device testing permitted | unknown | unknown | unknown | NOT TESTED |

## Limitations and risks

- **P0:** No real Android ↔ Worker integrated CI test, therefore no empirical cross-language scheduling/deadline, network-loss or UI crash evidence. Perform bounded real-device AAB test before pilot.
- **P1:** Real Cloudflare runtime, DNS/TLS path, and 30-second Android call deadline interactions remain unverified; 502 does not tell whether upstream completed after a lost response.
- **P1:** Duplicate CONTINUE is **not idempotent**; tests confirm two upstream calls rather than claiming deduplication.
- **P1:** KWANT two-product cap and failed attachment recovery should receive a tightly scoped dedicated follow-up if existing tests cannot be traced explicitly.
- **P2:** Synthetic timing gate proves pending/release behavior, not wall-clock timeout precision.
- **Privacy:** R3 tests and matrix must contain only synthetic data. Existing Worker observability tests validate log redaction; do not turn on production logging.

## Recommended next action

**R4: bounded release AAB device test**, using trace IDs from explicit failure captures and a reproducible manual script for START/MESSAGE/CONTINUE with one OBI and one KWANT conversation, attachment upload, interruption, app background/foreground and recovery. Do not deploy this test PR or automatically retry uncertain outcomes.

## Rollback

Close this unmerged test-only PR; no Worker deployment, Android schema migration, service configuration, public protocol, timeout or model budget changed.
