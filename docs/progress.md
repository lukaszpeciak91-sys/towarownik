# Progress

## Current phase

**Authenticated OpenAI proxy v0.1**

PR #13 completed the OBI text-search false-empty repair. PR #14 established the isolated Cloudflare Worker boundary while keeping Android authoritative for OBI search, exact store-`075` lookup, local stock, and local price.

The Worker now implements the smallest authenticated assistant contract:

- public `GET /health`;
- authenticated `POST /v1/agent/start`;
- authenticated `POST /v1/agent/continue`;
- server-only `OPENAI_API_KEY`;
- shared Internal-Testing `TOWAROWNIK_APP_TOKEN`;
- native Responses API transport using centralized `gpt-5.6-luna`, low reasoning effort, and bounded output;
- exactly one strict local-tool declaration: `find_available_obi_075(query, limit)`;
- normalized `answer` / `tool_request` responses;
- validated compact continuation results using `previous_response_id` and `function_call_output`.

The Worker still contains no OBI HTTP/parser implementation and stores no conversation state in Cloudflare storage. If the model needs OBI facts, Android will execute the existing local repositories and return only compact verified product data. OBI HTML/Nuxt never enters the proxy/model path.

Deterministic tests inject a fake OpenAI transport, so CI uses no real API key, no OpenAI network, and no Cloudflare account. No paid OpenAI built-in tool is enabled and no application retry is added.

## Next implementation milestone

**Android assistant integration**

After the authenticated Worker contract is deployed and validated with a real Worker secret configuration, add the smallest Android conversation controller/UI that can call `/start`, execute a bounded local `find_available_obi_075` request through the existing OBI mechanisms, and send the compact verified result to `/continue`.

The final assistant personality/prompt should be evaluated separately rather than expanded inside this infrastructure milestone.

## Not started

- Android chat UI and interaction controller
- Persistent conversation history
- Strong per-device/user identity
- Final assistant persona
- OpenAI built-in tools
- Streaming
- Cloudflare KV/D1/Durable Objects
- Server-side OBI implementation
- Camera barcode scanning
- Local and nearby-store fallback behavior
- External product-link behavior
- Persistence/history/favorites
- Product images
- Final Play release polish
