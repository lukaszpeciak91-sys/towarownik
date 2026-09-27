# Progress

## Current phase

**Problem reporting + assistant response reports v0.1**

PR #17 established the production-shaped chat shell and bounded human manual OBI browsing. PR #18 added real local conversation state, multi-turn continuation, retention, and deletion. PR #19 added persistent app-owned verified product cards without moving OBI authority into the model or proxy. The localization, Warm Modular Utility, Settings, PL/EN selection, and diagnostics relocation are complete. The current iteration adds the first user-controlled problem-reporting workflow without adding a backend or changing advisor, OBI, Room schema, or product behavior.

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
- the existing `VerifiedProductCard` boundary is shared by manual search and advisor history;
- current Android UI text is moved to the Polish/default `values/strings.xml` resource set;
- a complete matching English `values-en/strings.xml` resource set is provided;
- Compose resolves user-visible chrome through Android string resources and formatted placeholders;
- advisor/search errors use stable language-neutral enum state and are translated only in UI;
- saved manual-search errors no longer persist rendered language-dependent sentences;
- Android UI uses the complete PL/EN resource sets and Settings controls the supported per-app locale through AppCompat;
- production UI uses the approved warm dark palette and centralized semantic Compose colors;
- automatic Material light/dark switching and dynamic-color-style defaults are replaced by the approved dark-first scheme;
- top bars, conversation drawer, chat bubbles, persistent composer, progress/error states, verified product card, and manual OBI search share one visual hierarchy;
- strong amber is reserved for primary/verified/active emphasis rather than ordinary buttons and cards;
- character/emoji action controls are replaced by local vector drawable icons with accessible descriptions;
- no launcher PNG or other binary asset is created or modified by the visual-system work;
- Settings is pinned to the bottom of the conversation drawer independently of history length/search state;
- Settings exposes exactly Polski / English through AndroidX AppCompat per-app locales;
- AppCompat auto-stores the selected locale below API 33, while Android 13+ uses the platform per-app locale path with declared `pl` and `en` support;
- switching UI locale recreates the normal Android UI but does not send locale to the proxy/model or translate persisted conversation text;
- diagnostics is reached through Settings and the old hidden title long-press entry is removed;
- Diagnostics returns to Settings, while Settings returns to Advisor;
- Report problem and Privacy policy are visible disabled future rows with no fake destination/content;
- About uses `app_name` plus the current BuildConfig version, keeping later naming work cheap;
- each persisted ASSISTANT response has a quiet contextual Report/Zgłoś action backed by its persisted message ID;
- Settings Report problem is now active while Privacy policy remains future work;
- assistant/general report categories use stable technical enums with localized labels;
- transcript inclusion defaults OFF and is always user-controlled;
- assistant reports always include the exact persisted response and its persisted product snapshots, while optional context stops at that response;
- general reports require a description and may include the current persisted conversation only after opt-in;
- unsent drafts are excluded from report evidence;
- existing sanitized OBI diagnostics may be appended only when already enabled and populated; reporting never starts diagnostics/probes/network work;
- reports are temporary UTF-8 TXT files under `cacheDir/reports/` and are not stored in Room;
- a non-exported FileProvider exposes only the report cache path and Android ACTION_SEND opens the system chooser with the centralized Nepahu Studio recipient;
- Towarownik never sends the report automatically; screenshots are added manually in the chosen mail/share client;
- report contents exclude OpenAI/tool IDs, response-chain IDs, secrets/auth headers, cookie values, raw model data, OBI HTML/Nuxt, account/network/location identifiers.

No local transcript is replayed as a hidden fallback if an old OpenAI response chain cannot continue. No compaction/summarization is added. `previous_response_id` reduces application-level transcript replay but prior context tokens remain billable input.

Android test version: **0.1.10 (11)**.

## Next implementation milestone

Evaluate reporting and Settings on-device, then continue with the next measured advisor/product iteration. Privacy-policy content/URL remains intentionally unimplemented until a real policy destination exists.

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
