# Progress

## Current phase

**Advisor verified product cards v0.4**

PR #17 established the production-shaped chat shell and bounded human manual OBI browsing. PR #18 added real local conversation state, multi-turn continuation, retention, and deletion. The current v0.4 iteration adds persistent app-owned verified product cards without moving OBI authority into the model or proxy.

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
- current OBI facts continue to be refreshed through the existing Android local tool;
- conversations are treated as individual customer cases, with “Nowa rozmowa” recommended for a new customer/problem;
- startup-only local retention removes conversations strictly older than 30 days by `updatedAt`;
- the exact 30-day cutoff remains retained and message rows cascade on deletion;
- each history row can be deleted manually after explicit confirmation;
- deleting the active conversation cancels its request, invalidates stale callbacks, and opens a fresh empty chat;
- final model answers use strict structured `{text, productObiks}` output with at most five selected OBIKs;
- exact `LocalProduct` lookups retain local-only snapshots containing trusted URL and verification time while OpenAI still receives only OBIK/name/stock/price;
- product selection resolves only against verified snapshots from the current USER turn, with latest lookup winning per OBIK;
- Room schema v2 persists ordered snapshots per ASSISTANT message through an explicit v1→v2 migration;
- ASSISTANT text, selected snapshots, and final response ID commit atomically;
- historical cards reopen without network access and display their verification timestamp;
- the existing `VerifiedProductCard` boundary is shared by manual search and advisor history.

No local transcript is replayed as a hidden fallback if an old OpenAI response chain cannot continue. No compaction/summarization is added. `previous_response_id` reduces application-level transcript replay but prior context tokens remain billable input.

Android test version: **0.1.7 (8)**.

## Next implementation milestone

Evaluate the verified-card advisor flow on-device and choose the next measured advisor iteration without broadening the OBI trust boundary.

The final advisor persona/instructions and any context compaction should remain separate measured iterations.

## Not started

- Final advisor persona/prompt
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
