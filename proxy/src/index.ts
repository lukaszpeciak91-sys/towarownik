import { authorizeApp } from "./auth.js";
import { continueAgent, startAgent, UpstreamFailureError } from "./openai.js";
import type { Env, UpstreamFetch } from "./types.js";
import {
  InvalidRequestError,
  parseContinueRequest,
  parseStartRequest,
  RequestTooLargeError,
} from "./validation.js";

const JSON_HEADERS = {
  "Content-Type": "application/json",
} as const;

function jsonResponse(
  body: unknown,
  status: number,
  extraHeaders?: HeadersInit,
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...JSON_HEADERS,
      ...extraHeaders,
    },
  });
}

function handleHealth(request: Request): Response {
  if (request.method !== "GET") {
    return jsonResponse(
      { error: "method_not_allowed" },
      405,
      { Allow: "GET" },
    );
  }

  return jsonResponse(
    {
      ok: true,
      service: "towarownik-proxy",
    },
    200,
  );
}

async function handleProtectedAgentRequest(
  request: Request,
  env: Env,
  upstreamFetch: UpstreamFetch,
  endpoint: "start" | "continue",
): Promise<Response> {
  const auth = authorizeApp(request, env);
  if (!auth.ok) {
    return jsonResponse({ error: auth.error }, auth.status);
  }

  if (request.method !== "POST") {
    return jsonResponse(
      { error: "method_not_allowed" },
      405,
      { Allow: "POST" },
    );
  }

  const apiKey = env.OPENAI_API_KEY;
  if (!apiKey) {
    return jsonResponse(
      { error: "server_not_configured" },
      503,
    );
  }

  try {
    const result = endpoint === "start"
      ? await startAgent(
          await parseStartRequest(request),
          apiKey,
          upstreamFetch,
        )
      : await (async () => {
          const input = await parseContinueRequest(request);
          return continueAgent(
            input.responseId,
            input.callId,
            input.result,
            apiKey,
            upstreamFetch,
          );
        })();

    return jsonResponse(result, 200);
  } catch (error) {
    if (error instanceof RequestTooLargeError) {
      return jsonResponse({ error: "request_too_large" }, 413);
    }
    if (error instanceof InvalidRequestError) {
      return jsonResponse({ error: "invalid_request" }, 400);
    }
    if (error instanceof UpstreamFailureError) {
      return jsonResponse({ error: "upstream_failure" }, 502);
    }
    return jsonResponse({ error: "upstream_failure" }, 502);
  }
}

export async function handleRequest(
  request: Request,
  env: Env,
  upstreamFetch: UpstreamFetch = fetch,
): Promise<Response> {
  const url = new URL(request.url);

  if (url.pathname === "/health") {
    return handleHealth(request);
  }

  if (url.pathname === "/v1/agent/start") {
    return handleProtectedAgentRequest(request, env, upstreamFetch, "start");
  }

  if (url.pathname === "/v1/agent/continue") {
    return handleProtectedAgentRequest(request, env, upstreamFetch, "continue");
  }

  return jsonResponse(
    { error: "not_found" },
    404,
  );
}

export function createWorker(upstreamFetch: UpstreamFetch = fetch) {
  return {
    fetch(request: Request, env: Env): Promise<Response> {
      return handleRequest(request, env, upstreamFetch);
    },
  };
}

export default createWorker();
