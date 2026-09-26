import type { Env } from "./types";

export type AuthResult =
  | { ok: true }
  | { ok: false; status: 401 | 503; error: "unauthorized" | "server_not_configured" };

export function authorizeApp(request: Request, env: Env): AuthResult {
  const expected = env.TOWAROWNIK_APP_TOKEN;
  if (!expected) {
    return {
      ok: false,
      status: 503,
      error: "server_not_configured",
    };
  }

  const authorization = request.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) {
    return {
      ok: false,
      status: 401,
      error: "unauthorized",
    };
  }

  const token = authorization.slice("Bearer ".length);
  if (!token || token !== expected) {
    return {
      ok: false,
      status: 401,
      error: "unauthorized",
    };
  }

  return { ok: true };
}
