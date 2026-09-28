export interface Env {
  OPENAI_API_KEY?: string;
  TOWAROWNIK_APP_TOKEN?: string;
}

export interface ToolArguments {
  query: string;
  storeNumber: string;
  limit: number;
}

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
        name: "find_obi_products";
        callId: string;
        arguments: ToolArguments;
      };
      webSearchCalls: number;
      usage?: AgentUsage;
    };

export type UpstreamFetch = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;
