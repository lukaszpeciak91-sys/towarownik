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
    "arguments":{"query":"...","limit":5}
  }
}
```

Raw OpenAI responses, reasoning content/items, internal instructions, and upstream error bodies are never forwarded to Android. The only usage data forwarded is a bounded validated metadata object containing model, request type, token counts, estimated cost, and pricing version.

## OpenAI contract

The current cost-sensitive validation model is `gpt-5.6-luna` with low reasoning effort and a bounded output budget. Model choice is centralized and may be revisited after real evaluations.

The Worker uses native `fetch` against the Responses API. It enables no OpenAI built-in tools: no web search, file search, computer use, hosted shell, image generation, MCP, or other paid built-in tool.

Exactly one application-defined function is declared:

```text
find_obi_products(query, limit)
```

`storeNumber` is an explicit three-digit string and `limit` is at most 5. The Worker validates shape and model-produced arguments and returns the tool request to Android; Android owns supported-store authorization and executes OBI lookup.

## Usage and pricing telemetry

For every successful Responses API result, the Worker independently attempts to validate OpenAI `usage`. START, MESSAGE, and CONTINUE are explicit request types. Missing or malformed usage is dropped while the normalized answer/tool request remains valid.

Current pricing is server-controlled and versioned as `openai-gpt-5.6-luna-2026-09-27` for `gpt-5.6-luna`:

- uncached input: USD 0.20 / 1M tokens;
- cached input: USD 0.02 / 1M tokens;
- output: USD 1.20 / 1M tokens.

The Worker computes uncached input as `inputTokens - cachedInputTokens`. Reasoning tokens are an output-usage detail and are never charged in addition to output tokens. Pricing arithmetic is performed in integer nanodollars before the bounded numeric USD estimate is serialized.

The successful envelope may therefore include:

```json
{
  "usage": {
    "model": "gpt-5.6-luna",
    "requestType": "START",
    "inputTokens": 1000,
    "cachedInputTokens": 400,
    "outputTokens": 100,
    "reasoningTokens": 50,
    "totalTokens": 1100,
    "estimatedCostUsd": 0.000248,
    "pricingVersion": "openai-gpt-5.6-luna-2026-09-27"
  }
}
```

No prompt/message/tool content, response IDs beyond the existing operational envelope, reasoning text, secrets, or raw upstream data are included in telemetry.

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

When OpenAI requests `find_obi_products`, Android uses its existing OBI repositories and returns only a compact verified result containing the query, authorized store context, and up to five products. OBI HTML, Nuxt payloads, cookies, and parser internals are never accepted as the tool result or forwarded to OpenAI.

The Worker stores no conversation state in Cloudflare storage. Android persists only the final answer response ID for a conversation. New USER turns call `/v1/agent/message`; tool outputs inside that turn continue through `/v1/agent/continue`. Using `previous_response_id` avoids manually replaying the local transcript, but earlier chain input tokens remain billable.

## Limits and failure mapping

The start and message request bodies are bounded to 4 KiB and each normalized user message to 2,000 characters. Message continuation also bounds the previous response ID. The continue request body is bounded to 16 KiB, product count to 5, tool query/product-name strings to 200 characters, and OBIK to exactly seven digits.

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
