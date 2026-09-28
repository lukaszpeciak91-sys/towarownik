# Progress

## Current phase

**Selective web search for Taksula v0.1**

PR #17 established the production-shaped chat shell and bounded human manual OBI browsing. PR #18 added real local conversation state, multi-turn continuation, retention, and deletion. PR #19 added persistent app-owned verified product cards. Localization, Warm Modular Utility, Settings, PL/EN selection, diagnostics relocation, user-controlled reporting, the Taksula public rename, multi-store support, usage/cost measurement, the GPT-6 Luna swap, richer verified OBI product facts, and final Taksula advisor behavior are complete. The current iteration adds one selective bounded Responses web-search capability with persisted citations while preserving the OBI/store/productRef trust boundary.

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
- final model answers use strict structured `{text, productRefs:[{storeNumber,obik}]}` output with at most five selected verified references;
- exact `LocalProduct` lookups retain local-only snapshots containing trusted URL and verification time while OpenAI still receives only OBIK/name/stock/price;
- product selection resolves only against verified snapshots from the current USER turn, keyed by `(storeNumber, obik)` so the same OBIK in different stores remains distinct;
- Room schema v3 persists each conversation store and each message-product store; v2→v3 deterministically assigns historical rows to `075`;
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
- existing sanitized OBI diagnostics are offered only when already enabled and populated, through a separate checkbox that defaults OFF; reporting never starts diagnostics/probes/network work;
- reports are temporary UTF-8 TXT files under `cacheDir/reports/` and are not stored in Room;
- a non-exported FileProvider exposes only the report cache path and Android ACTION_SEND opens the system chooser with the centralized Nepahu Studio recipient;
- Taksula never sends the report automatically; chooser launch failure is handled with a bounded UI error and deletion of the fresh TXT; screenshots are added manually in the chosen mail/share client;
- report contents exclude OpenAI/tool IDs, response-chain IDs, secrets/auth headers, cookie values, raw model data, OBI HTML/Nuxt, account/network/location identifiers.
- public-facing product branding is **Taksula** in Android resources, diagnostics/report labels, TXT headings, and current product documentation;
- package/application ID `pl.lukaszpeciak.towarownik`, repository name, Worker/service/token identifiers, database/persisted contracts, internal theme symbols, and launcher asset filenames remain intentionally unchanged;
- launcher icon artwork and Warm Modular Utility styling are unchanged; no binary asset is modified.
- one canonical Android allowlist contains confirmed OBI Poland store numbers; new conversations default to `075`;
- the advisor top bar exposes a compact conversation store selector; unsaved selection remains transient until first send;
- direct OBIK and selected EAN/text candidates exact-verify against the active selected store while search discovery remains store-independent;
- the single advisor OBI tool is now `find_obi_products(query, storeNumber, limit)`;
- Android authorizes alternate tool stores only when a supported exact three-digit token occurs literally in the current USER message, with exact digit boundaries;
- START/MESSAGE/CONTINUE carry the immutable turn-store context to the proxy, while the full allowlist remains Android-local;
- unsupported/unauthorized store tool requests fail closed before OBI and never substitute `075`;
- verified cards/history/reports preserve their own snapshot store and changing the conversation selector never rewrites historical facts;
- store `075` remains the deterministic regression/live-probe baseline.

No local transcript is replayed as a hidden fallback if an old OpenAI response chain cannot continue. No compaction/summarization is added. `previous_response_id` reduces application-level transcript replay but prior context tokens remain billable input.

Android test version: **0.1.10 (11)**.

- every successful OpenAI START/MESSAGE/CONTINUE response can carry bounded usage metadata without making telemetry a correctness dependency;
- GPT-5.6 Luna remains historical baseline data, while production now uses GPT-6 Luna with versioned pricing and the same usage accounting boundaries;
- local cumulative requests, USER turns, tool-assisted turns, tokens, model-grouped totals, known USD cost, and unpriced-request count are persisted without conversation content;
- Settings exposes AI Usage with adaptive sub-cent cost formatting;
- NBP USD/PLN is fetched only from the usage UI, cached for 24 hours, and fails soft to a dated stale rate or unavailable PLN;
- a user-configured local Taksula remaining budget uses the current cumulative cost as its baseline and is explicitly not OpenAI balance/credit data;
- the below-USD-1 budget warning is one-shot per threshold crossing and re-arms after budget reset/increase.

- exact verified OBI lookups now carry optional model-context brand, short description, and bounded technical facts from the same decoded product payload;
- live proof used OBIK 3496072 (adhesive), 6743009 (drill), and 7156243 (LED fitting), confirming productDescription/productOverview/technicalData shapes across categories;
- optional rich sections fail soft while OBIK/store identity, stock, and price rules remain unchanged;
- advisor tool payload remains bounded to brand 80, description 220, six facts, fact label 60, fact value 100;
- message-product snapshots/cards/reports remain unchanged; rich facts are transient model context only;
- raw OBI HTML/Nuxt, URLs, EAN, cookies, diagnostics, and verification timestamps remain outside OpenAI tool results.

- final Taksula instructions define a practical home-improvement retail product/technical advisor rather than a generic assistant;
- ordinary general technical knowledge is allowed without forcing an OBI lookup;
- specific OBI SKU/current-store claims remain under the verified tool trust hierarchy and missing SKU-specific facts are not invented;
- current stock/price still require fresh verification when relevant and historical values remain non-authoritative;
- richer product facts are used selectively rather than dumped;
- clearly unrelated general chat is briefly redirected while borderline practical home-improvement questions remain in scope;
- conversation language is followed naturally where practical without changing Android locale/persistence behavior;
- GPT-6 Luna, low reasoning, tool/schema/productRefs boundaries, tool/product limits, multi-store rules, pricing, UI, persistence, and parser remain unchanged;
- selective Responses web_search is enabled with automatic choice and max one built-in call per response; ordinary technical questions should not browse reflexively.

- current Responses `web_search` is available selectively alongside `find_obi_products`, with `tool_choice=auto` and `max_tool_calls=1` for built-ins;
- OBI remains authoritative for current stock/price/store availability and only Android-verified current-turn snapshots may enter productRefs/cards;
- final answers preserve at most six normalized HTTPS sources from actual OpenAI url_citation annotations; raw web output/query metadata never reaches Android;
- Room schema v4 persists message sources with cascade so reopened web-derived answers retain clickable citations offline;
- searched pages are explicitly untrusted reference data and cannot alter role/tool/trust/privacy rules;
- AI Usage now counts completed web searches and current pricing adds USD 0.01 per search action on top of model token cost;
- web availability alone does not count as a call and existing unpriced/known-minimum budget semantics remain unchanged;
- no deep research/background/streaming/file search/image search/RAG/MCP/general autonomous agent behavior is added.

## Next implementation milestone

The currently planned AI capability stage is complete after this selective web-search iteration. Context compaction and privacy-policy content remain separate future work.

## Not started

- Context summarization/compaction
- Explicit continue-as-new-context fallback
- Strong per-device/user identity
- General chat
- Additional agent tools
- Streaming
- Server-side OBI implementation
- Camera barcode scanning
- Nearby-store fallback behavior
- Product images
- Final Play release polish
