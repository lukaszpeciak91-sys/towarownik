import { authorizeApp } from "./auth.js";
import {
  continueAgent,
  messageAgent,
  startAgent,
  UpstreamFailureError,
} from "./openai.js";
import {
  applyAgentResult,
  applyContinuationSummary,
  emitAdvisorDiagnostic,
  emptyAdvisorDiagnostic,
  inputKindForAttachment,
  newRequestId,
  summarizeContinuationResult,
  TRACE_HEADER_NAME,
  traceIdForRequest,
  type AdvisorOutcome,
  type AgentStage,
} from "./observability.js";
import type {
  AdvisorProtocolVersion,
  AgentResult,
  Env,
  UpstreamFetch,
} from "./types.js";
import {
  InvalidRequestError,
  parseContinueRequest,
  parseMessageRequest,
  parseStartRequest,
  RequestTooLargeError,
  UnsupportedProtocolVersionError,
} from "./validation.js";

const JSON_HEADERS = {
  "Content-Type": "application/json",
} as const;

type AgentEndpoint = "start" | "message" | "continue";

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

function stageFor(endpoint: AgentEndpoint): AgentStage {
  return endpoint.toUpperCase() as AgentStage;
}

async function handleProtectedAgentRequest(
  request: Request,
  env: Env,
  upstreamFetch: UpstreamFetch,
  endpoint: AgentEndpoint,
): Promise<Response> {
  const stage = stageFor(endpoint);
  const traceId = traceIdForRequest(request, stage);
  const requestId = newRequestId();
  const startedAt = Date.now();
  const diagnostic = emptyAdvisorDiagnostic(
    traceId,
    requestId,
    stage,
  );

  const finish = (
    body: unknown,
    status: number,
    outcome: AdvisorOutcome,
    level: "info" | "warn",
    extraHeaders?: Record<string, string>,
  ): Response => {
    diagnostic.outcome = outcome;
    diagnostic.httpStatus = status;
    diagnostic.durationMs = Math.max(0, Date.now() - startedAt);
    emitAdvisorDiagnostic(diagnostic, level);
    return jsonResponse(body, status, {
      ...(extraHeaders ?? {}),
      [TRACE_HEADER_NAME]: traceId,
    });
  };

  const auth = authorizeApp(request, env);
  if (!auth.ok) {
    return finish(
      { error: auth.error },
      auth.status,
      auth.status === 401
        ? "unauthorized"
        : "server_not_configured",
      "warn",
    );
  }

  if (request.method !== "POST") {
    return finish(
      { error: "method_not_allowed" },
      405,
      "method_not_allowed",
      "warn",
      { Allow: "POST" },
    );
  }

  const apiKey = env.OPENAI_API_KEY;
  if (!apiKey) {
    return finish(
      { error: "server_not_configured" },
      503,
      "server_not_configured",
      "warn",
    );
  }

  let protocolVersion: AdvisorProtocolVersion | null = null;

  try {
    let result: AgentResult;

    if (endpoint === "start") {
      const input = await parseStartRequest(request);
      protocolVersion = input.protocolVersion;
      diagnostic.protocolVersion = input.protocolVersion;
      diagnostic.providerId = input.providerId;
      diagnostic.branchId = input.branchId;
      diagnostic.inputKind = inputKindForAttachment(input.attachment ?? input.attachments?.[0]);
      result = await startAgent(
        input.message,
        input.providerId,
        input.branchId,
        apiKey,
        upstreamFetch,
        input.protocolVersion,
        input.attachment ?? input.attachments,
      );
    } else if (endpoint === "message") {
      const input = await parseMessageRequest(request);
      protocolVersion = input.protocolVersion;
      diagnostic.protocolVersion = input.protocolVersion;
      diagnostic.providerId = input.providerId;
      diagnostic.branchId = input.branchId;
      diagnostic.inputKind = inputKindForAttachment(input.attachment ?? input.attachments?.[0]);
      result = await messageAgent(
        input.previousResponseId,
        input.message,
        input.providerId,
        input.branchId,
        apiKey,
        upstreamFetch,
        input.protocolVersion,
        input.attachment ?? input.attachments,
      );
    } else {
      const input = await parseContinueRequest(request);
      protocolVersion = input.protocolVersion;
      diagnostic.protocolVersion = input.protocolVersion;
      diagnostic.providerId = input.providerId;
      diagnostic.branchId = input.branchId;
      diagnostic.inputKind = "tool_result";
      applyContinuationSummary(
        diagnostic,
        summarizeContinuationResult(input.result),
      );
      result = await continueAgent(
        input.responseId,
        input.callId,
        input.providerId,
        input.branchId,
        input.result,
        apiKey,
        upstreamFetch,
        input.protocolVersion,
      );
    }

    applyAgentResult(diagnostic, result);
    return finish(result, 200, "success", "info");
  } catch (error) {
    const failureProtocolVersion =
      error instanceof InvalidRequestError ||
      error instanceof UnsupportedProtocolVersionError
        ? error.protocolVersion
        : protocolVersion;
    diagnostic.protocolVersion = failureProtocolVersion;

    if (error instanceof RequestTooLargeError) {
      diagnostic.validationFailureCategory = "request_too_large";
      return finish(
        { error: "request_too_large" },
        413,
        "validation_failure",
        "warn",
      );
    }
    if (error instanceof UnsupportedProtocolVersionError) {
      diagnostic.validationFailureCategory = error.category;
      return finish(
        { error: "unsupported_protocol_version" },
        400,
        "validation_failure",
        "warn",
      );
    }
    if (error instanceof InvalidRequestError) {
      diagnostic.validationFailureCategory = error.category;
      return finish(
        { error: "invalid_request" },
        400,
        "validation_failure",
        "warn",
      );
    }
    if (error instanceof UpstreamFailureError) {
      diagnostic.upstreamFailureCategory = error.category;
      diagnostic.upstreamStatus = error.upstreamStatus;
      return finish(
        { error: "upstream_failure" },
        502,
        "upstream_failure",
        "warn",
      );
    }

    diagnostic.validationFailureCategory = "unexpected_failure";
    return finish(
      { error: "upstream_failure" },
      502,
      "unexpected_failure",
      "warn",
    );
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

  if (url.pathname === "/v1/agent/message") {
    return handleProtectedAgentRequest(request, env, upstreamFetch, "message");
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
