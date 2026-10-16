# INC-008 D1 — response-bound KWANT inventory diagnostics

Base: `6ea345a486b9457aa64d146c1687c393e984e68a` (main, includes P0-A #127 and P0-B #128). This is an evidence-only extension of the existing TXT problem report. The original INC-008 device root cause remains UNKNOWN.

## Existing flow and minimal change

`KwantProductLocationsAdapter` already produces typed failure and coverage; `AdvisorLocationsTool` already creates exact provider/product identity, checked/returned/missing branch IDs, status/reason, timestamp and null-stock rows. D1 carries that existing evidence through the Controller's turn-local result into the existing report path. The only new transport evidence is HTTP status and a fixed content-type classification (`json`, `html`, `other`, `missing`), when an HTTP response exists. No raw media-type header, exception message or JSON structure is retained. The stock parser is unchanged.

`ConversationDao.completeAssistantTurn` already knows the inserted message ID. It now returns that ID through `ConversationRepository`, without changing the transaction, table schema or stored message. MainActivity records the diagnostic under `(conversationId, messageId)` only after that completion succeeds. The existing report resolver first verifies that the exact target ASSISTANT message still exists, then retrieves only that key. It never asks for the latest lookup. Conversation deletion clears matching entries.

Storage is a synchronized insertion-ordered session map in the existing report-evidence file: at most 16 completed messages, at most three tool results per message, at most 100 numeric IDs per list with at most 12 digits per ID. It stores only the sanitized text projection, roughly bounded below 300 KiB at maximum IDs/calls/messages. Recording the same message cannot replace its first immutable snapshot. A resolver-selected String stays immutable if later results arrive. Eviction or Activity/process recreation produces explicit unavailable evidence, never evidence borrowed from another turn. No new database, migration, Worker field, tool schema, logging service or diagnostic upload is introduced.

The diagnostic is included only in an explicit ASSISTANT response report. It is independent of the conversation-context checkbox because it contains technical evidence for the selected response; it adds no earlier conversation text. General and transient ADVISOR_FAILURE reports keep their existing ownership semantics. The Android stock message and product cards are unchanged.

## Report contents and synthetic samples

The report identifies `tool=find_product_locations`, provider, exact trusted numeric product ID, authorized requested branch IDs (even if the adapter's directory read fails), status/reason, coverage, checked/returned/missing IDs, positive/zero/unknown counts, verification time when present, and HTTP status/content-type class when present. Stock quantities, product/branch descriptions, request URLs, cookies and headers are omitted.

Example common block, generated from synthetic failure evidence:

```text
--- KWANT INVENTORY DIAGNOSTICS ---
tool=find_product_locations provider=kwant-pl product_id=580
requested_ids=[205]
status=unavailable reason=malformed_response
coverage=unknown
checked_ids=[205] returned_ids=[] missing_ids=[205]
stock_states=positive:0,zero:0,unknown:1
verified_at_millis=unavailable
http_status=200 content_type=json
```

Other tested outcomes use the same identity/branch fields and these exact category-specific values:

| Controlled outcome | Sanitized report lines |
| --- | --- |
| Positive stock | `status=verified reason=none`; `coverage=requested_subset`; `returned_ids=[205] missing_ids=[]`; `stock_states=positive:1,zero:0,unknown:0`; `verified_at_millis=1234`; `http_status=200 content_type=json` |
| Confirmed zero | Same verified coverage/returned IDs; `stock_states=positive:0,zero:1,unknown:0` |
| Null stock / missing stock field | `status=unavailable reason=no_trusted_rows`; `coverage=partial`; `returned_ids=[] missing_ids=[205]`; `stock_states=positive:0,zero:0,unknown:1`; `verified_at_millis=unavailable`; `http_status=200 content_type=json` |
| Requested row omitted | Same `no_trusted_rows` evidence; no inference that the omitted branch has zero |
| Malformed JSON / unsupported response shape / wrong response product | Common malformed block above; the reported ID is requested trusted 580, not the mismatched response identity |
| HTTP failure | `status=unavailable reason=transport`; `coverage=unknown`; `http_status=403 content_type=html` |
| Transport failure without HTTP response | `status=unavailable reason=transport`; `http_status=unavailable content_type=unavailable` |
| Non-JSON response | `status=unavailable reason=malformed_response`; `http_status=200 content_type=other` (or `html` for HTML) |
| Directory failure after typed authorization | `requested_ids=[204]`; `reason=directory_unavailable`; `checked_ids=[] returned_ids=[] missing_ids=[]`; no invented HTTP status or stock observation |

All sample quantities/timestamps are test controls, not device or live KWANT observations. A report without a captured exact trusted product ID has no inventory snapshot; raw model product arguments are not treated as evidence.

## Validation and privacy review

Focused tests exercise real HTTP response handling with in-process interceptors, real KWANT adapter/parser, typed authorization, local tool, Controller, report snapshot storage and TXT formatting. They cover both 580/205 and 580/204, positive/zero/null/missing, wrong identity, malformed response, HTTP and IO failure, canonical request attribution, cancellation, unchanged stock text and continuation response/call IDs. An explicit serializer test verifies local metadata is absent from Worker JSON. Room-backed resolver coverage verifies the returned insertion ID and report creation after a later response; final Android CI executes this path with generated Room code.

Privacy canaries cover response body, Set-Cookie, Content-Type parameters, exception text, branch/product descriptions and unsafe arbitrary identifiers/reasons. Reports project numeric IDs, static status/reason/coverage tokens, counters, optional validated status and an enum. They do not retain raw JSON, arbitrary upstream field names, headers, cookie/session values, Authorization, prompts or attachment contents. Existing voluntarily included report text/product cards remain governed by the existing reporting mechanism; D1 does not add raw upstream descriptions to diagnostics.

No live provider requests or real-model calls are part of this implementation. No Worker files, product-search behavior, parser semantics, HTTP request policy, authorization or final stock rendering are changed. The local Gradle attempt is blocked at startup (`FileLockContentionHandler`: `Could not determine a usable wildcard IP for this machine`). Native test results and final-head full Android CI are recorded in the PR; a blocked local build is not a CI pass. No AAB, deployment or merge is authorized.

## Read-only routing assessment: INSUFFICIENT EVIDENCE

For a future single explicitly authorized branch, `/current?depstock=BRANCH` has an established exact-product **and** exact-department response check. Departments identity is bound to the product URL plus optional response IDs; branch rows are mapped to a fresh canonical directory. A single-branch current read would avoid whole-list rejection caused by an unrelated row and transfer less data. Neither fact proves that today's live failure is a departments parser failure or that current is more reliable across devices/networks.

The device observed `/current` succeed and departments fail, but without the failing local reason/status/body and adjacent control reads this is not a measured reliability comparison. October 11 research found both endpoints agreeing for branch 205 at that time. Both paths make fresh HTTP reads; there is no evidence that the endpoint named current guarantees newer data than departments. Historical stock 398 must never be reused.

USER-selected branch authorization must remain the existing typed authority; changing endpoints cannot authorize a branch or mutate WorkingProfile. A direct existing current-stock service read could replace one stock GET with one stock GET for a single branch. Calling the full product lookup instead would also fetch directory/product HTML and add unnecessary work. Multi-branch/nationwide inventory should keep its one product-bound departments request; replacing that with one current read per branch would increase HTTP work and alter the existing bounded inventory contract.

Recommendation: keep routing unchanged in D1 and collect the new response-bound evidence first. Reconsider a separate single-branch current path only after paired endpoint evidence and scoped regression tests justify it; preserve the one-request departments path for multiple branches. No routing change is implemented here.

## Limits and remaining evidence

- Session-only diagnostics disappear on Activity/process recreation and after oldest-entry eviction. Reporting the message still works and marks the diagnostic unavailable.
- If the Worker continuation fails and no ASSISTANT response is committed, there is no response snapshot to attach; the existing separate ADVISOR_FAILURE report remains available for that failed turn.
- Null stock, missing stock fields and omitted requested rows retain the existing shared `no_trusted_rows` semantics. This PR does not add a second JSON parser or a detailed parser-rejection taxonomy.
- Status/content-type class narrow transport versus content/parser failures. They do not prove which field violated the parser or explain an HTTP policy change. Obtain a bounded, vetted raw endpoint fixture separately if the result is `malformed_response`.
- Existing message 33's historical incident cannot be reconstructed retroactively. Use a future explicit ASSISTANT report for a reproduced lookup, then correlate its persisted Advisor trace separately from the direct KWANT HTTP request.

Stop after the focused PR and final-head Android CI; independent audit is required before merge or release work.
