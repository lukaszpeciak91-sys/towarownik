export interface Env {
  OPENAI_API_KEY?: string;
  TOWAROWNIK_APP_TOKEN?: string;
}

export interface ToolArguments {
  query: string;
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
  products: VerifiedProduct[];
}

export type AgentResult =
  | {
      type: "answer";
      responseId: string;
      text: string;
    }
  | {
      type: "tool_request";
      responseId: string;
      tool: {
        name: "find_available_obi_075";
        callId: string;
        arguments: ToolArguments;
      };
    };

export type UpstreamFetch = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;
