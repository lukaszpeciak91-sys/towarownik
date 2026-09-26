# Progress

## Current phase

**Chat-style shell + manual OBI search v0.2**

PR #16 completed the first Android advisor end-to-end integration. This iteration replaces the temporary WYSZUKIWARKA/DORADCA validation selector with the first production-shaped Towarownik shell.

The default surface is now DORADCA in a chat-style layout:

- hamburger opens a modal left drawer;
- centered Towarownik title retains the long-press diagnostics entry;
- “+” starts a fresh case;
- the top-right search action opens the independent full-screen Wyszukiwarka OBI;
- submitted user text and the normalized one-shot advisor reply render as timestamped chat messages;
- progress and errors appear in the conversation surface;
- no persistent history or fake conversations are created.

The drawer contains “Nowa rozmowa”, a local “Przeszukaj rozmowy...” field, and an honest empty history area ready for the later persistence iteration without committing to a database now.

Direct manual OBI search remains local and independent from proxy/OpenAI. OBIK and EAN verification behavior is preserved. Text search now has a separate bounded human-browsing capacity: the parser may expose at most **25** recognized candidate links from the current OBI HTML while preserving OBI's reported total count. The UI initially shows five candidates and reveals additional parsed candidates in chunks of five. No OBI pagination HTTP contract is added, and “Pokaż więcej” disappears when the locally parsed candidate list is exhausted even if OBI reports a larger total.

The advisor/local tool remains capped at **5** products. Candidate selection still runs the existing exact store-`075` lookup. Exact product presentation now includes name, OBIK, local gross price, local stock, and “Otwórz w OBI” using the canonical/trusted `LocalProduct.productUrl`.

Advisor draft/messages, manual-search query/completed result state, and selected top-level surface are preserved across rotation where practical using Compose saved state. Phone landscape keeps bounded content widths and the conversation drawer remains modal.

## Next implementation milestone

**Persistent conversation history and real multi-turn context**

The next dedicated iteration may connect the prepared drawer/message model to persisted conversations, conversation search, and controlled multi-turn OpenAI continuation. That work should define retention/resume rules explicitly rather than being hidden inside this shell PR.

The final advisor persona/prompt remains a separate product iteration.

## Not started

- Persistent conversation history
- Room/database
- Today/yesterday grouping
- Conversation search index/backend
- Real multi-turn user conversation
- Resume after app restart
- Context summarization
- Final "Justyna" advisor persona/prompt
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
