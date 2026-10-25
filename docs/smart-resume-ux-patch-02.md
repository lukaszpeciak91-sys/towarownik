# UX Patch 02 — Smart Resume

On a foreground return from `MainActivity.onStop` after at least 60 elapsed minutes in the same Activity instance, the app navigates to the existing empty Advisor new-conversation surface **only when** there is no unsent draft, pending attachment/import, running Advisor turn or draft persistence job. It does not delete existing conversations or insert an empty Room row. Explicit history navigation remains unchanged.

Duration uses `SystemClock.elapsedRealtime()`; the timestamp is held only in the Activity instance. A recreated Activity and a fresh process do not inherit the old timeout. Exactly one return event is consumed; shorter foreground returns retain navigation state. The existing `newAdvisorCase` resets transient UI state and selects the current WorkingProfile. No persistent timeout, background process, Worker request, protocol or Room migration is introduced.

Focused unit tests exercise the pure threshold/guard predicate. Android lifecycle behavior, picker cancellation and process-death recovery require manual on-device validation. On a fresh process without saved Activity state, the previous automatic opening of the most recent conversation is intentionally skipped; persisted history remains accessible from the drawer. Saved Activity state still restores recoverable conversation/draft work.

## Picker return guard (PR #137 audit fix)

Launching any of the existing photo/document/replacement/camera contracts marks the next foreground return as picker-owned. The return event consumes the marker and skips Smart Resume even when the picker is cancelled, or before its callback imports attachments. Later ordinary returns are eligible again. The marker is in-memory and scoped to the Compose Activity instance. Deterministic predicate tests cover successful/cancelled picker returns; real system-UI callback ordering remains a device-test limitation.
