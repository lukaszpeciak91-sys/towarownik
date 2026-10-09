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

The Worker uses native `fetch` against the Responses API. It declares the existing strict `find_obi_products` application function plus the current built-in `{ "type": "web_search" }`, with `tool_choice: "auto"` and `max_tool_calls: 1` for built-in tools. Web search is selective rather than forced. File search, computer use, hosted shell, image generation, MCP, deep research, background mode, and streaming remain disabled.

Exactly one application-defined function is declared:

```text
find_obi_products(storeNumber, queries[])
```

`storeNumber` is one explicit three-digit string shared by the whole batch. `queries` contains 1–5 `{query, limit}` entries, every limit is at least 1, and the sum of all limits is at most 5. The Worker validates shape and model-produced arguments and returns the tool request to Android; Android owns supported-store authorization and executes OBI lookup.

## Usage and pricing telemetry

For every successful Responses API result, the Worker independently attempts to validate OpenAI `usage`. START, MESSAGE, and CONTINUE are explicit request types. Missing or malformed usage is dropped while the normalized answer/tool request remains valid.

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

Allowed MIME/signatures: `image/jpeg`, `image/png`, `application/pdf`. Each file must be 1 byte–16 MiB; the raw attachment sizes must total at most **24 MiB** across up to three files, and the declared multipart Content-Length must be at most **24 MiB + 16 KiB**, including framing. Missing/invalid/oversized declared length is rejected before formData parsing; all count, signature and MIME checks occur before any upstream forwarding. The Worker emits a single Responses USER input with optional `input_text` first, followed by all files in original order (`input_image`, `detail=high` for images; `input_file` for PDFs). No persistent proxy file storage or new formats are introduced. `/continue` remains JSON-only with provider-aware `find_products` and matching v5 version throughout a tool-assisted turn.

Android `AdvisorProxyClient` accepts the explicit `attachments: List<AdvisorAttachment>` overload for v5; the existing nullable `attachment` overload and UI remain v4. No multi-picker, Room/attachment ownership, or rendered history change is included.


## Advisor structured observability

Protected Advisor requests emit one privacy-safe structured `advisor_protocol` event to Cloudflare logs. START and MESSAGE create a new opaque user-turn trace and return it in `X-Taksula-Trace-Id`; CONTINUE reuses a syntactically valid inbound trace header so one tool-assisted user turn can be correlated across multiple HTTP requests. Missing or invalid CONTINUE trace headers fail soft to a new trace. An internal requestId identifies the individual Worker request and is not returned to Android.

The event records observable operational metadata only: endpoint stage, protocol version, parsed provider/branch, generic input kind (text/image/pdf/tool_result), answer vs tool-request envelope, bounded local query/limit counts, final product-card count, web-search/source counts, normalized AgentUsage/cost when present, total request latency, safe HTTP/outcome/failure categories, and aggregate CONTINUE result counts/rejection category. Cloudflare structured logs are the current inspection surface; no persistent analytics store is used.

The event never contains chain-of-thought, user/model text, local or web query text, product names/descriptions/IDs/OBIKs/article numbers, stock or price values, attachment filenames/bytes/content, web source URLs/titles, raw upstream bodies, responseId/callId/previousResponseId, Authorization material, app/OpenAI secrets, or other raw payload content. Observability is fail-soft and does not change Advisor JSON bodies or production behavior. Android propagation/reporting of the trace header is deferred to B2.2.
