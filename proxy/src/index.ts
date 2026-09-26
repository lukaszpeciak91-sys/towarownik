interface Env {
  OPENAI_API_KEY?: string;
  TOWAROWNIK_APP_TOKEN?: string;
}

const JSON_HEADERS = {
  "Content-Type": "application/json",
} as const;

function jsonResponse(body: unknown, status: number, extraHeaders?: HeadersInit): Response {
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

export function handleRequest(request: Request, _env: Env): Response {
  const url = new URL(request.url);

  if (url.pathname === "/health") {
    return handleHealth(request);
  }

  return jsonResponse(
    { error: "not_found" },
    404,
  );
}

export default {
  fetch(request: Request, env: Env): Response {
    return handleRequest(request, env);
  },
};
