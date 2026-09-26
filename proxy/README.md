# Towarownik proxy

This directory contains the minimal Cloudflare Worker foundation for Towarownik's future AI assistant.

## Current behavior

Only one public endpoint exists:

```text
GET /health
```

It returns a stable service-health JSON response. Unknown routes return JSON `404`; unsupported methods on `/health` return `405`.

There are intentionally no AI endpoints, OpenAI calls, OBI requests, tool-calling flows, persistence, authentication middleware, or analytics in this foundation.

## Local checks

Requires Node.js 22.

```bash
cd proxy
npm ci
npm run typecheck
npm test
```

Or run both validation steps:

```bash
npm run check
```

Tests use no network, require no Cloudflare account, and require no secrets.

## Cloudflare setup later

For Cloudflare GitHub repository integration, use:

```text
Root directory: proxy
Worker name: towarownik-proxy
```

`wrangler.jsonc` contains the deployable Worker entry point and requires no paid Cloudflare service bindings.

Future server-side secrets are reserved as:

- `OPENAI_API_KEY` — future OpenAI credential;
- `TOWAROWNIK_APP_TOKEN` — future app-to-proxy authentication credential.

Do not put either value in source control. The health endpoint does not require them.

## OBI boundary

This Worker does not scrape OBI and contains no OBI transport or parser code. OBI discovery, OBIK extraction, exact store-`075` lookup, local stock, and local price remain authoritative inside the existing Android implementation.

A future AI tool request such as `find_available_obi_075(query, limit)` will be executed by the Android app using its existing OBI repositories. Only compact structured results should be returned to the AI path; OBI HTML and Nuxt payloads must not be sent through this proxy.
