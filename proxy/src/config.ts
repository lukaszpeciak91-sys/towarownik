export const OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";
export const OPENAI_MODEL = "gpt-5.6-luna";
export const OPENAI_REASONING_EFFORT = "low";
export const OPENAI_MAX_OUTPUT_TOKENS = 384;

export const LOCAL_TOOL_NAME = "find_available_obi_075";
export const MAX_TOOL_PRODUCTS = 5;
export const MAX_TOOL_QUERY_CHARS = 200;
export const MAX_PRODUCT_NAME_CHARS = 200;

export const START_BODY_MAX_BYTES = 4 * 1024;
export const START_MESSAGE_MAX_CHARS = 2_000;
export const CONTINUE_BODY_MAX_BYTES = 16 * 1024;
export const MAX_RESPONSE_ID_CHARS = 256;
export const MAX_CALL_ID_CHARS = 256;
export const MAX_ANSWER_CHARS = 4_000;

export const AGENT_INSTRUCTIONS =
  "You are a concise retail-product assistant. Ask at most one concise clarification when needed. " +
  "OBI store availability, price, OBIK, and stock are factual only when supplied by the local " +
  "find_available_obi_075 tool. Never invent those values. When an OBI product lookup is required, " +
  "call find_available_obi_075.";

export const OBI_TOOL = {
  type: "function",
  name: LOCAL_TOOL_NAME,
  description:
    "Ask the Android app to find verified OBI store 075 products using the app's existing local OBI lookup.",
  strict: true,
  parameters: {
    type: "object",
    properties: {
      query: {
        type: "string",
        description: "Concise product search phrase for the Android app.",
      },
      limit: {
        type: "integer",
        minimum: 1,
        maximum: MAX_TOOL_PRODUCTS,
        description: "Maximum number of verified products to return.",
      },
    },
    required: ["query", "limit"],
    additionalProperties: false,
  },
} as const;
