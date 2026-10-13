# Taksula proxy

This directory contains the Cloudflare Worker boundary for Taksula's AI assistant.

## Endpoints

### Public health

```text
GET /health
```

Returns:

```json
{"ok":true,"service":"towarownik-proxy"}
```

The public product name is **Taksula**. The existing `towarownik-proxy` Worker/service name and `TOWAROWNIK_APP_TOKEN` secret name are retained as technical identifiers.

Health does not require either Worker secret.

### Authenticated AI start

```text
POST /v1/agent/start
Authorization: Bearer <app token>
Content-Type: application/json
```

Request:

```json
{"message":"...","storeNumber":"075"}
```

The message is trimmed/whitespace-normalized and bounded. Model, instructions, tools, reasoning effort, output budget, and OpenAI URL are server-controlled.

### Authenticated AI message

```text
POST /v1/agent/message
Authorization: Bearer <app token>
Content-Type: application/json
```

Request:

```json
{"previousResponseId":"...","message":"...","storeNumber":"075"}
```

This endpoint handles a new USER turn in an existing customer conversation. It passes the stored prior final response as `previous_response_id` and sends only the new normalized user message. Model, instructions, tool definition, reasoning effort, and output budget remain server-controlled.

### Authenticated AI continue

```text
POST /v1/agent/continue
Authorization: Bearer <app token>
Content-Type: application/json
```

This endpoint accepts the compact result of one previously requested local tool call and continues the same OpenAI Responses turn with `previous_response_id` plus `function_call_output`.

Both AI endpoints may return one normalized envelope:

```json
{"type":"answer","responseId":"...","text":"..."}
```

or:

```json
{
  "type":"tool_request",
  "responseId":"...",
  "tool":{
    "name":"find_obi_products",
    "callId":"...",
    "arguments":{
      "storeNumber":"075",
      "queries":[{"query":"...","limit":5}]
    }
  }
}
```

Raw OpenAI responses, reasoning content/items, internal instructions, and upstream error bodies are never forwarded to Android. The only usage data forwarded is a bounded validated metadata object containing model, request type, token counts, estimated cost, and pricing version.

## OpenAI contract

The current cost-sensitive validation model is `gpt-6-luna` with low reasoning effort and a bounded output budget. Model choice is centralized and may be revisited after real evaluations.

Output policy: START/MESSAGE use **384**; CONTINUE uses **1024** only for a strictly validated product result containing at least two query-result groups or at least two returned products (also legacy v1 multi-product). Single-group zero/one-product, authorization/tool-limit rejection and location-inventory CONTINUE retain **384**. The client cannot override this policy. It does not increase the five-product or three-local-call caps or change model/reasoning/prompts/timeouts. 1024 is a bounded candidate motivated by one confirmed production exhaustion, not a guarantee of completion.

The Worker uses native `fetch` against the Responses API. It declares the existing strict `find_obi_products` application function plus the current built-in `{ "type": "web_search" }`, with `tool_choice: "auto"` and `max_tool_calls: 1` for built-in tools. Web search is selective rather than forced. File search, computer use, hosted shell, image generation, MCP, deep research, background mode, and streaming remain disabled.

Exactly one application-defined function is declared:

```text
find_obi_products(storeNumber, queries[])
```

`storeNumber` is one explicit three-digit string shared by the whole batch. `queries` contains 1–5 `{query, limit}` entries, every limit is at least 1, and the sum of all limits is at most 5. The Worker validates shape and model-produced arguments and returns the tool request to Android; Android owns supported-store authorization and executes OBI lookup.

## Usage and pricing telemetry

For every successful Responses API result, the Worker independently attempts to validate OpenAI `usage`. START, MESSAGE, and CONTINUE are explicit request types. Missing or malformed usage is dropped while the normalized answer/tool request remains valid.

For an **incomplete** Responses result, the same bounded usage validation now feeds only the existing safe diagnostic log. Status still fails closed with 502; incomplete text/tool output is never accepted. Unknown optional token details remain null; measured zeros remain zero. Invalid/missing metadata cannot replace the primary failure. Incomplete `estimatedCostUsd` and `pricingVersion` remain null because full web-tool cost is unknown; log-only failure usage is not forwarded to Android's cumulative usage counters.

Current pricing is server-controlled and versioned as `openai-gpt-6-luna-2026-09-28-web-v1` for `gpt-6-luna`:

- ordinary input: USD 0.10 / 1M tokens;
- cached input: USD 0.01 / 1M tokens;
- cache-write input: USD 0.125 / 1M tokens;
- output: USD 0.50 / 1M tokens.

For requests with more than 272,000 input tokens, pricing switches for the full request to 2× every input-side rate and 1.5× the output rate. Completed `web_search_call` search actions additionally cost USD 0.01 each (USD 10 / 1000 calls); merely declaring the web-search tool does not count as a call.

The Worker computes ordinary input as `inputTokens - cachedInputTokens - cacheWriteTokens` and validates that cached plus cache-write tokens do not exceed total input. Reasoning tokens are an output-usage detail and are never charged in addition to output tokens. Pricing arithmetic is performed in integer nanodollars before the bounded numeric USD estimate is serialized.

The successful envelope may therefore include:

```json
{
  "usage": {
    "model": "gpt-6-luna",
    "requestType": "START",
    "inputTokens": 1000,
    "cachedInputTokens": 400,
    "cacheWriteTokens": 100,
    "outputTokens": 100,
    "reasoningTokens": 50,
    "totalTokens": 1100,
    "webSearchCalls": 0,
    "estimatedCostUsd": 0.0001165,
    "pricingVersion": "openai-gpt-6-luna-2026-09-28-web-v1"
  }
}
```

No prompt/message/tool content, response IDs beyond the existing operational envelope, reasoning text, secrets, or raw upstream data are included in telemetry.

## Web search citations

Responses output may contain reasoning items, `web_search_call`, messages, and the existing application `function_call`. The normalizer ignores raw built-in-tool objects for Android transport and still recognizes one strict application function call when present.

For a final answer, only actual OpenAI `url_citation` annotations are normalized. Sources are stable-order URL-deduplicated, HTTPS-only, capped at six, with title <= 200 chars and URL <= 2048 chars. Raw search queries/results/actions and arbitrary metadata are never forwarded. Android persists only this normalized source list and renders clickable links; product selection remains the unchanged locally verified `productRefs` contract.

## Secrets and authentication

Worker secrets:

- `OPENAI_API_KEY` — used only by the Worker when calling OpenAI;
- `TOWAROWNIK_APP_TOKEN` — protects the two AI endpoints.

Never place either value in source control or in the Android app source.

`TOWAROWNIK_APP_TOKEN` is an initial abuse-prevention mechanism for Internal Testing. It is a shared application token, not a claim of strong per-device identity or user authentication.

If either required server secret is missing for an AI request, the Worker fails closed with a bounded `503` response. The Android bearer token is never forwarded to OpenAI.

## OBI boundary

This Worker does not scrape OBI and contains no OBI HTTP/parser implementation.

Android remains authoritative for:

- OBI search discovery;
- OBIK extraction;
- supported-store authorization;
- exact selected-store lookup;
- local stock;
- local price.

When OpenAI requests `find_obi_products`, Android uses its existing OBI repositories and returns a compact grouped result for the requested queries plus the authorized store context. Groups report `verified`, `not_found`, or `unavailable`; successful groups are preserved independently and the entire call contains at most five verified products / exact lookup attempts by requested budget. OBI HTML, Nuxt payloads, cookies, and parser internals are never accepted as the tool result or forwarded to OpenAI.

The Worker stores no conversation state in Cloudflare storage. Android persists only the final answer response ID for a conversation. New USER turns call `/v1/agent/message`; tool outputs inside that turn continue through `/v1/agent/continue`. Using `previous_response_id` avoids manually replaying the local transcript, but earlier chain input tokens remain billable.

## Limits and failure mapping

The start and message request bodies are bounded to 4 KiB and each normalized user message to 2,000 characters. Message continuation also bounds the previous response ID. The continue request body is bounded to 16 KiB, grouped product count to 5, query-group count to 5, total requested query limits to 5, tool query/product-name strings to 200 characters, and OBIK to exactly seven digits.

Client validation failures return bounded `400` or `413` JSON. Authentication failures return `401`. Missing Worker configuration returns `503`. OpenAI transport, non-success status, unknown tool output, or malformed OpenAI JSON returns bounded `502`. Raw upstream response bodies are not exposed and the Worker adds no application retry.

## Local checks

Requires Node.js 22.

```bash
cd proxy
npm ci
npm run typecheck
npm test
```

Tests use an injected fake OpenAI transport. They use no external network, no real API key, and no Cloudflare account.

## Cloudflare setup

For Cloudflare GitHub repository integration:

```text
Root directory: proxy
Worker name: towarownik-proxy
```

`wrangler.jsonc` requires no paid Cloudflare service binding. Deployment remains Cloudflare-managed; GitHub Actions only validates the proxy.


## Current OBI-fact freshness rule

Conversation history may mention older stock or price values. The server-controlled advisor instructions require a new `find_obi_products` call whenever the current user question depends on current store-`075` availability, stock, price, or choosing currently available products. Historical facts are not current authority. Stock `0` means unavailable; null stock or null price means unknown.

## Protocol v4 attachments

Protocol v4 accepts `multipart/form-data` on `/start` and `/message` only, with a JSON `payload` part and exactly one `attachment` part. Supported MIME/signature pairs are JPEG, PNG, and PDF, bounded to 16 MiB. Its Content-Length maximum is 16 MiB + 16 KiB, checked before multipart parsing. `/continue` and all v2/v3 traffic remain JSON-only.

## Opt-in protocol v5: multi-attachment transport

For START/MESSAGE with 1–3 attachments, send multipart `payload` (JSON with `protocolVersion: 5`, providerId, branchId, message and, on MESSAGE, previousResponseId) followed by **1–3 repeated `attachment` file parts** in user-selected order. Send the request header `X-Taksula-Attachment-Protocol: 5` to opt into the v5 pre-parse size bound; missing or conflicting header/payload markers fail closed. The existing v4 single-part behavior needs no new header and is not changed. V5 JSON START/MESSAGE without attachments is rejected; use existing v3 for provider text-only traffic.

Allowed MIME/signatures: `image/jpeg`, `image/png`, `application/pdf`. Each file must be 1 byte–16 MiB; the raw attachment sizes must total at most **24 MiB** across up to three files, and the declared multipart Content-Length must be at most **24 MiB + 16 KiB**, including framing. Missing/invalid/oversized declared length is rejected before formData parsing; all count, signature and MIME checks occur before any upstream forwarding. The Worker emits a single Responses USER input with optional `input_text` first, followed by all files in original order (`input_image`, `detail=high` for images; `input_file` for PDFs). No persistent proxy file storage is introduced; the Phase A1 UTF-8 text formats are documented below. `/continue` remains JSON-only with provider-aware `find_products` and matching v5 version throughout a tool-assisted turn.

Current Android multi-attachment UI sends 1–3 ordered image/PDF/TEXT files through v5; the legacy nullable single-file overload still sends v4 for older callers. Phase A2 enables TEXT selection and distinct document cards while reusing Room v12 and existing private-file ownership.


### Phase A1 — lightweight UTF-8 text attachments (v5 only)

V5 additionally accepts `.txt`, `.md`, `.csv`, `.json`, `.xml`, `.yaml`, `.yml`, `.log`, `.ini`, and `.conf` as text **only**. MIME and final extension must agree: TXT/LOG/INI/CONF use `text/plain`; MD accepts `text/markdown` or `text/plain`; CSV accepts `text/csv` or `text/plain`; JSON uses `application/json` or `text/json`; XML uses `application/xml` or `text/xml`; YAML/YML accept `application/yaml`, `application/x-yaml`, `text/yaml`, or `text/x-yaml`. No octet-stream fallback, Office or archive support.

Lightweight text is additionally limited to **1 MiB per file and 1 MiB total TEXT bytes per v5 turn**; image/PDF bytes do not consume that separate text budget. The existing 3-file and 24 MiB v5 aggregate transport caps remain unchanged. Worker rejects invalid UTF-8, NUL/control bytes, and common binary signatures before any OpenAI request. Accepted text uses ordered Responses `input_text` with the sanitized filename and an untrusted-user-data prefix; **never** image or PDF `input_file`. Image/PDF and legacy v4 multipart remain unchanged. Continuation remains JSON only. Android Phase A2 adds document picker selection, bounded MIME normalization, private-file import and TEXT history cards. The Worker continues enforcing the strict Phase A1 wire allowlist; Room schema and JSON-only continuations are unchanged.

## Advisor structured observability

Protected Advisor requests emit one privacy-safe structured `advisor_protocol` event to Cloudflare logs. START and MESSAGE create a new opaque user-turn trace and return it in `X-Taksula-Trace-Id`; CONTINUE reuses a syntactically valid inbound trace header so one tool-assisted user turn can be correlated across multiple HTTP requests. Missing or invalid CONTINUE trace headers fail soft to a new trace. An internal requestId identifies the individual Worker request and is not returned to Android.

The event records observable operational metadata only: endpoint stage, protocol version, parsed provider/branch, generic input kind (text/image/pdf/tool_result), answer vs tool-request envelope, bounded local query/limit counts, final product-card count, web-search/source counts, normalized AgentUsage/cost when present, total request latency, safe HTTP/outcome/failure categories, and aggregate CONTINUE result counts/rejection category. Cloudflare structured logs are the current inspection surface; no persistent analytics store is used.

The event never contains chain-of-thought, user/model text, local or web query text, product names/descriptions/IDs/OBIKs/article numbers, stock or price values, attachment filenames/bytes/content, web source URLs/titles, raw upstream bodies, responseId/callId/previousResponseId, Authorization material, app/OpenAI secrets, or other raw payload content. Observability is fail-soft and does not change Advisor JSON bodies. Current Android propagates the UUID trace through CONTINUE and captures it in the dedicated `ADVISOR_FAILURE` report; an older assistant-message report is not evidence for a later failed turn.

### Incomplete-output diagnosis and correlation

OpenAI HTTP 200 is transport success, not proof of `status=completed`. An incomplete result with `incomplete_details.reason=max_output_tokens` maps to `upstream_incomplete_output_budget`; another incomplete reason maps to `upstream_incomplete_other`. Android receives only `502 {"error":"upstream_failure"}` and `X-Taksula-Trace-Id`. Raising the continuation ceiling never promotes incomplete output or invalid final/tool JSON. No retry is added.

The October 13 record for trace `3d3e40fa-5b3b-4abf-bbcd-f587c3dcf49e` / Worker version `cd218e15-8264-4f4f-a32c-ff2d14e81314` confirms KWANT/205 v3 CONTINUE output-budget exhaustion after 3 groups / 5 products (OpenAI 200, Worker 502, 6070 ms; investigated cap 384). This production evidence was supplied by the incident investigator, not independently retrieved during implementation. **The October 12 pump root cause remains UNKNOWN.** A Worker version ID is not a git SHA; match it to the deployment/build history separately.

Safe operational procedure (read-only; no implicit permission to generate traffic or change logging):

1. Obtain the failed turn's UUID trace, stage and timestamp from its dedicated Android failure report, before retry/process death discards transient evidence. Do not substitute a prior successful photo/assistant trace.
2. In authorized Cloudflare retained logs for the correct Worker/environment, filter event `advisor_protocol`, exact trace and relevant time window. START/MESSAGE create fresh traces; CONTINUE propagates one. Use platform version/deployment metadata to identify the serving code; source main or CI success alone cannot establish deployment.
3. Export only operational fields: trace/request IDs, stage, provider/branch/protocol, outcome/HTTP status, bounded validation/upstream category/status, duration, local-result/query/product counts, and bounded model/requestType/token usage. Never export prompts, raw error/response bodies, products, queries, attachments, auth or entire platform invocation payloads.
4. For incomplete, inspect `inputTokens`, `cachedInputTokens`, `cacheWriteTokens`, `outputTokens`, `reasoningTokens`, `totalTokens` when non-null. Reasoning is included in output; do not add it twice. Null is unknown, not zero. Failure `responseEnvelopeType=null`, default false/zero tool/web counters do not prove that no tool work occurred. The selected cap follows the deployed code's policy and validated result counts; this patch adds no budget field or observability schema change.
5. Preserve original category/status/trace when usage is absent or invalid. A default web counter of zero is not proof of zero incomplete web billing. Logs may be disabled, sampled, unavailable or expired; real-time tail cannot recover history. If exact correlation is missing/ambiguous, report UNKNOWN. Enabling logging or deployment requires separate authorization and cannot recover old logs.
