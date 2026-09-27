export interface Env {
  OPENAI_API_KEY?: string;
  TOWAROWNIK_APP_TOKEN?: string;
}

export interface ToolArguments {
  query: string;
  storeNumber: string;
  limit: number;
}

export interface VerifiedProduct {
  obik: string;
  name: string;
  stock: number | null;
  price: number | null;
}

export interface VerifiedToolResult {
  query: string;
  storeNumber: string;
  products: VerifiedProduct[];
}

export interface RejectedToolResult {
  query: string;
  storeNumber: string;
  rejection: "store_not_authorized";
}

export type ToolContinuationResult =
  | VerifiedToolResult
  | RejectedToolResult;

export interface ProductRef {
  storeNumber: string;
  obik: string;
}

export type AgentRequestType = "START" | "MESSAGE" | "CONTINUE";

export interface AgentUsage {
  model: string;
  requestType: AgentRequestType;
  inputTokens: number;
  cachedInputTokens: number | null;
  outputTokens: number;
  reasoningTokens: number | null;
  totalTokens: number;
  estimatedCostUsd: string | null;
  pricingVersion: string | null;
}

export type AgentResult =
  | {
      type: "answer";
      responseId: string;
      text: string;
      productRefs: ProductRef[];
      usage?: AgentUsage;
    }
  | {
      type: "tool_request";
      responseId: string;
      tool: {
        name: "find_obi_products";
        callId: string;
        arguments: ToolArguments;
      };
      usage?: AgentUsage;
    };

export type UpstreamFetch = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;
