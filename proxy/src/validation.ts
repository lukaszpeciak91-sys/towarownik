import {
  CONTINUE_BODY_MAX_BYTES,
  MAX_CALL_ID_CHARS,
  MESSAGE_BODY_MAX_BYTES,
  MAX_PRODUCT_NAME_CHARS,
  MAX_PRODUCT_BRAND_CHARS,
  MAX_PRODUCT_DESCRIPTION_CHARS,
  MAX_PRODUCT_TECHNICAL_FACTS,
  MAX_PRODUCT_FACT_LABEL_CHARS,
  MAX_PRODUCT_FACT_VALUE_CHARS,
  MAX_RESPONSE_ID_CHARS,
  MAX_TOOL_PRODUCTS,
  MAX_TOOL_QUERY_CHARS,
  START_BODY_MAX_BYTES,
  START_MESSAGE_MAX_CHARS,
} from "./config.js";
import type {
  RejectedToolResult,
  ToolArguments,
  ToolContinuationResult,
  VerifiedProduct,
  VerifiedToolResult,
} from "./types.js";

export class InvalidRequestError extends Error {}
export class RequestTooLargeError extends Error {}

export interface StartRequest {
  message: string;
  storeNumber: string;
}

export interface MessageRequest extends StartRequest {
  previousResponseId: string;
}

export interface ContinueRequest {
  responseId: string;
  callId: string;
  storeNumber: string;
  tool: "find_obi_products";
  result: ToolContinuationResult;
}

export async function readJsonBody(
  request: Request,
  maxBytes: number,
): Promise<unknown> {
  if (!isJsonContentType(request.headers.get("Content-Type"))) {
    throw new InvalidRequestError();
  }

  const declaredLength = parseContentLength(
    request.headers.get("Content-Length"),
  );
  if (declaredLength !== null && declaredLength > maxBytes) {
    throw new RequestTooLargeError();
  }

  const text = await readUtf8Body(request, maxBytes);

  try {
    return JSON.parse(text) as unknown;
  } catch {
    throw new InvalidRequestError();
  }
}

export async function parseStartRequest(
  request: Request,
): Promise<StartRequest> {
  const value = await readJsonBody(request, START_BODY_MAX_BYTES);
  const object = exactObject(value, ["message", "storeNumber"]);

  return {
    message: validatedMessage(object.message),
    storeNumber: validateStoreNumber(object.storeNumber),
  };
}

export async function parseMessageRequest(
  request: Request,
): Promise<MessageRequest> {
  const value = await readJsonBody(request, MESSAGE_BODY_MAX_BYTES);
  const object = exactObject(
    value,
    ["previousResponseId", "message", "storeNumber"],
  );

  return {
    previousResponseId: boundedString(
      object.previousResponseId,
      MAX_RESPONSE_ID_CHARS,
    ),
    message: validatedMessage(object.message),
    storeNumber: validateStoreNumber(object.storeNumber),
  };
}

export async function parseContinueRequest(
  request: Request,
): Promise<ContinueRequest> {
  const value = await readJsonBody(request, CONTINUE_BODY_MAX_BYTES);
  const object = exactObject(
    value,
    ["responseId", "callId", "storeNumber", "tool", "result"],
  );

  const responseId = boundedString(
    object.responseId,
    MAX_RESPONSE_ID_CHARS,
  );
  const callId = boundedString(
    object.callId,
    MAX_CALL_ID_CHARS,
  );
  const storeNumber = validateStoreNumber(object.storeNumber);

  if (object.tool !== "find_obi_products") {
    throw new InvalidRequestError();
  }

  return {
    responseId,
    callId,
    storeNumber,
    tool: "find_obi_products",
    result: validateToolContinuationResult(object.result),
  };
}

export function parseToolArguments(raw: string): ToolArguments {
  let value: unknown;
  try {
    value = JSON.parse(raw) as unknown;
  } catch {
    throw new InvalidRequestError();
  }

  const object = exactObject(
    value,
    ["query", "storeNumber", "limit"],
  );
  const query = normalizeWhitespace(
    boundedString(object.query, MAX_TOOL_QUERY_CHARS),
  );
  const storeNumber = validateStoreNumber(object.storeNumber);

  if (
    !query ||
    typeof object.limit !== "number" ||
    !Number.isInteger(object.limit) ||
    object.limit < 1 ||
    object.limit > MAX_TOOL_PRODUCTS
  ) {
    throw new InvalidRequestError();
  }

  return {
    query,
    storeNumber,
    limit: object.limit,
  };
}

function validateToolContinuationResult(
  value: unknown,
): ToolContinuationResult {
  if (!isRecord(value)) {
    throw new InvalidRequestError();
  }

  if ("products" in value) {
    return validateVerifiedToolResult(value);
  }
  if ("rejection" in value) {
    return validateRejectedToolResult(value);
  }
  throw new InvalidRequestError();
}

function validateVerifiedToolResult(
  value: unknown,
): VerifiedToolResult {
  const object = exactObject(
    value,
    ["query", "storeNumber", "products"],
  );
  const query = normalizedToolQuery(object.query);
  const storeNumber = validateStoreNumber(object.storeNumber);

  if (
    !Array.isArray(object.products) ||
    object.products.length > MAX_TOOL_PRODUCTS
  ) {
    throw new InvalidRequestError();
  }

  return {
    query,
    storeNumber,
    products: object.products.map(validateProduct),
  };
}

function validateRejectedToolResult(
  value: unknown,
): RejectedToolResult {
  const object = exactObject(
    value,
    ["query", "storeNumber", "rejection"],
  );
  if (object.rejection !== "store_not_authorized") {
    throw new InvalidRequestError();
  }

  return {
    query: normalizedToolQuery(object.query),
    storeNumber: validateStoreNumber(object.storeNumber),
    rejection: "store_not_authorized",
  };
}

function validatedMessage(value: unknown): string {
  if (typeof value !== "string") {
    throw new InvalidRequestError();
  }
  const message = normalizeWhitespace(value);
  if (!message) {
    throw new InvalidRequestError();
  }
  if (message.length > START_MESSAGE_MAX_CHARS) {
    throw new RequestTooLargeError();
  }
  return message;
}

function normalizedToolQuery(value: unknown): string {
  const query = normalizeWhitespace(
    boundedString(value, MAX_TOOL_QUERY_CHARS),
  );
  if (!query) {
    throw new InvalidRequestError();
  }
  return query;
}

function validateStoreNumber(value: unknown): string {
  if (typeof value !== "string" || !/^\d{3}$/.test(value)) {
    throw new InvalidRequestError();
  }
  return value;
}

function validateProduct(value: unknown): VerifiedProduct {
  const object = exactObject(
    value,
    [
      "obik",
      "name",
      "brand",
      "shortDescription",
      "technicalFacts",
      "stock",
      "price",
    ],
  );

  if (
    typeof object.obik !== "string" ||
    !/^\d{7}$/.test(object.obik)
  ) {
    throw new InvalidRequestError();
  }

  const name = normalizeWhitespace(
    boundedString(object.name, MAX_PRODUCT_NAME_CHARS),
  );
  if (!name) {
    throw new InvalidRequestError();
  }

  return {
    obik: object.obik,
    name,
    brand: validateNullableNormalizedString(
      object.brand,
      MAX_PRODUCT_BRAND_CHARS,
    ),
    shortDescription: validateNullableNormalizedString(
      object.shortDescription,
      MAX_PRODUCT_DESCRIPTION_CHARS,
    ),
    technicalFacts: validateTechnicalFacts(
      object.technicalFacts,
    ),
    stock: validateNullableStock(object.stock),
    price: validateNullablePrice(object.price),
  };
}

function validateTechnicalFacts(
  value: unknown,
): VerifiedProduct["technicalFacts"] {
  if (
    !Array.isArray(value) ||
    value.length > MAX_PRODUCT_TECHNICAL_FACTS
  ) {
    throw new InvalidRequestError();
  }

  return value.map((entry) => {
    const object = exactObject(entry, ["label", "value"]);
    const label = normalizeWhitespace(
      boundedString(
        object.label,
        MAX_PRODUCT_FACT_LABEL_CHARS,
      ),
    );
    const factValue = normalizeWhitespace(
      boundedString(
        object.value,
        MAX_PRODUCT_FACT_VALUE_CHARS,
      ),
    );
    if (!label || !factValue) {
      throw new InvalidRequestError();
    }
    return {
      label,
      value: factValue,
    };
  });
}

function validateNullableNormalizedString(
  value: unknown,
  maxChars: number,
): string | null {
  if (value === null) return null;
  const normalized = normalizeWhitespace(
    boundedString(value, maxChars),
  );
  if (!normalized) {
    throw new InvalidRequestError();
  }
  return normalized;
}

function validateNullableStock(value: unknown): number | null {
  if (value === null) return null;
  if (
    typeof value !== "number" ||
    !Number.isInteger(value) ||
    value < 0
  ) {
    throw new InvalidRequestError();
  }
  return value;
}

function validateNullablePrice(value: unknown): number | null {
  if (value === null) return null;
  if (
    typeof value !== "number" ||
    !Number.isFinite(value) ||
    value < 0
  ) {
    throw new InvalidRequestError();
  }
  return value;
}

function exactObject(
  value: unknown,
  allowedKeys: readonly string[],
): Record<string, unknown> {
  if (!isRecord(value)) {
    throw new InvalidRequestError();
  }

  const keys = Object.keys(value);
  if (
    keys.length !== allowedKeys.length ||
    keys.some((key) => !allowedKeys.includes(key)) ||
    allowedKeys.some((key) => !(key in value))
  ) {
    throw new InvalidRequestError();
  }

  return value;
}

function boundedString(value: unknown, maxChars: number): string {
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    value.length > maxChars
  ) {
    throw new InvalidRequestError();
  }
  return value;
}

const normalizeWhitespace = (value: string): string =>
  value.replace(/[\s\u0000-\u001f\u007f]+/g, " ").trim();

function isJsonContentType(value: string | null): boolean {
  if (!value) return false;
  return value.split(";", 1)[0]?.trim().toLowerCase() ===
    "application/json";
}

function parseContentLength(value: string | null): number | null {
  if (!value) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
}

async function readUtf8Body(
  request: Request,
  maxBytes: number,
): Promise<string> {
  if (!request.body) {
    throw new InvalidRequestError();
  }

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value) continue;

      totalBytes += value.byteLength;
      if (totalBytes > maxBytes) {
        throw new RequestTooLargeError();
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  if (totalBytes === 0) {
    throw new InvalidRequestError();
  }

  const bytes = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }

  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new InvalidRequestError();
  }
}

function isRecord(
  value: unknown,
): value is Record<string, unknown> {
  return typeof value === "object" &&
    value !== null &&
    !Array.isArray(value);
}
