import type {
  AgentResult,
  AgentUsage,
  AdvisorAttachment,
  ToolContinuationResult,
  VersionedToolArguments,
} from "./types.js";

export const TRACE_HEADER_NAME = "X-Taksula-Trace-Id";
export const ADVISOR_OBSERVABILITY_SCHEMA_VERSION = 2;

export type AgentStage = "START" | "MESSAGE" | "CONTINUE";
export type AdvisorInputKind =
  | "text"
  | "image"
  | "pdf"
  | "tool_result";

export type AdvisorOutcome =
  | "success"
  | "unauthorized"
  | "method_not_allowed"
  | "server_not_configured"
  | "validation_failure"
  | "upstream_failure"
  | "unexpected_failure";

export type LocalResultKind =
  | "verified"
  | "rejected"
  | "mixed"
  | "not_found"
  | "unavailable";

export interface ContinuationResultSummary {
  localResultKind: LocalResultKind;
  localResultProviderId: string;
  localResultBranchId: string | null;
  resultQueryCount: number;
  verifiedProductCount: number;
  verifiedQueryCount: number;
  notFoundQueryCount: number;
  unavailableQueryCount: number;
  rejectionCategory:
    | "store_not_authorized"
    | "branch_not_authorized"
    | "local_tool_limit_reached"
    | null;
}

export interface AdvisorDiagnostic {
  event: "advisor_protocol";
  schemaVersion: number;
  traceId: string;
  requestId: string;
  protocolVersion: number | null;
  endpointStage: AgentStage;
  providerId: string | null;
  branchId: string | null;
  inputKind: AdvisorInputKind | null;
  responseEnvelopeType: AgentResult["type"] | null;
  localToolRequested: boolean;
  localQueryCount: number;
  requestedProductLimitTotal: number;
  requestedBranchPresent: boolean | null;
  webSearchCalls: number;
  finalProductRefCount: number;
  webSourceCount: number;
  durationMs: number;
  outcome: AdvisorOutcome;
  httpStatus: number;
  validationFailureCategory: string | null;
  upstreamFailureCategory: string | null;
  upstreamStatus: number | null;
  localResultKind: LocalResultKind | null;
  localResultProviderId: string | null;
  localResultBranchId: string | null;
  resultQueryCount: number;
  verifiedProductCount: number;
  verifiedQueryCount: number;
  notFoundQueryCount: number;
  unavailableQueryCount: number;
  rejectionCategory:
    | "store_not_authorized"
    | "branch_not_authorized"
    | "local_tool_limit_reached"
    | null;
  model: string | null;
  requestType: AgentUsage["requestType"] | null;
  inputTokens: number | null;
  cachedInputTokens: number | null;
  cacheWriteTokens: number | null;
  outputTokens: number | null;
  reasoningTokens: number | null;
  totalTokens: number | null;
  estimatedCostUsd: number | null;
  pricingVersion: string | null;
}

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function traceIdForRequest(
  request: Request,
  stage: AgentStage,
): string {
  if (stage === "CONTINUE") {
    const inbound = request.headers.get(TRACE_HEADER_NAME);
    if (inbound && UUID_PATTERN.test(inbound)) {
      return inbound.toLowerCase();
    }
  }
  return safeRandomUuid();
}

export function newRequestId(): string {
  return safeRandomUuid();
}

function safeRandomUuid(): string {
  try {
    return crypto.randomUUID();
  } catch {
    try {
      const bytes = new Uint8Array(16);
      crypto.getRandomValues(bytes);
      bytes[6] = (bytes[6] & 0x0f) | 0x40;
      bytes[8] = (bytes[8] & 0x3f) | 0x80;
      const hex = [...bytes]
        .map((value) => value.toString(16).padStart(2, "0"))
        .join("");
      return (
        hex.slice(0, 8) +
        "-" +
        hex.slice(8, 12) +
        "-" +
        hex.slice(12, 16) +
        "-" +
        hex.slice(16, 20) +
        "-" +
        hex.slice(20)
      );
    } catch {
      return "00000000-0000-4000-8000-000000000000";
    }
  }
}

export function inputKindForAttachment(
  attachment?: AdvisorAttachment,
): AdvisorInputKind {
  if (!attachment) return "text";
  return attachment.textContent !== undefined
    ? "text"
    : attachment.mimeType === "application/pdf" ? "pdf" : "image";
}

export function resultSummary(
  result: AgentResult,
): Pick<
  AdvisorDiagnostic,
  | "responseEnvelopeType"
  | "localToolRequested"
  | "localQueryCount"
  | "requestedProductLimitTotal"
  | "requestedBranchPresent"
  | "webSearchCalls"
  | "finalProductRefCount"
  | "webSourceCount"
> {
  if (result.type === "answer") {
    return {
      responseEnvelopeType: "answer",
      localToolRequested: false,
      localQueryCount: 0,
      requestedProductLimitTotal: 0,
      requestedBranchPresent: null,
      webSearchCalls: result.webSearchCalls,
      finalProductRefCount: result.productRefs.length,
      webSourceCount: result.sources?.length ?? 0,
    };
  }

  const tool = summarizeToolArguments(result.tool.arguments);
  return {
    responseEnvelopeType: "tool_request",
    localToolRequested: true,
    localQueryCount: tool.localQueryCount,
    requestedProductLimitTotal: tool.requestedProductLimitTotal,
    requestedBranchPresent: tool.requestedBranchPresent,
    webSearchCalls: result.webSearchCalls,
    finalProductRefCount: 0,
    webSourceCount: 0,
  };
}

function summarizeToolArguments(
  args: VersionedToolArguments,
): {
  localQueryCount: number;
  requestedProductLimitTotal: number;
  requestedBranchPresent: boolean | null;
} {
  if ("locations" in args) {
    return {
      localQueryCount: 0,
      requestedProductLimitTotal: 0,
      requestedBranchPresent: args.locations.length > 0,
    };
  }
  if ("queries" in args) {
    return {
      localQueryCount: args.queries.length,
      requestedProductLimitTotal: args.queries.reduce(
        (sum, query) => sum + query.limit,
        0,
      ),
      requestedBranchPresent:
        "providerId" in args
          ? typeof args.requestedBranch === "string"
          : null,
    };
  }

  return {
    localQueryCount: 1,
    requestedProductLimitTotal: args.limit,
    requestedBranchPresent: null,
  };
}

export function summarizeContinuationResult(
  result: ToolContinuationResult,
): ContinuationResultSummary {
  if ("rejection" in result) {
    const queryCount = "queries" in result ? result.queries.length : 1;
    return {
      localResultKind: "rejected",
      localResultProviderId:
        "providerId" in result ? result.providerId : "obi-pl",
      localResultBranchId:
        "branchId" in result ? result.branchId : result.storeNumber,
      resultQueryCount: queryCount,
      verifiedProductCount: 0,
      verifiedQueryCount: 0,
      notFoundQueryCount: 0,
      unavailableQueryCount: 0,
      rejectionCategory: result.rejection,
    };
  }

  if ("checkedIds" in result) {
    return {
      localResultKind: result.status === "verified" ? "verified" :
        result.status === "rejected" ? "rejected" : "unavailable",
      localResultProviderId: result.providerId,
      localResultBranchId: null,
      resultQueryCount: 0,
      verifiedProductCount: 0,
      verifiedQueryCount: 0,
      notFoundQueryCount: 0,
      unavailableQueryCount: result.status === "unavailable" ? 1 : 0,
      rejectionCategory: result.reason === "local_tool_limit_reached"
        ? "local_tool_limit_reached" : null,
    };
  }
  if ("products" in result) {
    return {
      localResultKind: "verified",
      localResultProviderId: "obi-pl",
      localResultBranchId: result.storeNumber,
      resultQueryCount: 1,
      verifiedProductCount: result.products.length,
      verifiedQueryCount: 1,
      notFoundQueryCount: 0,
      unavailableQueryCount: 0,
      rejectionCategory: null,
    };
  }

  const statuses = new Set(result.results.map((entry) => entry.status));
  const verifiedQueryCount = result.results.filter(
    (entry) => entry.status === "verified",
  ).length;
  const notFoundQueryCount = result.results.filter(
    (entry) => entry.status === "not_found",
  ).length;
  const unavailableQueryCount = result.results.filter(
    (entry) => entry.status === "unavailable",
  ).length;
  const verifiedProductCount = result.results.reduce(
    (sum, entry) =>
      sum + (entry.status === "verified" ? entry.products.length : 0),
    0,
  );

  let localResultKind: LocalResultKind;
  if (statuses.size > 1) {
    localResultKind = "mixed";
  } else if (statuses.has("verified")) {
    localResultKind = "verified";
  } else if (statuses.has("not_found")) {
    localResultKind = "not_found";
  } else {
    localResultKind = "unavailable";
  }

  return {
    localResultKind,
    localResultProviderId:
      "providerId" in result ? result.providerId : "obi-pl",
    localResultBranchId:
      "branchId" in result ? result.branchId : result.storeNumber,
    resultQueryCount: result.results.length,
    verifiedProductCount,
    verifiedQueryCount,
    notFoundQueryCount,
    unavailableQueryCount,
    rejectionCategory: null,
  };
}

export function emptyAdvisorDiagnostic(
  traceId: string,
  requestId: string,
  stage: AgentStage,
): AdvisorDiagnostic {
  return {
    event: "advisor_protocol",
    schemaVersion: ADVISOR_OBSERVABILITY_SCHEMA_VERSION,
    traceId,
    requestId,
    protocolVersion: null,
    endpointStage: stage,
    providerId: null,
    branchId: null,
    inputKind: null,
    responseEnvelopeType: null,
    localToolRequested: false,
    localQueryCount: 0,
    requestedProductLimitTotal: 0,
    requestedBranchPresent: null,
    webSearchCalls: 0,
    finalProductRefCount: 0,
    webSourceCount: 0,
    durationMs: 0,
    outcome: "unexpected_failure",
    httpStatus: 500,
    validationFailureCategory: null,
    upstreamFailureCategory: null,
    upstreamStatus: null,
    localResultKind: null,
    localResultProviderId: null,
    localResultBranchId: null,
    resultQueryCount: 0,
    verifiedProductCount: 0,
    verifiedQueryCount: 0,
    notFoundQueryCount: 0,
    unavailableQueryCount: 0,
    rejectionCategory: null,
    model: null,
    requestType: null,
    inputTokens: null,
    cachedInputTokens: null,
    cacheWriteTokens: null,
    outputTokens: null,
    reasoningTokens: null,
    totalTokens: null,
    estimatedCostUsd: null,
    pricingVersion: null,
  };
}

export function applyUsage(
  diagnostic: AdvisorDiagnostic,
  usage?: AgentUsage,
): void {
  if (!usage) return;
  diagnostic.model = usage.model;
  diagnostic.requestType = usage.requestType;
  diagnostic.inputTokens = usage.inputTokens;
  diagnostic.cachedInputTokens = usage.cachedInputTokens;
  diagnostic.cacheWriteTokens = usage.cacheWriteTokens;
  diagnostic.outputTokens = usage.outputTokens;
  diagnostic.reasoningTokens = usage.reasoningTokens;
  diagnostic.totalTokens = usage.totalTokens;
  diagnostic.estimatedCostUsd = usage.estimatedCostUsd;
  diagnostic.pricingVersion = usage.pricingVersion;
}

export function applyContinuationSummary(
  diagnostic: AdvisorDiagnostic,
  summary: ContinuationResultSummary,
): void {
  diagnostic.localResultKind = summary.localResultKind;
  diagnostic.localResultProviderId = summary.localResultProviderId;
  diagnostic.localResultBranchId = summary.localResultBranchId;
  diagnostic.resultQueryCount = summary.resultQueryCount;
  diagnostic.verifiedProductCount = summary.verifiedProductCount;
  diagnostic.verifiedQueryCount = summary.verifiedQueryCount;
  diagnostic.notFoundQueryCount = summary.notFoundQueryCount;
  diagnostic.unavailableQueryCount = summary.unavailableQueryCount;
  diagnostic.rejectionCategory = summary.rejectionCategory;
}

export function applyAgentResult(
  diagnostic: AdvisorDiagnostic,
  result: AgentResult,
): void {
  const summary = resultSummary(result);
  Object.assign(diagnostic, summary);
  applyUsage(diagnostic, result.usage);
}

export function emitAdvisorDiagnostic(
  diagnostic: AdvisorDiagnostic,
  level: "info" | "warn",
): void {
  try {
    const line = JSON.stringify(diagnostic);
    if (level === "warn") {
      console.warn(line);
    } else {
      console.info(line);
    }
  } catch {
    // Observability must never affect the Advisor response path.
  }
}
