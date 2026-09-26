# Progress

## Current phase

**Persistent advisor conversations + multi-turn v0.3**

PR #17 established the production-shaped chat shell and bounded human manual OBI browsing. This iteration connects that shell to real local conversation state and the Responses continuation chain.

Implemented direction:

- Room schema v1 for conversations/messages;
- real drawer history sorted newest-updated first;
- fully local phrase search across title plus USER/ASSISTANT text;
- first USER send creates the conversation and local bounded title;
- completed messages, timestamps, drafts, and final response ID survive database recreation;
- recent useful conversation restores on app restart where practical;
- composer re-enables after a completed answer for real follow-up turns;
- first turn uses `/v1/agent/start`;
- follow-up turns use authenticated `/v1/agent/message` with the stored final `lastResponseId`;
- tool responses still use `/v1/agent/continue`;
- only the final answer response ID replaces the persisted conversation context;
- local tool allowance is two calls per USER turn and resets on every new USER message;
- interrupted USER-only tails recover to editable draft without automatic resend;
- switching/new conversation cancels active work and stale callbacks are rejected;
- current OBI facts continue to be refreshed through the existing Android local tool.

No local transcript is replayed as a hidden fallback if an old OpenAI response chain cannot continue. No compaction/summarization is added. `previous_response_id` reduces application-level transcript replay but prior context tokens remain billable input.

Android test version: **0.1.7 (8)**.

## Next implementation milestone

**Advisor product cards / verified product snapshots**

Keep the reusable Android `VerifiedProductCard` boundary and add structured advisor product presentation without making model-generated URLs/facts authoritative.

The final Justyna persona/instructions and any context compaction should remain separate measured iterations.

## Not started

- Final "Justyna" advisor persona/prompt
- Advisor structured product-card proxy output
- Locally persisted verified product snapshots
- Context summarization/compaction
- Explicit continue-as-new-context fallback
- Strong per-device/user identity
- General chat
- Additional agent tools
- OpenAI built-in tools
- Streaming
- Server-side OBI implementation
- Camera barcode scanning
- Nearby-store fallback behavior
- Product images
- Final Play release polish
