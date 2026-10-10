import {
  CONTINUE_BODY_MAX_BYTES,
  CURRENT_ADVISOR_PROTOCOL_VERSION,
  PROVIDER_ADVISOR_PROTOCOL_VERSION,
  LEGACY_ADVISOR_PROTOCOL_VERSION,
  OBI_GROUPED_ADVISOR_PROTOCOL_VERSION,
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
  MAX_TOOL_QUERIES,
  MAX_TOOL_QUERY_CHARS,
  START_BODY_MAX_BYTES,
  START_MESSAGE_MAX_CHARS,
  ATTACHMENT_MAX_BYTES,
  MULTIPART_BODY_MAX_BYTES,
  MULTI_MULTIPART_BODY_MAX_BYTES,
  MULTI_ATTACHMENT_ADVISOR_PROTOCOL_VERSION,
  MULTI_ATTACHMENT_PROTOCOL_HEADER,
  MAX_MULTI_ATTACHMENTS,
  LOCATIONS_LOCAL_TOOL_NAME,
  LOCATIONS_CAPABILITY_HEADER,
  LOCATIONS_CAPABILITY_VALUE,
  MULTI_ATTACHMENT_TOTAL_MAX_BYTES,
} from "./config.js";
import { decodeTextAttachment, isAllowedTextAttachment, TEXT_ATTACHMENT_MAX_BYTES } from "./text-attachments.js";
import type {
  AdvisorProtocolVersion,
  AdvisorAttachment,
  LegacyRejectedToolResult,
  LegacyToolArguments,
  LegacyVerifiedToolResult,
  LocalToolLimitResult,
  ProviderLocalToolLimitResult,
  ProviderRejectedToolResult,
  ProviderToolArguments,
  ProviderVerifiedProduct,
  ProviderVerifiedQueryResult,
  ProviderVerifiedToolResult,
  RejectedToolResult,
  ToolArguments,
  ToolContinuationResult,
  ToolQuery,
  VerifiedProduct,
  VerifiedQueryResult,
  VerifiedToolResult,
  VersionedToolArguments,
  LocationToolArguments,
  LocationToolResult,
} from "./types.js";

export class InvalidRequestError extends Error {
  readonly category = "invalid_request";

  constructor(
    readonly protocolVersion: number | null = null,
  ) {
    super("invalid request");
  }
}

export class UnsupportedProtocolVersionError extends Error {
  readonly category = "unsupported_protocol_version";

  constructor(readonly protocolVersion: number) {
    super("unsupported protocol version");
  }
}

export class RequestTooLargeError extends Error {}

export interface StartRequest {
  protocolVersion: AdvisorProtocolVersion;
  message: string;
  providerId: string;
  branchId: string;
  storeNumber: string;
  attachment?: AdvisorAttachment;
  attachments?: AdvisorAttachment[];
}

export interface MessageRequest extends StartRequest {
  previousResponseId: string;
}

export interface ContinueRequest {
  protocolVersion: AdvisorProtocolVersion;
  responseId: string;
  callId: string;
  providerId: string;
  branchId: string;
  storeNumber: string;
  tool: "find_obi_products" | "find_products" | "find_product_locations";
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
  if (isMultipartContentType(request.headers.get("Content-Type"))) {
    return parseMultipartRequest(request, false);
  }
  const value = await readJsonBody(request, START_BODY_MAX_BYTES);
  const record = requireRecord(value);
  const protocolVersion = validateProtocolVersion(record.protocolVersion);
  if (protocolVersion === MULTI_ATTACHMENT_ADVISOR_PROTOCOL_VERSION) {
    throw new InvalidRequestError(protocolVersion);
  }

  return withProtocolContext(protocolVersion, () => {
    if (protocolVersion >= PROVIDER_ADVISOR_PROTOCOL_VERSION) {
      const object = exactObjectShape(
        value,
        ["message", "providerId", "branchId"],
        ["protocolVersion"],
      );
      const providerId = validateProviderId(object.providerId);
      const branchId = validateBranchId(object.branchId);
      return {
        protocolVersion,
        message: validatedMessage(object.message),
        providerId,
        branchId,
        storeNumber: branchId,
      };
    }

    const object = exactObjectShape(
      value,
      ["message", "storeNumber"],
      ["protocolVersion"],
    );
    const storeNumber = validateStoreNumber(object.storeNumber);
    return {
      protocolVersion,
      message: validatedMessage(object.message),
      providerId: "obi-pl",
      branchId: storeNumber,
      storeNumber,
    };
  });
}

export async function parseMessageRequest(
  request: Request,
): Promise<MessageRequest> {
  if (isMultipartContentType(request.headers.get("Content-Type"))) {
    return parseMultipartRequest(request, true) as Promise<MessageRequest>;
  }
  const value = await readJsonBody(request, MESSAGE_BODY_MAX_BYTES);
  const record = requireRecord(value);
  const protocolVersion = validateProtocolVersion(record.protocolVersion);
  if (protocolVersion === MULTI_ATTACHMENT_ADVISOR_PROTOCOL_VERSION) {
    throw new InvalidRequestError(protocolVersion);
  }

  return withProtocolContext(protocolVersion, () => {
    if (protocolVersion >= PROVIDER_ADVISOR_PROTOCOL_VERSION) {
      const object = exactObjectShape(
        value,
        ["previousResponseId", "message", "providerId", "branchId"],
        ["protocolVersion"],
      );
      const providerId = validateProviderId(object.providerId);
      const branchId = validateBranchId(object.branchId);
      return {
        protocolVersion,
        previousResponseId: boundedString(
          object.previousResponseId,
          MAX_RESPONSE_ID_CHARS,
        ),
        message: validatedMessage(object.message),
        providerId,
        branchId,
        storeNumber: branchId,
      };
    }

    const object = exactObjectShape(
      value,
      ["previousResponseId", "message", "storeNumber"],
      ["protocolVersion"],
    );
    const storeNumber = validateStoreNumber(object.storeNumber);
    return {
      protocolVersion,
      previousResponseId: boundedString(
        object.previousResponseId,
        MAX_RESPONSE_ID_CHARS,
      ),
      message: validatedMessage(object.message),
      providerId: "obi-pl",
      branchId: storeNumber,
      storeNumber,
    };
  });
}

export async function parseContinueRequest(
  request: Request,
): Promise<ContinueRequest> {
  const value = await readJsonBody(request, CONTINUE_BODY_MAX_BYTES);
  const record = requireRecord(value);
  const protocolVersion = validateProtocolVersion(record.protocolVersion);

  return withProtocolContext(protocolVersion, () => {
    if (protocolVersion >= PROVIDER_ADVISOR_PROTOCOL_VERSION) {
      const object = exactObjectShape(
        value,
        [
          "responseId",
          "callId",
          "providerId",
          "branchId",
          "tool",
          "result",
        ],
        ["protocolVersion"],
      );
      if (object.tool !== "find_products" &&
          !(object.tool === LOCATIONS_LOCAL_TOOL_NAME && acceptsLocations(request))) {
        throw new InvalidRequestError(protocolVersion);
      }
      const providerId = validateProviderId(object.providerId);
      const branchId = validateBranchId(object.branchId);
      return {
        protocolVersion,
        responseId: boundedString(
          object.responseId,
          MAX_RESPONSE_ID_CHARS,
        ),
        callId: boundedString(
          object.callId,
          MAX_CALL_ID_CHARS,
        ),
        providerId,
        branchId,
        storeNumber: branchId,
        tool: object.tool as "find_products" | "find_product_locations",
        result: object.tool === LOCATIONS_LOCAL_TOOL_NAME
          ? validateLocationToolResult(object.result)
          : validateToolContinuationResult(object.result, protocolVersion),
      };
    }

    const object = exactObjectShape(
      value,
      ["responseId", "callId", "storeNumber", "tool", "result"],
      ["protocolVersion"],
    );
    if (object.tool !== "find_obi_products" &&
        !(object.tool === LOCATIONS_LOCAL_TOOL_NAME && acceptsLocations(request))) {
      throw new InvalidRequestError(protocolVersion);
    }
    const storeNumber = validateStoreNumber(object.storeNumber);
    return {
      protocolVersion,
      responseId: boundedString(
        object.responseId,
        MAX_RESPONSE_ID_CHARS,
      ),
      callId: boundedString(
        object.callId,
        MAX_CALL_ID_CHARS,
      ),
      providerId: "obi-pl",
      branchId: storeNumber,
      storeNumber,
      tool: object.tool as "find_obi_products" | "find_product_locations",
      result: object.tool === LOCATIONS_LOCAL_TOOL_NAME
        ? validateLocationToolResult(object.result)
        : validateToolContinuationResult(object.result, protocolVersion),
    };
  });
}

export function parseToolArguments(
  raw: string,
  protocolVersion: AdvisorProtocolVersion =
    CURRENT_ADVISOR_PROTOCOL_VERSION,
  toolName?: string,
): VersionedToolArguments {
  let value: unknown;
  try {
    value = JSON.parse(raw) as unknown;
  } catch {
    throw new InvalidRequestError(protocolVersion);
  }

  return withProtocolContext(protocolVersion, () => {
    if (toolName === LOCATIONS_LOCAL_TOOL_NAME) {
      if (protocolVersion === LEGACY_ADVISOR_PROTOCOL_VERSION) throw new InvalidRequestError(protocolVersion);
      return validateLocationToolArguments(value);
    }
    if (protocolVersion === LEGACY_ADVISOR_PROTOCOL_VERSION) {
      return validateLegacyToolArguments(value);
    }
    if (protocolVersion === OBI_GROUPED_ADVISOR_PROTOCOL_VERSION) {
      return validateGroupedToolArguments(value);
    }
    return validateProviderToolArguments(value);
  });
}

export function acceptsLocations(request: Request): boolean {
  return request.headers.get(LOCATIONS_CAPABILITY_HEADER) === LOCATIONS_CAPABILITY_VALUE;
}

function validateLocationToolArguments(value: unknown): LocationToolArguments {
  const obj = exactObject(value, ["providerId", "productId", "locations"]);
  const providerId = validateProviderId(obj.providerId);
  const productId = boundedString(obj.productId, 64);
  if (!/^[A-Za-z0-9._-]+$/.test(productId)) throw new InvalidRequestError();
  if (!Array.isArray(obj.locations) || obj.locations.length > 20) throw new InvalidRequestError();
  const locations = obj.locations.map(item => boundedString(item, 100));
  if (new Set(locations).size !== locations.length) throw new InvalidRequestError();
  return { providerId, productId, locations };
}

function stockInt(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0 || value > 2147483647) {
    throw new InvalidRequestError();
  }
  return value;
}

function locationIds(value: unknown): string[] {
  if (!Array.isArray(value) || value.length > 100) throw new InvalidRequestError();
  const ids = value.map(validateBranchId);
  if (new Set(ids).size !== ids.length) throw new InvalidRequestError();
  return ids;
}

function validateLocationToolResult(value: unknown): LocationToolResult {
  const obj = exactObject(value, [
    "providerId", "productId", "status", "reason", "coverage",
    "checkedIds", "returnedIds", "missingIds", "locations",
    "verifiedAtMillis", "centralStock",
  ]);
  const providerId = validateProviderId(obj.providerId);
  const productId = obj.productId === null ? null : boundedString(obj.productId, 64);
  const status = obj.status;
  if (status !== "verified" && status !== "unavailable" && status !== "rejected") throw new InvalidRequestError();
  const reason = obj.reason === null ? null : boundedString(obj.reason, 64);
  const coverage = obj.coverage;
  if (coverage !== "all_public_locations" && coverage !== "all_other_locations" &&
      coverage !== "requested_subset" &&
      coverage !== "partial" && coverage !== "unknown") throw new InvalidRequestError();
  const checkedIds = locationIds(obj.checkedIds);
  const returnedIds = locationIds(obj.returnedIds);
  const missingIds = locationIds(obj.missingIds);
  if (returnedIds.some(id => !checkedIds.includes(id)) ||
      missingIds.some(id => !checkedIds.includes(id)) ||
      returnedIds.some(id => missingIds.includes(id))) throw new InvalidRequestError();
  if (!Array.isArray(obj.locations) || obj.locations.length > 100) throw new InvalidRequestError();
  const locations = obj.locations.map(item => {
    const row = exactObject(item, ["branchId", "name", "stock"]);
    return {
      branchId: validateBranchId(row.branchId),
      name: boundedString(row.name, 100),
      stock: row.stock === null ? null : stockInt(row.stock),
    };
  });
  if (locations.length !== checkedIds.length ||
      new Set(locations.map(x => x.branchId)).size !== locations.length ||
      locations.some(x => !checkedIds.includes(x.branchId)) ||
      checkedIds.some(id => !locations.some(x => x.branchId === id))) throw new InvalidRequestError();
  // A missing row must remain stock=null; a confirmed row needs a value.
  if (locations.some(x =>
    (missingIds.includes(x.branchId) && x.stock !== null) ||
    (returnedIds.includes(x.branchId) && x.stock === null))) throw new InvalidRequestError();
  if (missingIds.length + returnedIds.length !== checkedIds.length) {
    throw new InvalidRequestError();
  }
  const verifiedAtMillis = obj.verifiedAtMillis === null ? null : stockInt64(obj.verifiedAtMillis);
  const centralStock = obj.centralStock === null ? null : stockInt(obj.centralStock);
  if ((coverage === "all_other_locations" || coverage === "all_public_locations" ||
       coverage === "requested_subset") && missingIds.length > 0) throw new InvalidRequestError();
  if (status !== "verified" &&
      (returnedIds.length > 0 || verifiedAtMillis !== null || centralStock !== null ||
       locations.some(x => x.stock !== null))) throw new InvalidRequestError();
  if (status === "verified" && (productId === null || verifiedAtMillis === null ||
      returnedIds.length === 0)) throw new InvalidRequestError();
  return {
    providerId, productId, status, reason, coverage,
    checkedIds, returnedIds, missingIds, locations, verifiedAtMillis, centralStock,
  };
}
function stockInt64(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) throw new InvalidRequestError();
  return value;
}

function validateLegacyToolArguments(
  value: unknown,
): LegacyToolArguments {
  const object = exactObject(
    value,
    ["query", "storeNumber", "limit"],
  );
  const query = normalizedToolQuery(object.query);
  const storeNumber = validateStoreNumber(object.storeNumber);

  if (
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

function validateGroupedToolArguments(
  value: unknown,
): ToolArguments {
  const object = exactObject(
    value,
    ["storeNumber", "queries"],
  );

  return {
    storeNumber: validateStoreNumber(object.storeNumber),
    queries: validateToolQueries(object.queries),
  };
}

function validateProviderToolArguments(
  value: unknown,
): ProviderToolArguments {
  const object = exactObject(
    value,
    ["providerId", "branchId", "requestedBranch", "queries"],
  );
  return {
    providerId: validateProviderId(object.providerId),
    branchId: validateBranchId(object.branchId),
    requestedBranch:
      object.requestedBranch === null
        ? null
        : boundedString(object.requestedBranch, 100),
    queries: validateToolQueries(object.queries),
  };
}

function validateToolQueries(value: unknown): ToolQuery[] {
  if (
    !Array.isArray(value) ||
    value.length < 1 ||
    value.length > MAX_TOOL_QUERIES
  ) {
    throw new InvalidRequestError();
  }

  const queries = value.map((entry): ToolQuery => {
    const object = exactObject(entry, ["query", "limit"]);
    const query = normalizedToolQuery(object.query);
    if (
      typeof object.limit !== "number" ||
      !Number.isInteger(object.limit) ||
      object.limit < 1 ||
      object.limit > MAX_TOOL_PRODUCTS
    ) {
      throw new InvalidRequestError();
    }
    return {
      query,
      limit: object.limit,
    };
  });

  if (
    queries.reduce((sum, query) => sum + query.limit, 0) >
    MAX_TOOL_PRODUCTS
  ) {
    throw new InvalidRequestError();
  }

  return queries;
}

function validateToolContinuationResult(
  value: unknown,
  protocolVersion: AdvisorProtocolVersion,
): ToolContinuationResult {
  if (!isRecord(value)) {
    throw new InvalidRequestError(protocolVersion);
  }

  if (protocolVersion === LEGACY_ADVISOR_PROTOCOL_VERSION) {
    if ("products" in value) {
      return validateLegacyVerifiedToolResult(value);
    }
    if ("rejection" in value) {
      return validateLegacyRejectedToolResult(value);
    }
    throw new InvalidRequestError(protocolVersion);
  }

  if (protocolVersion >= PROVIDER_ADVISOR_PROTOCOL_VERSION) {
    if ("results" in value) {
      return validateProviderVerifiedToolResult(value);
    }
    if ("rejection" in value) {
      return validateProviderRejectedToolResult(value);
    }
    throw new InvalidRequestError(protocolVersion);
  }

  if ("results" in value) {
    return validateVerifiedToolResult(value);
  }
  if ("rejection" in value) {
    return validateRejectedToolResult(value);
  }
  throw new InvalidRequestError(protocolVersion);
}

function validateLegacyVerifiedToolResult(
  value: unknown,
): LegacyVerifiedToolResult {
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

function validateLegacyRejectedToolResult(
  value: unknown,
): LegacyRejectedToolResult {
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

function validateVerifiedToolResult(
  value: unknown,
): VerifiedToolResult {
  const object = exactObject(
    value,
    ["storeNumber", "results"],
  );
  const storeNumber = validateStoreNumber(object.storeNumber);

  if (
    !Array.isArray(object.results) ||
    object.results.length < 1 ||
    object.results.length > MAX_TOOL_QUERIES
  ) {
    throw new InvalidRequestError();
  }

  const results = object.results.map(validateVerifiedQueryResult);
  const productCount = results.reduce(
    (sum, result) => sum + result.products.length,
    0,
  );
  if (productCount > MAX_TOOL_PRODUCTS) {
    throw new InvalidRequestError();
  }

  return {
    storeNumber,
    results,
  };
}

function validateVerifiedQueryResult(
  value: unknown,
): VerifiedQueryResult {
  const object = exactObject(
    value,
    ["query", "status", "products"],
  );
  const query = normalizedToolQuery(object.query);
  if (!Array.isArray(object.products)) {
    throw new InvalidRequestError();
  }
  const products = object.products.map(validateProduct);

  if (object.status === "verified") {
    if (products.length < 1) {
      throw new InvalidRequestError();
    }
    return {
      query,
      status: "verified",
      products,
    };
  }

  if (
    object.status === "not_found" ||
    object.status === "unavailable"
  ) {
    if (products.length !== 0) {
      throw new InvalidRequestError();
    }
    return {
      query,
      status: object.status,
      products: [],
    };
  }

  throw new InvalidRequestError();
}

function validateProviderVerifiedToolResult(
  value: unknown,
): ProviderVerifiedToolResult {
  const object = exactObject(
    value,
    ["providerId", "branchId", "results"],
  );
  const providerId = validateProviderId(object.providerId);
  const branchId = validateBranchId(object.branchId);

  if (
    !Array.isArray(object.results) ||
    object.results.length < 1 ||
    object.results.length > MAX_TOOL_QUERIES
  ) {
    throw new InvalidRequestError();
  }

  const results = object.results.map(
    validateProviderVerifiedQueryResult,
  );
  const productCount = results.reduce(
    (sum, result) => sum + result.products.length,
    0,
  );
  if (productCount > MAX_TOOL_PRODUCTS) {
    throw new InvalidRequestError();
  }

  return { providerId, branchId, results };
}

function validateProviderVerifiedQueryResult(
  value: unknown,
): ProviderVerifiedQueryResult {
  const object = exactObject(
    value,
    ["query", "status", "products"],
  );
  const query = normalizedToolQuery(object.query);
  if (!Array.isArray(object.products)) {
    throw new InvalidRequestError();
  }
  const products = object.products.map(validateProviderProduct);

  if (object.status === "verified") {
    if (products.length < 1) {
      throw new InvalidRequestError();
    }
    return { query, status: "verified", products };
  }

  if (
    object.status === "not_found" ||
    object.status === "unavailable"
  ) {
    if (products.length !== 0) {
      throw new InvalidRequestError();
    }
    return { query, status: object.status, products: [] };
  }

  throw new InvalidRequestError();
}

function validateProviderRejectedToolResult(
  value: unknown,
): ProviderRejectedToolResult | ProviderLocalToolLimitResult {
  const object = exactObject(
    value,
    ["providerId", "branchId", "queries", "rejection"],
  );
  const providerId = validateProviderId(object.providerId);
  const branchId = validateBranchId(object.branchId);
  const queries = validateToolQueries(object.queries);

  if (object.rejection === "branch_not_authorized") {
    return {
      providerId,
      branchId,
      queries,
      rejection: "branch_not_authorized",
    };
  }

  if (object.rejection === "local_tool_limit_reached") {
    return {
      providerId,
      branchId,
      queries,
      rejection: "local_tool_limit_reached",
    };
  }

  throw new InvalidRequestError();
}

function validateRejectedToolResult(
  value: unknown,
): RejectedToolResult | LocalToolLimitResult {
  const object = exactObject(
    value,
    ["storeNumber", "queries", "rejection"],
  );
  const storeNumber = validateStoreNumber(object.storeNumber);
  const queries = validateToolQueries(object.queries);

  if (object.rejection === "store_not_authorized") {
    return {
      storeNumber,
      queries,
      rejection: "store_not_authorized",
    };
  }

  if (object.rejection === "local_tool_limit_reached") {
    return {
      storeNumber,
      queries,
      rejection: "local_tool_limit_reached",
    };
  }

  throw new InvalidRequestError();
}

function validateProtocolVersion(
  value: unknown,
): AdvisorProtocolVersion {
  if (value === undefined) {
    return OBI_GROUPED_ADVISOR_PROTOCOL_VERSION;
  }
  if (
    typeof value !== "number" ||
    !Number.isInteger(value)
  ) {
    throw new InvalidRequestError();
  }
  if (
    value === LEGACY_ADVISOR_PROTOCOL_VERSION ||
    value === OBI_GROUPED_ADVISOR_PROTOCOL_VERSION ||
    value === PROVIDER_ADVISOR_PROTOCOL_VERSION ||
    value === CURRENT_ADVISOR_PROTOCOL_VERSION ||
    value === MULTI_ATTACHMENT_ADVISOR_PROTOCOL_VERSION
  ) {
    return value;
  }
  throw new UnsupportedProtocolVersionError(value);
}

async function parseMultipartRequest(
  request: Request,
  isMessage: boolean,
): Promise<StartRequest | MessageRequest> {
  // V5 opts into a larger pre-parse bound explicitly; v4's original
  // Content-Length guard still runs before formData().
  const versionHeader = request.headers.get(MULTI_ATTACHMENT_PROTOCOL_HEADER);
  if (
    versionHeader !== null &&
    versionHeader !== String(MULTI_ATTACHMENT_ADVISOR_PROTOCOL_VERSION)
  ) {
    throw new InvalidRequestError();
  }
  const isMulti = versionHeader !== null;
  requireMultipartContentLength(
    request.headers.get("Content-Length"),
    isMulti ? MULTI_MULTIPART_BODY_MAX_BYTES : MULTIPART_BODY_MAX_BYTES,
  );

  const form = await request.formData().catch(() => {
    throw new InvalidRequestError();
  });
  const keys: string[] = [];
  form.forEach((_value, key) => keys.push(key));
  const payloads = form.getAll("payload");
  const parts = form.getAll("attachment");
  if (
    payloads.length !== 1 ||
    parts.length < 1 ||
    parts.length > (isMulti ? MAX_MULTI_ATTACHMENTS : 1) ||
    keys.length !== payloads.length + parts.length ||
    keys.some((key) => key !== "payload" && key !== "attachment") ||
    typeof payloads[0] !== "string" ||
    parts.some((part) => !(part instanceof File))
  ) {
    throw new InvalidRequestError();
  }

  const files = parts as File[];
  let totalBytes = 0;
  let totalTextBytes = 0;
  for (const file of files) {
    if (file.size < 1) throw new InvalidRequestError();
    if (file.size > ATTACHMENT_MAX_BYTES) throw new RequestTooLargeError();
    totalBytes += file.size;
    if (isMulti && isAllowedTextAttachment(sanitizeAttachmentFilename(file.name), file.type)) {
      totalTextBytes += file.size;
      // Separate v5 lightweight-text budget; images/PDFs do not consume it.
      if (totalTextBytes > TEXT_ATTACHMENT_MAX_BYTES) throw new RequestTooLargeError();
    }
  }
  if (totalBytes > (isMulti
    ? MULTI_ATTACHMENT_TOTAL_MAX_BYTES
    : ATTACHMENT_MAX_BYTES)) {
    throw new RequestTooLargeError();
  }

  let value: unknown;
  try {
    value = JSON.parse(payloads[0] as string);
  } catch {
    throw new InvalidRequestError();
  }
  const record = requireRecord(value);
  const protocolVersion = validateProtocolVersion(record.protocolVersion);
  if (protocolVersion !== (isMulti
    ? MULTI_ATTACHMENT_ADVISOR_PROTOCOL_VERSION
    : CURRENT_ADVISOR_PROTOCOL_VERSION)) {
    throw new InvalidRequestError(protocolVersion);
  }
  const required = isMessage
    ? ["previousResponseId", "message", "providerId", "branchId"]
    : ["message", "providerId", "branchId"];
  const object = exactObjectShape(value, required, ["protocolVersion"]);
  const message = validatedAttachmentMessage(object.message);
  const providerId = validateProviderId(object.providerId);
  const branchId = validateBranchId(object.branchId);

  // Fully validate every part before forwarding any of them to Responses.
  const attachments: AdvisorAttachment[] = [];
  for (const file of files) {
    const bytes = new Uint8Array(await file.arrayBuffer());
    const filename = sanitizeAttachmentFilename(file.name);
    if (
      file.type === "image/jpeg" ||
      file.type === "image/png" ||
      file.type === "application/pdf"
    ) {
      validateAttachmentSignature(file.type, bytes, protocolVersion);
      attachments.push({ mimeType: file.type, bytes, filename });
    } else {
      // Text files exist only under the opt-in v5 multipart protocol.
      if (!isMulti || !isAllowedTextAttachment(filename, file.type)) {
        throw new InvalidRequestError(protocolVersion);
      }
      if (bytes.byteLength > TEXT_ATTACHMENT_MAX_BYTES) throw new RequestTooLargeError();
      const textContent = decodeTextAttachment(bytes);
      if (textContent === null) throw new InvalidRequestError(protocolVersion);
      attachments.push({ mimeType: file.type, bytes, filename, textContent });
    }
  }
  const base = {
    protocolVersion,
    message,
    providerId,
    branchId,
    storeNumber: branchId,
    ...(isMulti
      ? { attachments }
      : { attachment: attachments[0] }),
  };
  return isMessage
    ? { ...base, previousResponseId: boundedString(object.previousResponseId, MAX_RESPONSE_ID_CHARS) }
    : base;
}

function requireMultipartContentLength(
  value: string | null,
  maximum: number,
): number {
  if (value === null) {
    throw new InvalidRequestError();
  }
  const normalized = value.trim();
  if (!/^[1-9]\d*$/.test(normalized)) {
    throw new InvalidRequestError();
  }
  const parsed = Number(normalized);
  if (!Number.isSafeInteger(parsed) || parsed > maximum) {
    throw new RequestTooLargeError();
  }
  return parsed;
}

function sanitizeAttachmentFilename(value: string): string {
  const sanitized = value
    .replace(/[\\/]/g, "_")
    .replace(/[\u0000-\u001f\u007f]+/g, "")
    .trim()
    .slice(0, 128);
  return sanitized || "attachment";
}

function validatedAttachmentMessage(value: unknown): string {
  if (typeof value !== "string") throw new InvalidRequestError();
  const normalized = value.trim();
  if (normalized.length > START_MESSAGE_MAX_CHARS) throw new InvalidRequestError();
  return normalized;
}

function validateAttachmentSignature(mime: string, bytes: Uint8Array, protocol: AdvisorProtocolVersion): void {
  const starts = (...signature: number[]) => signature.every((value, index) => bytes[index] === value);
  const valid = mime === "image/jpeg" ? starts(0xff, 0xd8, 0xff)
    : mime === "image/png" ? starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    : mime === "application/pdf" ? starts(0x25, 0x50, 0x44, 0x46, 0x2d)
    : false;
  if (!valid) throw new InvalidRequestError(protocol);
}

function isMultipartContentType(value: string | null): boolean {
  return value?.toLowerCase().startsWith("multipart/form-data;") === true;
}

function requireRecord(
  value: unknown,
): Record<string, unknown> {
  if (!isRecord(value)) {
    throw new InvalidRequestError();
  }
  return value;
}

function exactObjectShape(
  value: unknown,
  requiredKeys: readonly string[],
  optionalKeys: readonly string[] = [],
): Record<string, unknown> {
  if (!isRecord(value)) {
    throw new InvalidRequestError();
  }

  const keys = Object.keys(value);
  if (
    keys.some(
      (key) =>
        !requiredKeys.includes(key) &&
        !optionalKeys.includes(key),
    ) ||
    requiredKeys.some((key) => !(key in value))
  ) {
    throw new InvalidRequestError();
  }

  return value;
}

function withProtocolContext<T>(
  protocolVersion: AdvisorProtocolVersion,
  action: () => T,
): T {
  try {
    return action();
  } catch (error) {
    if (
      error instanceof InvalidRequestError &&
      error.protocolVersion === null
    ) {
      throw new InvalidRequestError(protocolVersion);
    }
    throw error;
  }
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

function validateProviderId(value: unknown): string {
  if (
    typeof value !== "string" ||
    value.length > 64 ||
    !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(value)
  ) {
    throw new InvalidRequestError();
  }
  return value;
}

function validateBranchId(value: unknown): string {
  if (
    typeof value !== "string" ||
    !/^[A-Za-z0-9._-]{1,64}$/.test(value)
  ) {
    throw new InvalidRequestError();
  }
  return value;
}

function validateProductId(value: unknown): string {
  if (
    typeof value !== "string" ||
    !/^[A-Za-z0-9._-]{1,128}$/.test(value)
  ) {
    throw new InvalidRequestError();
  }
  return value;
}

function validateStoreNumber(value: unknown): string {
  if (typeof value !== "string" || !/^\d{3}$/.test(value)) {
    throw new InvalidRequestError();
  }
  return value;
}

function validateProviderProduct(
  value: unknown,
): ProviderVerifiedProduct {
  const object = exactObject(
    value,
    [
      "productId",
      "articleNumber",
      "name",
      "brand",
      "shortDescription",
      "technicalFacts",
      "stock",
      "centralStock",
      "price",
      "priceScope",
    ],
  );
  const productId = validateProductId(object.productId);
  const articleNumber =
    object.articleNumber === null
      ? null
      : boundedString(object.articleNumber, MAX_PRODUCT_NAME_CHARS);
  const name = boundedString(object.name, MAX_PRODUCT_NAME_CHARS);
  const brand =
    object.brand === null
      ? null
      : boundedString(object.brand, MAX_PRODUCT_BRAND_CHARS);
  const shortDescription =
    object.shortDescription === null
      ? null
      : boundedString(
          object.shortDescription,
          MAX_PRODUCT_DESCRIPTION_CHARS,
        );
  const technicalFacts = validateTechnicalFacts(object.technicalFacts);
  const stock = validateNullableStock(object.stock);
  const centralStock = validateNullableStock(object.centralStock);
  const price = validateNullablePrice(object.price);
  const priceScope =
    object.priceScope === null ||
    object.priceScope === "branch" ||
    object.priceScope === "online"
      ? object.priceScope
      : (() => {
          throw new InvalidRequestError();
        })();
  return {
    productId,
    articleNumber,
    name,
    brand,
    shortDescription,
    technicalFacts,
    stock,
    centralStock,
    price,
    priceScope,
  };
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
