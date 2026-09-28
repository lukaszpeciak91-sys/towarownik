export const OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";
export const OPENAI_MODEL = "gpt-6-luna";
export const OPENAI_REASONING_EFFORT = "low";
export const OPENAI_MAX_OUTPUT_TOKENS = 384;

export const CURRENT_MODEL_PRICING = {
  model: OPENAI_MODEL,
  pricingVersion: "openai-gpt-6-luna-2026-09-27-v1",
  longContextInputThreshold: 272_000,
  usdPerMillionTokens: {
    uncachedInput: "0.10",
    cachedInput: "0.01",
    cacheWriteInput: "0.125",
    output: "0.50",
  },
  nanoUsdPerToken: {
    uncachedInput: 100n,
    cachedInput: 10n,
    cacheWriteInput: 125n,
    output: 500n,
  },
  longContextNanoUsdPerToken: {
    uncachedInput: 200n,
    cachedInput: 20n,
    cacheWriteInput: 250n,
    output: 750n,
  },
} as const;

export const MAX_USAGE_TOKEN_COUNT = 10_000_000_000;
export const MAX_MODEL_NAME_CHARS = 100;

export const LOCAL_TOOL_NAME = "find_obi_products";
export const MAX_TOOL_PRODUCTS = 5;
export const MAX_TOOL_QUERY_CHARS = 200;
export const MAX_PRODUCT_NAME_CHARS = 200;
export const MAX_PRODUCT_BRAND_CHARS = 80;
export const MAX_PRODUCT_DESCRIPTION_CHARS = 220;
export const MAX_PRODUCT_TECHNICAL_FACTS = 6;
export const MAX_PRODUCT_FACT_LABEL_CHARS = 60;
export const MAX_PRODUCT_FACT_VALUE_CHARS = 100;

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
  "market number when clarification is needed. Product-page facts returned by find_obi_products are verified " +
  "OBI facts for that specific product; use them when relevant but never invent missing technical properties. " +
  "Treat missing facts as unknown, not as false or no. Stock and price are current selected-store facts; " +
  "descriptive and technical product facts are product-level facts unless the supplied source explicitly says otherwise.";

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
