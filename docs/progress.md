# Progress

## Current phase

**AI assistant / proxy foundation**

PR #13 completed the OBI text-search false-empty repair. Positive OBI search pages now require a positive `Wyniki dla … (N)` count before recognized product links are accepted, while true empty pages with recommendation links remain protected from false positive results.

The existing Android OBI path is now considered the authoritative local product-data capability for the next phase: search discovery, OBIK extraction, exact store-`075` lookup, local stock, and local price stay in Android.

The new phase introduces the smallest production-shaped Cloudflare Worker foundation under `proxy/` for a future Towarownik AI assistant. The Worker currently exposes only `GET /health` and has deterministic TypeScript/tests/CI. It makes no OpenAI request, has no agent endpoints, does not consume API credits, and contains no OBI transport or parser code.

The intended future split is:

```text
Android local OBI lookup ── authoritative product/stock/price

Android AI interaction
        ↓
Cloudflare Worker proxy
        ↓
OpenAI
```

If the future model requests an OBI lookup, Android will execute the high-level local tool using the existing repositories and return only compact structured data. OBI HTML/Nuxt payloads stay out of the proxy/OpenAI path.

## Next implementation milestone

**Define the authenticated AI conversation contract**

After the proxy foundation is deployed and its health endpoint is verified, define the smallest authenticated agent request/response contract before adding any OpenAI call, prompt, model selection, tool calling, or Android chat UI.

## Not started

- OpenAI API integration
- OpenAI model selection
- Agent prompts
- Agent start/continue endpoints
- Conversation history
- Tool/function calling
- Android chat UI
- App-to-proxy authentication implementation
- Camera barcode scanning
- Local and nearby-store fallback behavior
- External product-link behavior
- Persistence/history/favorites
- Product images
- Final Play release polish
