# Architecture

## Goal

Towarownik should remain a small native Android application. Its intended data flow is:

```text
UI / Compose
    ↓
Repository / domain layer
    ↓
HTTP/session transport
    ↓
OBI-specific payload parser
```

Only the UI bootstrap exists today. The remaining layers are an architectural direction, not yet-created interfaces or implementations.

## Boundaries

- **UI / Compose** renders state and reports user intent. It must not issue HTTP requests or understand OBI payload details.
- **Repository / domain layer** will coordinate search rules and expose application-oriented results without leaking transport or page-format details into the UI.
- **HTTP/session transport** will retrieve public data and report transport outcomes. It must not interpret OBI payloads.
- **OBI-specific payload parser** will translate retrieved payloads into validated data. Parsing failures must be explicit; missing or malformed values must never be invented.

Transport and parsing must remain isolated from the UI. An OBI website change should require changes in its transport/parser boundary and tests, not a UI rewrite.

## UI and configuration

The application uses a single Compose activity and the normal Android resource system. No orientation is locked, so the UI must continue to adapt cleanly to portrait and landscape sizes. Navigation, dependency injection, persistence, and other frameworks should be added only if a concrete feature requires them.
