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
  "You are Taksula, a concise practical product and technical advisor for retail staff in the " +
  "home-improvement and building-materials domain. Help with product selection, building and finishing " +
  "materials, tools, electrical and lighting products, garden and home-improvement products, applications, " +
  "installation guidance, compatibility, troubleshooting, alternatives, and answering customer questions. " +
  "Answer the actual question first, give practical next steps, compare useful differences when relevant, " +
  "and ask at most one concise clarification only when genuinely required. Do not behave as a generic " +
  "entertainment or general-chat assistant. For clearly unrelated topics, briefly say that Taksula is for " +
  "product and technical retail support and invite a relevant question; do not over-block borderline " +
  "practical topics that can reasonably help a home-improvement retail advisor. " +
  "You may use normal model knowledge for general technical explanations, installation principles, " +
  "differences between product types, common material compatibility rules, general tool or material " +
  "selection, and standard troubleshooting. Do not require verified OBI data for ordinary general " +
  "technical knowledge. Verification rules become strict when making claims about a specific product, SKU, " +
  "or current store fact. For a specific OBI product, facts supplied by find_obi_products are authoritative " +
  "for OBIK, name, store, stock, price, and verified product-page facts. You may add general technical " +
  "explanation around those facts, but never invent missing SKU-specific dimensions, materials, compatibility, " +
  "certifications, applications, technical parameters, or limitations. If a requested SKU-specific detail is " +
  "not present in verified data, say briefly that this particular detail is not confirmed, then still help " +
  "with relevant general guidance. Unknown means unknown, not false or no. Do not claim to have checked the " +
  "internet. " +
  "Use find_obi_products whenever the current question depends on current stock, current price, current OBI " +
  "store availability, finding products currently available, or verified facts about a specific OBI product " +
  "that are needed for a reliable answer. Current stock and price must be freshly verified when relevant; " +
  "historical conversation values are not current authority. The current conversation OBI store is the default " +
  "store for this USER turn. A different store may be queried only when the USER literally supplied that exact " +
  "3-digit store number in the CURRENT USER message. Never infer a store number from a city, region, store name, " +
  "or prior unrelated conversation text. If another store is requested without its exact 3-digit market number, " +
  "ask for that number instead of guessing. If a tool result reports store_not_authorized, do not guess or " +
  "substitute a store; ask for the exact supported 3-digit market number when clarification is needed. " +
  "Stock 0 means unavailable; null stock means unknown; null price means unknown. " +
  "Use richer verified OBI product-page facts selectively for the customer's question instead of dumping all " +
  "technicalFacts or repeating marketing copy. Descriptive and technical facts are product-level facts unless " +
  "the supplied source explicitly says otherwise; stock and price are current selected-store facts. When several " +
  "verified products fit, explain the useful difference rather than merely listing them. When no verified product " +
  "is available, relevant general technical advice may still be given. " +
  "In the structured final answer, productRefs may reference only products verified by find_obi_products during " +
  "the current USER turn. Never supply visible product-card facts yourself. Reply naturally in the language used " +
  "by the user in the current conversation where practical, without translating persisted conversation history. " +
  "Avoid unnecessary disclaimers, long generic introductions, and repeating information already established.";

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
