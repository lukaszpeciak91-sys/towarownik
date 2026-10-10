export interface Env {
  OPENAI_API_KEY?: string;
  TOWAROWNIK_APP_TOKEN?: string;
}

export type AdvisorProtocolVersion = 1 | 2 | 3 | 4 | 5;

export interface AdvisorAttachment {
  mimeType: "image/jpeg" | "image/png" | "application/pdf";
  bytes: Uint8Array;
  filename: string;
}

export interface ToolQuery {
  query: string;
  limit: number;
}

export interface LegacyToolArguments {
  query: string;
  storeNumber: string;
  limit: number;
}

export interface ToolArguments {
  storeNumber: string;
  queries: ToolQuery[];
}

export interface ProviderToolArguments {
  providerId: string;
  branchId: string;
  requestedBranch?: string | null;
  queries: ToolQuery[];
}

export interface LocationToolArguments {
  providerId: string;
  productId: string;
  locations: string[];
}
export interface LocationToolResult {
  providerId: string;
  productId: string | null;
  status: "verified" | "unavailable" | "rejected";
  reason: string | null;
  coverage: "all_public_locations" | "all_other_locations" | "requested_subset" | "partial" | "unknown";
  checkedIds: string[];
  returnedIds: string[];
  missingIds: string[];
  locations: Array<{ branchId: string; name: string; stock: number | null }>;
  verifiedAtMillis: number | null;
  centralStock: number | null;
}
export type VersionedToolArguments =
  | LegacyToolArguments
  | ToolArguments
  | ProviderToolArguments
  | LocationToolArguments;

export interface TechnicalFact {
  label: string;
  value: string;
}

export interface VerifiedProduct {
  obik: string;
  name: string;
  brand: string | null;
  shortDescription: string | null;
  technicalFacts: TechnicalFact[];
  stock: number | null;
  price: number | null;
}

export interface ProviderVerifiedProduct {
  productId: string;
  articleNumber: string | null;
  name: string;
  brand: string | null;
  shortDescription: string | null;
  technicalFacts: TechnicalFact[];
  stock: number | null;
  centralStock: number | null;
  price: number | null;
  priceScope: "branch" | "online" | null;
}

export interface LegacyVerifiedToolResult {
  query: string;
  storeNumber: string;
  products: VerifiedProduct[];
}

export interface LegacyRejectedToolResult {
  query: string;
  storeNumber: string;
  rejection: "store_not_authorized";
}

export type VerifiedQueryStatus =
  | "verified"
  | "not_found"
  | "unavailable";

export interface VerifiedQueryResult {
  query: string;
  status: VerifiedQueryStatus;
  products: VerifiedProduct[];
}

export interface VerifiedToolResult {
  storeNumber: string;
  results: VerifiedQueryResult[];
}

export interface RejectedToolResult {
  storeNumber: string;
  queries: ToolQuery[];
  rejection: "store_not_authorized";
}

export interface ProviderRejectedToolResult {
  providerId: string;
  branchId: string;
  queries: ToolQuery[];
  rejection: "branch_not_authorized";
}

export interface LocalToolLimitResult {
  storeNumber: string;
  queries: ToolQuery[];
  rejection: "local_tool_limit_reached";
}

export interface ProviderLocalToolLimitResult {
  providerId: string;
  branchId: string;
  queries: ToolQuery[];
  rejection: "local_tool_limit_reached";
}

export interface ProviderVerifiedQueryResult {
  query: string;
  status: VerifiedQueryStatus;
  products: ProviderVerifiedProduct[];
}

export interface ProviderVerifiedToolResult {
  providerId: string;
  branchId: string;
  results: ProviderVerifiedQueryResult[];
}

export type ToolContinuationResult =
  | LocationToolResult
  | LegacyVerifiedToolResult
  | LegacyRejectedToolResult
  | VerifiedToolResult
  | RejectedToolResult
  | LocalToolLimitResult
  | ProviderVerifiedToolResult
  | ProviderRejectedToolResult
  | ProviderLocalToolLimitResult;

export interface LegacyProductRef {
  storeNumber: string;
  obik: string;
}

export interface ProviderProductRef {
  providerId: string;
  branchId: string;
  productId: string;
}

export type ProductRef = LegacyProductRef | ProviderProductRef;

export interface WebSource {
  title: string;
  url: string;
  startIndex: number | null;
  endIndex: number | null;
}

export type AgentRequestType = "START" | "MESSAGE" | "CONTINUE";

export interface AgentUsage {
  model: string;
  requestType: AgentRequestType;
  inputTokens: number;
  cachedInputTokens: number | null;
  cacheWriteTokens: number | null;
  outputTokens: number;
  reasoningTokens: number | null;
  totalTokens: number;
  estimatedCostUsd: number | null;
  pricingVersion: string | null;
}

export type AgentResult =
  | {
      type: "answer";
      responseId: string;
      text: string;
      productRefs: ProductRef[];
      sources?: WebSource[];
      webSearchCalls: number;
      usage?: AgentUsage;
    }
  | {
      type: "tool_request";
      responseId: string;
      tool: {
        name: "find_obi_products" | "find_products" | "find_product_locations";
        callId: string;
        arguments: VersionedToolArguments;
      };
      webSearchCalls: number;
      usage?: AgentUsage;
    };

export type UpstreamFetch = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;
