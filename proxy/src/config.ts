export const OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";
export const OPENAI_MODEL = "gpt-5.6-luna";
export const OPENAI_REASONING_EFFORT = "low";
export const OPENAI_MAX_OUTPUT_TOKENS = 384;

export const LOCAL_TOOL_NAME = "find_obi_products";
export const MAX_TOOL_PRODUCTS = 5;
export const MAX_TOOL_QUERY_CHARS = 200;
export const MAX_PRODUCT_NAME_CHARS = 200;

export const START_BODY_MAX_BYTES = 4 * 1024;
export const START_MESSAGE_MAX_CHARS = 2_000;
export const CONTINUE_BODY_MAX_BYTES = 16 * 1024;
export const MESSAGE_BODY_MAX_BYTES = 4 * 1024;
export const MAX_RESPONSE_ID_CHARS = 256;
export const MAX_CALL_ID_CHARS = 256;
export const MAX_ANSWER_CHARS = 4_000;
export const MAX_SELECTED_PRODUCT_REFS = 5;

export const AGENT_INSTRUCTIONS =
  "You are a concise retail-product assistant. Ask at most one concise clarification when needed. " +
  "The current conversation OBI store is the default store for this USER turn. Current OBI availability, " +
  "stock, price, and product selection facts are factual only when supplied by the local find_obi_products " +
  "tool and must be freshly verified instead of relying on historical conversation values. Never invent " +
  "those values. A different store may be queried only when the USER literally supplied that exact " +
  "3-digit store number in the CURRENT USER message. Never infer a store number from a city, region, " +
  "store name, or prior unrelated conversation text. If the user wants another store without supplying " +
  "its exact 3-digit market number, ask for that number instead of guessing. Stock 0 means unavailable; " +
  "null stock means unknown; null price means unknown. In the structured final answer, productRefs may " +
  "reference only products verified by find_obi_products during the current USER turn. If a tool result " +
  "reports store_not_authorized, do not guess or substitute a store; ask for the exact supported 3-digit " +
  "market number when clarification is needed.";

export function agentInstructionsForStore(storeNumber: string): string {
  return AGENT_INSTRUCTIONS +
    " Current conversation store for this USER turn is OBI " +
    storeNumber +
    ".";
}

export const OBI_TOOL = {
  type: "function",
  name: LOCAL_TOOL_NAME,
  description:
    "Ask the Android app to find verified OBI products for one explicit 3-digit store number.",
  strict: true,
  parameters: {
    type: "object",
    properties: {
      query: {
        type: "string",
        description: "Concise product search phrase for the Android app.",
      },
      storeNumber: {
        type: "string",
        pattern: "^[0-9]{3}$",
        description:
          "One explicit OBI store number. Use the current conversation store by default.",
      },
      limit: {
        type: "integer",
        minimum: 1,
        maximum: MAX_TOOL_PRODUCTS,
        description: "Maximum number of verified products to return.",
      },
    },
    required: ["query", "storeNumber", "limit"],
    additionalProperties: false,
  },
} as const;

export const FINAL_ANSWER_FORMAT = {
  type: "json_schema",
  name: "advisor_final_answer",
  description:
    "Concise advisor text plus optional store-aware references for locally verified product cards.",
  strict: true,
  schema: {
    type: "object",
    properties: {
      text: {
        type: "string",
        minLength: 1,
        maxLength: MAX_ANSWER_CHARS,
      },
      productRefs: {
        type: "array",
        maxItems: MAX_SELECTED_PRODUCT_REFS,
        items: {
          type: "object",
          properties: {
            storeNumber: {
              type: "string",
              pattern: "^[0-9]{3}$",
            },
            obik: {
              type: "string",
              pattern: "^[0-9]{7}$",
            },
          },
          required: ["storeNumber", "obik"],
          additionalProperties: false,
        },
      },
    },
    required: ["text", "productRefs"],
    additionalProperties: false,
  },
} as const;
