# Towarownik proxy

This directory contains the Cloudflare Worker boundary for Towarownik's AI assistant.

## Endpoints

### Public health

```text
GET /health
```

Returns:

```json
{"ok":true,"service":"towarownik-proxy"}
```

Health does not require either Worker secret.

### Authenticated AI start

```text
POST /v1/agent/start
Authorization: Bearer <app token>
Content-Type: application/json
```

Request:

```json
{"message":"..."}
```

The message is trimmed/whitespace-normalized and bounded. Model, instructions, tools, reasoning effort, output budget, and OpenAI URL are server-controlled.

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
    "name":"find_available_obi_075",
    "callId":"...",
    "arguments":{"query":"...","limit":5}
  }
}
```

Raw OpenAI responses, reasoning items, usage metadata, internal instructions, and upstream error bodies are never forwarded to Android.

## OpenAI contract

The current cost-sensitive validation model is `gpt-6-luna` with low reasoning effort and a bounded output budget. Model choice is centralized and may be revisited after real evaluations.

The Worker uses native `fetch` against the Responses API. It enables no OpenAI built-in tools: no web search, file search, computer use, hosted shell, image generation, MCP, or other paid built-in tool.

Exactly one application-defined function is declared:

```text
find_available_obi_075(query, limit)
```

`limit` is at most 5. The Worker validates model-produced arguments and returns the tool request to Android; it does not execute OBI lookup itself.

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
- exact store-`075` lookup;
- local stock;
- local price.

When OpenAI requests `find_available_obi_075`, Android uses its existing OBI repositories and returns only a compact verified result containing the query and up to five products. OBI HTML, Nuxt payloads, cookies, and parser internals are never accepted as the tool result or forwarded to OpenAI.

The Worker stores no conversation state in Cloudflare storage. Continuation uses the OpenAI response identifier supplied by the previous normalized result.

## Limits and failure mapping

The start request body is bounded to 4 KiB and the normalized message to 2,000 characters. The continue request body is bounded to 16 KiB, product count to 5, tool query/product-name strings to 200 characters, and OBIK to exactly seven digits.

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
