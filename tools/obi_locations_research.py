#!/usr/bin/env python3
"""Manual OBI browser research only. No guessed API calls, no production imports.

All analyzers are offline/deterministic; Playwright is imported only by the
manually dispatched workflow. No cookies, tokens or arbitrary scalar values
are written to the safe artifacts.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any
from urllib.parse import parse_qsl, urlsplit

from obi_live_contract_probe import extract_nuxt_payload, parse_nuxt_payload

MAX_RECORDS = 90
MAX_JSON_BYTES = 750_000
MAX_ROWS = 100
MAX_WALK = 1200
MAX_CONTROLS = 80
STORE_KEYS = ("storeNumber", "store_number", "storeId", "store_id",
              "marketNumber", "market_number", "marketId", "market_id")
PRODUCT_KEYS = ("obik", "skuId", "sku", "productId", "product_id",
                "articleNumber", "article_number")
STATE_KEYS = ("stock", "availableStock", "storeStock", "stockQuantity",
              "quantity", "availability", "availabilityStatus",
              "inStock", "isAvailable", "pickupAvailable", "status")
# Generic status may refer to store opening/operational state, and quantity
# may be requested basket quantity. Neither proves product availability.
# Keep both visible as shape candidates, but do not classify from them.
RESEARCH_TRUSTED_AVAILABILITY_FIELDS = {
    "stock", "availableStock", "storeStock", "stockQuantity",
    "availability", "availabilityStatus", "inStock",
    "isAvailable", "pickupAvailable",
}
STATUS_WORDS = {
    "available", "in_stock", "low", "low_stock", "unavailable",
    "not_available", "out_of_stock", "limited", "unknown",
    "dostępny", "dostępne", "niedostępny", "niedostępne", "brak",
}
PRIVATE_FIELD = re.compile(
    r"token|secret|pass|auth|cookie|session|bearer|email|phone|"
    r"address|account|user|device|fingerprint|tracking|visitor|ipaddr|"
    r"postal|postcode|zip|coordinate|latitude|longitude|geoloc|"
    r"(?:^|_)lat(?:$|_)|(?:^|_)lng(?:$|_)|(?:^|_)lon(?:$|_)",
    re.I,
)
SAFE_FIELD = re.compile(r"[A-Za-z][A-Za-z0-9_-]{0,45}\Z")
STORE_NUMBER = re.compile(r"\d{3}\Z")
OBIK_NUMBER = re.compile(r"\d{7}\Z")
KEYWORD = re.compile(
    r"sklep|market|dostęp|odbiór|wybierz|zmień|lokaliz|"
    r"magazyn|stan|rezerw|availability|store|pickup|stock",
    re.I,
)
UNSAFE_ACTION = re.compile(
    r"koszyk|do koszyka|kup teraz|zamów|opłać|płatno|"
    r"rezerwuj|potwierdź|zaloguj|register|checkout|add to cart|buy",
    re.I,
)
SELECT_ACTION = re.compile(r"sklep|market|lokaliz|store|dostęp|odbiór", re.I)
SELECT_VERB = re.compile(r"sprawdź|wybierz|zmień|pokaż|znajdź|select|change|check|find|show", re.I)
ALLOWED_HOST = re.compile(r"(?:[a-z0-9-]+\.)?obi\.pl\Z", re.I)
PATH_WORD = re.compile(r"[a-z][a-z_-]{0,29}\Z")
SAFE_VERSION_PATH_SEGMENTS = {"v1", "v2", "v3", "v4", "v5"}
ALL_RESEARCH_STATES = {"known_zero", "known_positive", "qualitative", "unknown_null", "unknown_missing", "invalid"}


def canonical_stores(kotlin_path: Path) -> dict[str, dict[str, str]]:
    """Read exactly the production OBI_STORES declarations, never own IDs."""
    source = kotlin_path.read_text(encoding="utf-8")
    entries = re.findall(
        r'ObiStoreMetadata\(\s*"(\d{3})"\s*,\s*"((?:\\.|[^"\\])*)"\s*,\s*"((?:\\.|[^"\\])*)"',
        source,
    )
    entries = [
        (number, city.replace('\\"', '"'), address.replace('\\"', '"'))
        for number, city, address in entries
    ]
    if len(entries) < 50 or len({number for number, _, _ in entries}) != len(entries):
        raise ValueError("Canonical OBI_STORES missing, truncated or duplicated")
    return {number: {"city": city, "address": address} for number, city, address in entries}


def safe_field_name(name: Any) -> str | None:
    if not isinstance(name, str) or not SAFE_FIELD.fullmatch(name) or PRIVATE_FIELD.search(name):
        return None
    return name


def trusted_url(url: str) -> bool:
    try:
        p = urlsplit(url)
        return (
            p.scheme == "https" and p.hostname is not None
            and ALLOWED_HOST.fullmatch(p.hostname) is not None
            and p.port in (None, 443) and p.username is None and p.password is None
        )
    except (ValueError, TypeError):
        return False


def safe_path(url: str, obik: str, stores: dict[str, Any]) -> str:
    if not trusted_url(url):
        return "REDACTED_UNTRUSTED_URL"
    path = urlsplit(url).path
    safe = []
    for part in path.split("/")[:20]:
        if part in {obik, *stores} or part in SAFE_VERSION_PATH_SEGMENTS:
            safe.append(part)
        elif PATH_WORD.fullmatch(part) and not PRIVATE_FIELD.search(part):
            safe.append(part)
        else:
            # Unknown IDs, hashes, token/session path components and SKU
            # fragments are redacted rather than treated as arbitrary text.
            safe.append("REDACTED" if part else "")
    return "/".join(safe)[:240]


def safe_identifier(value: Any, obik: str, stores: dict[str, Any]) -> str | None:
    if type(value) not in (int, str):
        return None
    raw = str(value)
    return raw if raw == obik or (STORE_NUMBER.fullmatch(raw) and raw in stores) else None


def safe_parameters(pairs: list[tuple[str, Any]], obik: str, stores: dict[str, Any]) -> dict[str, Any]:
    names: list[str] = []
    safe_values: dict[str, str] = {}
    product_conflict = False
    for key, value in pairs[:80]:
        key = safe_field_name(key)
        if key is None:
            continue
        if key not in names and len(names) < 40:
            names.append(key)
        ident = safe_identifier(value, obik, stores)
        if key in PRODUCT_KEYS and type(value) in (str, int):
            if OBIK_NUMBER.fullmatch(str(value)) and str(value) != obik:
                product_conflict = True
        if ident is not None and key in (*STORE_KEYS, *PRODUCT_KEYS, "store", "market"):
            safe_values[key] = ident
    return {"names": names, "safeValues": safe_values,
            "conflictingProductIdentifier": product_conflict}


def safe_request_body(body: str | None, obik: str, stores: dict[str, Any]) -> dict[str, Any] | None:
    if not body:
        return None
    if len(body) > 30000:
        return {"type": "too_large"}
    try:
        parsed = json.loads(body)
    except (ValueError, TypeError):
        # Form/query bodies may contain personal data; emit no values.
        return {"type": "non_json"}
    if not isinstance(parsed, dict):
        return {"type": type(parsed).__name__}
    return {"type": "object", **safe_parameters(list(parsed.items()), obik, stores)}


def safe_url_request(method: str, url: str, body: str | None,
                     obik: str, stores: dict[str, Any]) -> dict[str, Any] | None:
    if not trusted_url(url):
        return None
    parsed = urlsplit(url)
    return {
        "host": parsed.hostname,
        "method": method if method in ("GET", "POST", "PUT", "PATCH", "DELETE") else "OTHER",
        "path": safe_path(url, obik, stores),
        "query": safe_parameters(parse_qsl(parsed.query, keep_blank_values=True), obik, stores),
        "body": safe_request_body(body, obik, stores),
    }


def stock_state(value: Any) -> dict[str, Any]:
    if value is None:
        return {"state": "unknown_null"}
    if isinstance(value, bool):
        return {"state": "qualitative", "value": value, "valueType": "boolean"}
    if type(value) is int and value >= 0 and value <= 10_000_000:
        return {"state": "known_zero" if value == 0 else "known_positive",
                "value": value, "valueType": "integer"}
    if isinstance(value, str) and value.strip().lower() in STATUS_WORDS:
        return {"state": "qualitative", "value": value.strip().lower(),
                "valueType": "string"}
    return {"state": "invalid", "valueType": type(value).__name__}


def candidate_rows(root: Any, stores: dict[str, Any]) -> list[dict[str, Any]]:
    """Structural only: IDs must match canonical three-digit OBI numbers."""
    rows: list[dict[str, Any]] = []
    queue: list[tuple[Any, int]] = [(root, 0)]
    seen = 0
    while queue and seen < MAX_WALK and len(rows) < MAX_ROWS:
        node, depth = queue.pop(0)
        seen += 1
        if depth > 9:
            continue
        if isinstance(node, dict):
            ids = [(key, node[key]) for key in STORE_KEYS if key in node]
            states = [(key, node[key]) for key in STATE_KEYS if key in node]
            if ids and states:
                key, value = ids[0]
                ident = value if isinstance(value, str) and STORE_NUMBER.fullmatch(value) else None
                if ident is not None and ident in stores and len(ids) == 1:
                    state_key, state_val = states[0]
                    rows.append({
                        "storeNumber": ident, "storeIdField": key,
                        "candidateField": state_key, **stock_state(state_val),
                        "ambiguousFields": len(states) != 1,
                    })
                else:
                    rows.append({"storeNumber": None, "storeIdField": key,
                                 "state": "invalid", "ambiguousIdentity": True})
            for k, v in list(node.items())[:50]:
                if not PRIVATE_FIELD.search(str(k)) and isinstance(v, (dict, list)):
                    queue.append((v, depth + 1))
        elif isinstance(node, list):
            for value in node[:MAX_ROWS]:
                if isinstance(value, (dict, list)):
                    queue.append((value, depth + 1))
    return rows


def product_identity(record: dict[str, Any], obik: str) -> str:
    if record.get("host") is None or not ALLOWED_HOST.fullmatch(record["host"]):
        return "UNKNOWN"
    if record.get("status") != 200:
        return "UNKNOWN"
    # Only product identity fields in the response root count; avoid unrelated
    # recommended product objects, global directories and array position.
    shape = record.get("shape") or {}
    identity = shape.get("rootProductId")
    if identity is not None:
        return "RESPONSE_ROOT_PRODUCT" if identity == obik else "CONFLICT"
    if any(params.get("conflictingProductIdentifier") for params in (
            record.get("query") or {}, record.get("body") or {}
    )):
        return "CONFLICT"
    path = record.get("path", "")
    pieces = path.strip("/").split("/")
    if obik in pieces and any(p in {"p", "product", "products", "article", "articles", "sku", "availability"} for p in pieces):
        return "SINGULAR_REQUEST_PRODUCT_PATH"
    for params in (record.get("query") or {}, record.get("body") or {}):
        for name, value in (params.get("safeValues") or {}).items():
            if name in PRODUCT_KEYS and value == obik:
                return "EXACT_REQUEST_PRODUCT_ID"
    return "UNKNOWN"


STRUCTURAL_AVAILABILITY_KEY = re.compile(
    r"availab|stock|invent|quantity|qty|pickup|fulfil|state|status",
    re.I,
)
QUANTITY_KEY = re.compile(
    r"stock|quantity|qty|inventory|inventor", re.I,
)
OBSERVED_SP_PATH = re.compile(
    r"/api/pdp/v1/availability/sp/(\d{7})\Z"
)
OBSERVED_HD_PATH = re.compile(
    r"/api/pdp/v1/availability/hd/(\d{7})\Z"
)


def scalar_type(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if type(value) is int:
        return "integer"
    if type(value) is float:
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, dict):
        return "object"
    if isinstance(value, list):
        return "list"
    return "other"


def structural_availability_container(
    node: Any, stores: dict[str, Any],
) -> dict[str, Any]:
    """Bounded, non-authoritative schema discovery for run-2 live containers.

    Only explicit canonical three-digit ID strings, genuine numeric
    quantities with recognized quantity key names and allowlisted
    qualitative enums can ever be exposed as VALUES. This diagnostic
    does NOT establish any store's availability or class A-E.
    """
    info: dict[str, Any] = {
        "containerType": scalar_type(node),
        "containerLength": len(node) if isinstance(node, list) else None,
        "containerKeys": [
            key for key in list(node)[:40] if safe_field_name(key) is not None
        ] if isinstance(node, dict) else [],
        "nestingShape": [],
        "representativeObjectKeys": [],
        "canonicalStoreIdFields": [],
        "availabilityCandidateFields": [],
        "structuralOnly": True,
    }
    queue: list[tuple[Any, int]] = [(node, 0)]
    visited = 0
    seen_ids: set[tuple[str, str]] = set()
    seen_fields: set[tuple[str, str]] = set()
    while queue and visited < 180:
        value, depth = queue.pop(0)
        visited += 1
        if depth > 7:
            continue
        if isinstance(value, list):
            if len(info["nestingShape"]) < 12:
                info["nestingShape"].append({
                    "depth": depth, "type": "list", "length": min(len(value), MAX_ROWS),
                })
            queue.extend((child, depth + 1) for child in value[:35]
                         if isinstance(child, (list, dict)))
        elif isinstance(value, dict):
            keys = [
                key for key in list(value)[:45] if safe_field_name(key) is not None
            ]
            if len(info["nestingShape"]) < 12:
                info["nestingShape"].append({
                    "depth": depth, "type": "object",
                    "keys": keys[:16],
                })
            if len(info["representativeObjectKeys"]) < 6:
                info["representativeObjectKeys"].append(keys[:30])
            for key in keys:
                child = value[key]
                if isinstance(child, (dict, list)):
                    queue.append((child, depth + 1))
                    continue
                if type(child) is str and child in stores and STORE_NUMBER.fullmatch(child):
                    identity = (key, child)
                    if identity not in seen_ids and len(info["canonicalStoreIdFields"]) < 25:
                        seen_ids.add(identity)
                        info["canonicalStoreIdFields"].append({
                            "field": key, "storeNumber": child,
                        })
                if STRUCTURAL_AVAILABILITY_KEY.search(key):
                    kind = scalar_type(child)
                    field_key = (key, kind)
                    if field_key in seen_fields or len(info["availabilityCandidateFields"]) >= 28:
                        continue
                    seen_fields.add(field_key)
                    evidence: dict[str, Any] = {
                        "field": key, "scalarType": kind,
                    }
                    if (QUANTITY_KEY.search(key) and
                            type(child) is int and 0 <= child <= 10_000_000):
                        evidence["numericCandidate"] = child
                    elif isinstance(child, (bool, str)):
                        typed = stock_state(child)
                        if typed["state"] == "qualitative":
                            evidence["qualitativeCandidate"] = typed["value"]
                    info["availabilityCandidateFields"].append(evidence)
    return info


def response_shape(data: Any, obik: str, stores: dict[str, Any],
                   request_path: str = "") -> dict[str, Any]:
    if isinstance(data, dict):
        root_names = [key for key in data if safe_field_name(key) is not None][:35]
        ids = [str(data[key]) for key in PRODUCT_KEYS if key in data
               and type(data[key]) in (str, int) and OBIK_NUMBER.fullmatch(str(data[key]))]
        root_id = ids[0] if len(set(ids)) == 1 else ("CONFLICT" if ids else None)
    else:
        root_names, root_id = [], None
    # Availability payloads must derive rows solely from their exact named
    # product-owned container, not a stray recommendation/global subtree.
    row_owner = data
    if isinstance(data, dict) and OBSERVED_SP_PATH.fullmatch(request_path):
        row_owner = data.get("pickupStores") if (
            OBSERVED_SP_PATH.fullmatch(request_path).group(1) == obik
        ) else None
    elif isinstance(data, dict) and OBSERVED_HD_PATH.fullmatch(request_path):
        row_owner = data.get("deliveryDataPerSeller") if (
            OBSERVED_HD_PATH.fullmatch(request_path).group(1) == obik
        ) else None
    rows = candidate_rows(row_owner, stores) if row_owner is not None else []
    result = {"rootType": type(data).__name__, "rootFields": root_names,
              "rootProductId": root_id, "storeRows": rows[:MAX_ROWS],
              "storeRowCount": len(rows)}
    # Distinct observed frontend routes: pickup and delivery are not merged.
    if isinstance(data, dict):
        if (OBSERVED_SP_PATH.fullmatch(request_path) and
                OBSERVED_SP_PATH.fullmatch(request_path).group(1) == obik and
                "pickupStores" in data):
            result["pickupStoresStructure"] = structural_availability_container(
                data["pickupStores"], stores
            )
        if (OBSERVED_HD_PATH.fullmatch(request_path) and
                OBSERVED_HD_PATH.fullmatch(request_path).group(1) == obik and
                "deliveryDataPerSeller" in data):
            result["deliveryDataPerSellerStructure"] = structural_availability_container(
                data["deliveryDataPerSeller"], stores
            )
    return result


def verified_rows(record: dict[str, Any], stores: dict[str, Any]) -> dict[str, dict[str, Any]]:
    rows = (record.get("shape") or {}).get("storeRows") or []
    if not rows or len(rows) > MAX_ROWS:
        return {}
    by_id = {}
    for row in rows:
        ident = row.get("storeNumber")
        if (ident not in stores or ident in by_id
                or row.get("ambiguousFields") or row.get("ambiguousIdentity")
                or row.get("candidateField") not in RESEARCH_TRUSTED_AVAILABILITY_FIELDS
                or row.get("state") not in (
                    "known_zero", "known_positive", "qualitative", "unknown_null"
                )):
            return {}
        by_id[ident] = row
    return by_id


def observed_availability_response(record: dict[str, Any], obik: str) -> bool:
    """Frontend availability evidence is valid BEFORE or AFTER a UI click.

    Exact observed sp/hd paths may be initial-page prefetches. Other traffic
    needs a real availability-action phase AND an availability-specific
    product route. This is not inferred from click timing alone: the
    independent product_identity gate still applies to every response.
    """
    if product_identity(record, obik) in ("UNKNOWN", "CONFLICT"):
        return False
    path = record.get("path") or ""
    sp = OBSERVED_SP_PATH.fullmatch(path)
    hd = OBSERVED_HD_PATH.fullmatch(path)
    if (sp or hd) and (sp or hd).group(1) == obik:
        return True
    if not (record.get("action") or "").startswith("availability:"):
        return False
    # Keep existing synthetic discovery coverage of explicitly product-bound
    # availability and store-list routes; generic/CMS/teasers never qualify.
    if re.fullmatch(
        r"/api/products/" + re.escape(obik)
        + r"/(?:stores|availability|stock|pickup)(?:/[^/]*)?",
        path,
    ):
        return True
    # Other newly observed response paths remain research-inconclusive until
    # a later verified run adds precise route semantics.
    return False


def classify_contract(observations: list[dict[str, Any]], stores: dict[str, Any],
                      obik: str, initial_nuxt: dict[str, Any] | None = None) -> dict[str, Any]:
    if not stores:
        return {"type": "F_INCONCLUSIVE", "reason": "NO_CANONICAL_STORES"}
    nuxt = initial_nuxt or {}
    preloaded = nuxt.get("verifiedProductOwnedRows") or []
    if len(preloaded) >= 2 and nuxt.get("productIdentityVerified") is True:
        ids = [row.get("storeNumber") for row in preloaded]
        if (len(ids) == len(set(ids))
                and all(i in stores for i in ids)
                and all(row.get("state") in (
                    "known_zero", "known_positive", "qualitative"
                ) and not row.get("ambiguousFields")
                and row.get("candidateField") in RESEARCH_TRUSTED_AVAILABILITY_FIELDS
                for row in preloaded)):
            return {"type": "C_FRONTEND_PRELOADED", "reason": "EXACT_PRODUCT_OWNED_NUXT_ROWS",
                    "observedStoreCount": len(ids)}
    scoped = []
    for record in observations:
        if not observed_availability_response(record, obik):
            continue
        mapped = verified_rows(record, stores)
        if mapped:
            scoped.append((record, mapped))
    for record, mapped in scoped:
        if len(mapped) == len(stores) and set(mapped) == set(stores) and all(
            row["state"] in ("known_zero", "known_positive", "qualitative")
            for row in mapped.values()
        ):
            return {"type": "A_ONE_SHOT_ALL_STORES",
                    "reason": "PRODUCT_BOUND_COMPLETE_CANONICAL_STORE_RESPONSE",
                    "observedStoreCount": len(mapped)}
    for record, mapped in scoped:
        usable = [row for row in mapped.values()
                  if row["state"] in ("known_zero", "known_positive", "qualitative")]
        if len(usable) >= 2:
            return {"type": "B_ONE_SHOT_SUBSET",
                    "reason": "PRODUCT_BOUND_VERIFIED_SUBSET_OMISSIONS_UNKNOWN",
                    "observedStoreCount": len(mapped)}
    if 2 <= len(scoped) <= 5:
        usable_by_record = [
            {store for store, row in mapped.items()
             if row["state"] in ("known_zero", "known_positive", "qualitative")}
            for _, mapped in scoped
        ]
        combined = set().union(*usable_by_record)
        if len(combined) >= 2:
            single_scoped = {
                next(iter(ids)) for ids in usable_by_record if len(ids) == 1
            }
            if len(single_scoped) >= 3:
                return {"type": "E_PER_STORE_FANOUT",
                        "reason": "THREE_DISTINCT_PRODUCT_BOUND_STORE_REQUESTS",
                        "observedStoreCount": len(combined)}
            return {"type": "D_MULTI_REQUEST_BOUNDED",
                    "reason": "BOUNDED_PRODUCT_BOUND_STORE_RESPONSES",
                    "observedStoreCount": len(combined)}
    return {"type": "F_INCONCLUSIVE", "reason": "INSUFFICIENT_PRODUCT_STORE_EVIDENCE"}


def _nuxt_resolve(root: Any, value: Any, limit: int = 5) -> Any:
    if not isinstance(root, list):
        return value
    visited: set[int] = set()
    while limit > 0 and type(value) is int and 0 <= value < len(root) and value not in visited:
        visited.add(value)
        value = root[value]
        limit -= 1
        if isinstance(value, list) and len(value) == 2 and value[0] in ("Ref", "ShallowRef"):
            value = value[1]
            continue
        break
    return value


def _nuxt_materialize(root: Any, node: Any, depth: int = 0,
                      seen: frozenset[int] = frozenset(),
                      flattened: bool = False) -> Any:
    """Expand flattened references but preserve inline literal 0 and booleans.

    In Nuxt's flattened representation, fields of objects stored as top-level
    list entries refer to indices. Inline nested JSON values are literal
    unless reached through such a reference. Never treat a scalar *result*
    of dereferencing as another reference.
    """
    if depth > 8:
        return None
    if flattened and isinstance(root, list) and type(node) is int:
        if node not in range(len(root)) or node in seen:
            return None
        value = root[node]
        if not isinstance(value, (dict, list)):
            return value
        return _nuxt_materialize(root, value, depth + 1, seen | {node},
                                 flattened=True)
    if isinstance(node, list):
        if (len(node) == 2 and node[0] in ("Ref", "ShallowRef")
                and type(node[1]) is int):
            return _nuxt_materialize(root, node[1], depth + 1, seen,
                                     flattened=True)
        return [
            _nuxt_materialize(root, item, depth + 1, seen, flattened)
            for item in node[:MAX_ROWS]
        ]
    if isinstance(node, dict):
        return {
            key: _nuxt_materialize(root, value, depth + 1, seen, flattened)
            for key, value in list(node.items())[:45]
            if safe_field_name(key) is not None
        }
    return node



def product_owned_store_ids(node: Any, stores: dict[str, Any]) -> list[str]:
    """Read only canonical explicit IDs in product-owned store subtrees."""
    ids: set[str] = set()
    todo = [(node, 0)]
    inspected = 0
    while todo and inspected < 250:
        value, depth = todo.pop(0)
        inspected += 1
        if depth > 7:
            continue
        if isinstance(value, dict):
            for key in STORE_KEYS:
                ident = value.get(key)
                if type(ident) is str and ident in stores:
                    ids.add(ident)
            for child in value.values():
                if isinstance(child, (dict, list)):
                    todo.append((child, depth + 1))
        elif isinstance(value, list):
            todo.extend((child, depth + 1) for child in value[:MAX_ROWS]
                        if isinstance(child, (dict, list)))
    return sorted(ids)


def inspect_initial_nuxt(html: str, obik: str, stores: dict[str, Any],
                         selected_store: str | None = None) -> dict[str, Any]:
    """Only attach availability fields to an exact skuId-owning object."""
    result = {"productIdentityVerified": False, "productOwnerCount": 0,
              "selectedStoreVerified": False, "selectedStoreIdFromProductContext": "UNKNOWN",
              "productOwnedKeys": [], "verifiedProductOwnedRows": [],
              "preloadedClassification": "UNKNOWN"}
    try:
        root = parse_nuxt_payload(extract_nuxt_payload(html))
    except ValueError:
        return result
    owners = []
    nodes = root if isinstance(root, list) else [root]
    for item in nodes[:6000]:
        if not isinstance(item, dict):
            continue
        if any(str(_nuxt_resolve(root, item.get(k))) == obik
               for k in ("skuId", "obik", "productId") if k in item):
            owners.append(item)
    result["productOwnerCount"] = len(owners)
    result["productIdentityVerified"] = len(owners) == 1
    if len(owners) != 1:
        return result
    owner = owners[0]
    result["productOwnedKeys"] = [
        k for k in owner if safe_field_name(k) is not None
    ][:45]
    store_context = owner.get("store", owner.get("selectedStore"))
    if store_context is not None:
        decoded_store = _nuxt_materialize(
            root, store_context, flattened=type(store_context) is int
        )
        store_ids = product_owned_store_ids(decoded_store, stores)
        if len(store_ids) == 1:
            result["selectedStoreIdFromProductContext"] = store_ids[0]
            result["selectedStoreVerified"] = (
                selected_store is not None and store_ids[0] == selected_store
            )
    owned = []
    for key, value in owner.items():
        if re.search(r"stock|store|availability|inventory|pickup|fulfillment|market", key, re.I):
            # Nuxt's product-owned arrays/objects can contain references
            # at each level; do not mistake raw indices for stock or IDs.
            nested = _nuxt_materialize(root, value, flattened=type(value) is int)
            owned.extend(candidate_rows(nested, stores))
    result["verifiedProductOwnedRows"] = owned[:MAX_ROWS]
    ids = [row.get("storeNumber") for row in owned]
    if (len(owned) >= 2 and len(ids) == len(set(ids))
            and all(i in stores for i in ids)
            and all(row.get("state") in (
                "known_zero", "known_positive", "qualitative"
            ) and not row.get("ambiguousFields")
            and row.get("candidateField") in RESEARCH_TRUSTED_AVAILABILITY_FIELDS
            for row in owned)):
        result["preloadedClassification"] = "PRODUCT_OWNED_MULTI_STORE_CANDIDATE"
    elif owned:
        result["preloadedClassification"] = "EXACT_PRODUCT_SINGLE_OR_AMBIGUOUS"
    else:
        result["preloadedClassification"] = "NO_PRODUCT_OWNED_MULTI_STORE_ROWS"
    return result


def safe_control_text(raw: str | None) -> str:
    text = re.sub(r"\s+", " ", raw or "").strip()
    if len(text) > 220 or not KEYWORD.search(text):
        return ""
    text = re.sub(r"[\w.+-]+@[\w.-]+\.\w+", "[REDACTED]", text)
    text = re.sub(r"\+?\d[\d\s-]{8,}\d", "[REDACTED]", text)
    if re.search(r"token|cookie|account|session|password", text, re.I):
        return ""
    return text[:120]


def discover_controls(page: Any) -> list[dict[str, Any]]:
    """Bounded control metadata; arbitrary product copy/addresses stay private."""
    controls = []
    try:
        nodes = page.locator("button,[role='button'],a[href],select")
        for index in range(min(nodes.count(), 220)):
            node = nodes.nth(index)
            if not node.is_visible():
                continue
            raw = node.get_attribute("aria-label") or node.inner_text(timeout=400)
            hint = safe_modal_label(raw)
            if hint is None:
                continue
            role = node.get_attribute("role") or node.evaluate(
                "(e) => e.tagName.toLowerCase()"
            )
            try:
                nearby = safe_modal_label(
                    node.locator("xpath=..").inner_text(timeout=350)
                )
            except Exception:
                nearby = None
            controls.append({
                "index": index,
                "role": role if role in ("button", "a", "select", "link") else "other",
                "label": (OBSERVED_AVAILABILITY_BUTTON if
                          normalized_inner_text(raw) == OBSERVED_AVAILABILITY_BUTTON
                          else None),
                "labelKeywords": hint["keywordTags"],
                "nearbyKeywords": nearby["keywordTags"] if nearby else [],
                "visible": True, "enabled": node.is_enabled(),
            })
            if len(controls) >= MAX_CONTROLS:
                break
    except Exception:
        pass
    return controls

OBSERVED_AVAILABILITY_BUTTON = "Sprawdź dostępność w innym sklepie"


def normalized_inner_text(raw: str | None) -> str:
    return re.sub(r"\s+", " ", raw or "").strip()


def resolve_exact_availability_opener(page: Any) -> tuple[Any | None, str]:
    """Run #3: observed native button[PdpLink] with EXACT visible innerText.

    SVG <title>arrow-right</title> can change the accessible name, so
    role/name lookups are not an exact representation of the live DOM.
    Candidate order/position, recommendation text and generic matches are
    never used to authorize a click.
    """
    try:
        candidates = page.locator('button[data-component="PdpLink"]')
        count = candidates.count()
        if count > 180:
            return None, "BUTTON_CANDIDATE_LIMIT_EXCEEDED"
        matching = []
        for index in range(count):
            button = candidates.nth(index)
            # This selector requires native button; don't relax to role.
            if normalized_inner_text(button.inner_text(timeout=400)) != OBSERVED_AVAILABILITY_BUTTON:
                continue
            matching.append(button)
        if not matching:
            return None, "EXACT_BUTTON_MISSING"
        if len(matching) != 1:
            return None, "EXACT_BUTTON_AMBIGUOUS"
        button = matching[0]
        if not button.is_visible():
            return None, "EXACT_BUTTON_HIDDEN"
        if not button.is_enabled():
            return None, "EXACT_BUTTON_DISABLED"
        return button, "EXACT_BUTTON_RESOLVED"
    except Exception:
        return None, "BUTTON_RESOLUTION_FAILED"



# Only the already observed harmless PDP button can be clicked with DOM
# fallback. This JavaScript rechecks the element *inside* the browser before
# dispatch and never clicks coordinates, a child node, or another selector.
DOM_EXACT_AVAILABILITY_CLICK = r"""
(element, expected) => {
    if (!element.isConnected || element.tagName !== 'BUTTON' ||
        element.getAttribute('data-component') !== 'PdpLink' ||
        (element.innerText || '').replace(/\s+/g, ' ').trim() !== expected ||
        element.disabled || element.getAttribute('aria-disabled') === 'true' ||
        element.getClientRects().length === 0)
        return false;
    const style = window.getComputedStyle(element);
    if (style.visibility === 'hidden' || style.display === 'none')
        return false;
    element.click();
    return true;
}
"""


def is_playwright_click_timeout(exc: Exception) -> bool:
    """Only an actual Playwright click actionability timeout may fall back."""
    cls = type(exc)
    return cls.__name__ == "TimeoutError" and cls.__module__.startswith("playwright.")


def begin_availability_open_phase(action: list[str]) -> None:
    """Set network attribution BEFORE normal or research-only DOM click."""
    action[0] = "availability:open"


def click_safe_control(page: Any, controls: list[dict[str, Any]]) -> dict[str, Any]:
    """Normal click first; strictly identical, revalidated native DOM fallback."""
    # Diagnostic controls never authorize or select the click target.
    button, category = resolve_exact_availability_opener(page)
    if button is None:
        return {"status": category}
    try:
        button.scroll_into_view_if_needed(timeout=2500)
    except Exception:
        # Never fall back on a scrolling failure, even a timeout.
        return {"status": "CLICK_FAILED", "failureCategory": "SCROLL_FAILED"}
    try:
        button.click(timeout=5000)  # Playwright actionability, never force.
        mode = "NORMAL_CLICK"
    except Exception as exc:
        if not is_playwright_click_timeout(exc):
            return {
                "status": "CLICK_FAILED",
                "failureCategory": (
                    "TIMEOUT" if type(exc).__name__ == "TimeoutError"
                    else "PLAYWRIGHT_CLICK_ERROR"
                ),
            }
        # A timed-out action can leave a stale locator or changed DOM.
        # Resolve and verify the SAME harmless button a second time.
        fresh, fresh_status = resolve_exact_availability_opener(page)
        if fresh is None:
            return {
                "status": "CLICK_FAILED",
                "failureCategory": "ACTIONABILITY_TIMEOUT",
                "fallbackResolution": fresh_status,
            }
        try:
            dispatched = fresh.evaluate(
                DOM_EXACT_AVAILABILITY_CLICK, OBSERVED_AVAILABILITY_BUTTON
            )
            if dispatched is not True:
                return {
                    "status": "CLICK_FAILED",
                    "failureCategory": "DOM_TARGET_CHANGED",
                }
            mode = "DOM_CLICK_AFTER_ACTIONABILITY_TIMEOUT"
        except Exception:
            return {
                "status": "CLICK_FAILED",
                "failureCategory": "DOM_CLICK_FAILED",
            }
    try:
        page.wait_for_timeout(1200)  # bounded UI/XHR reaction window
    except Exception:
        return {
            "status": "CLICK_FAILED",
            "failureCategory": "POST_CLICK_OBSERVATION_FAILED",
            "interactionMode": mode,
        }
    # Dispatch is provisional; the caller MUST independently observe a
    # relevant browser request, response or safe new availability UI.
    return {
        "status": "CLICK_DISPATCHED",
        "interactionMode": mode,
        "selectorEvidence": "EXACT_OBSERVED_PDP_LINK_INNER_TEXT",
    }


def finalize_open_control(
    dispatched: dict[str, Any], evidence: dict[str, Any],
) -> dict[str, Any]:
    """A native DOM click is not an observed availability action on its own."""
    if dispatched.get("status") != "CLICK_DISPATCHED":
        return dispatched
    result = dict(dispatched)
    if evidence.get("effectObserved") is True:
        result["status"] = "CLICKED"
    elif dispatched.get("interactionMode") == "DOM_CLICK_AFTER_ACTIONABILITY_TIMEOUT":
        result["status"] = "DOM_CLICK_NO_OBSERVABLE_EFFECT"
    else:
        result["status"] = "NORMAL_CLICK_NO_OBSERVABLE_EFFECT"
    return result


def availability_button_expanded(page: Any) -> bool | None:
    """Only the exact opener aria-expanded boolean; never log element text."""
    button, status = resolve_exact_availability_opener(page)
    if status != "EXACT_BUTTON_RESOLVED" or button is None:
        return None
    try:
        value = button.get_attribute("aria-expanded")
        return True if value == "true" else False if value == "false" else None
    except Exception:
        return None


def relevant_open_network_record(record: dict[str, Any], obik: str) -> bool:
    """An action-phase request can prove an event, not inventory coverage.

    Delayed recommendations/CMS/teaser traffic never proves the click worked.
    Product availability *classification* remains separately identity gated.
    """
    if record.get("action") != "availability:open":
        return False
    host = record.get("host")
    if not isinstance(host, str) or not ALLOWED_HOST.fullmatch(host):
        return False
    path = record.get("path") or ""
    if any(x in path.lower() for x in (
        "recommend", "teaser", "cms", "tracking", "analytics", "promo"
    )):
        return False
    parts = path.lower().strip("/").split("/")
    return (
        bool(OBSERVED_SP_PATH.fullmatch(path) and
             OBSERVED_SP_PATH.fullmatch(path).group(1) == obik)
        or bool(OBSERVED_HD_PATH.fullmatch(path) and
                OBSERVED_HD_PATH.fullmatch(path).group(1) == obik)
        or bool(set(parts) & {"availability", "pickup", "stores", "store",
                              "locator", "market", "markets", "locations"})
    )


def verify_open_observable_effect(
    before_ui: dict[str, Any], after_ui: dict[str, Any],
    before_expanded: bool | None, after_expanded: bool | None,
    new_requests: list[dict[str, Any]],
    new_responses: list[dict[str, Any]],
    obik: str,
) -> dict[str, Any]:
    """Safe, bounded evidence required AFTER either click dispatch."""
    relevant_requests = [
        item for item in new_requests
        if relevant_open_network_record(item, obik)
    ]
    availability_responses = [
        item for item in new_responses
        if item.get("action") == "availability:open"
        and observed_availability_response(item, obik)
    ]
    dialog_opened = (
        after_ui.get("visibleDialogCount", 0) >
        before_ui.get("visibleDialogCount", 0)
    )
    store_ui_changed = (
        (after_ui.get("storeSearchInputObserved") is True and
         before_ui.get("storeSearchInputObserved") is not True)
        or (after_ui.get("candidateStoreRowsCount", 0) >
            before_ui.get("candidateStoreRowsCount", 0))
    )
    expanded = before_expanded is False and after_expanded is True
    observed = bool(relevant_requests or availability_responses
                    or dialog_opened or store_ui_changed or expanded)
    return {
        "effectObserved": observed,
        "newRelevantRequests": len(relevant_requests),
        "newProductAvailabilityResponses": len(availability_responses),
        "newDialog": dialog_opened,
        "newStoreSelectionUi": store_ui_changed,
        "availabilityControlExpanded": expanded,
    }


# Only these repository-driven keywords/known public phrases may be
# reflected in a safe structural summary. Never report arbitrary DOM text.
POST_OPEN_KEYWORDS = {
    "store": re.compile(r"sklep|market|store", re.I),
    "location": re.compile(r"lokaliz|miast|location|kod pocztow", re.I),
    "availability": re.compile(r"dostęp|availability|stock|stan", re.I),
    "pickup": re.compile(r"odbiór|pickup|rezerw", re.I),
    "search": re.compile(r"szukaj|wyszuk|wpisz|search|find", re.I),
}
SAFE_MODAL_PHRASES = {
    "sprawdź dostępność w innym sklepie",
    "wybierz sklep",
    "zmień sklep",
    "znajdź sklep",
    "wyszukaj sklep",
    "szukaj sklepu",
    "dostępność w sklepach",
    "wybierz lokalizację",
    "wpisz miasto",
    "wpisz kod pocztowy",
    "miasto lub kod pocztowy",
}
DATA_STORE_ATTRIBUTE_NAMES = (
    "data-store-number", "data-store-id", "data-market-id",
    "data-market-number", "data-branch-id",
)
DATA_STORE_ATTRIBUTE_RE = re.compile(
    r"data-[a-z0-9-]{1,46}\Z"
)


def safe_modal_label(text: str | None) -> dict[str, Any] | None:
    """Whitelist semantic tags, never arbitrary location/customer strings."""
    normalized = normalized_inner_text(text)
    if len(normalized) > 250:
        normalized = normalized[:250]
    tags = [tag for tag, pattern in POST_OPEN_KEYWORDS.items()
            if pattern.search(normalized)]
    if not tags:
        return None
    safe: dict[str, Any] = {"keywordTags": tags, "length": len(normalized)}
    if normalized.casefold() in SAFE_MODAL_PHRASES:
        safe["knownPhrase"] = normalized.casefold()
    return safe


def safe_store_attribute_names(names: list[str]) -> list[str]:
    return [
        name for name in names[:45]
        if isinstance(name, str)
        and DATA_STORE_ATTRIBUTE_RE.fullmatch(name)
        and re.search(r"store|market|branch|location", name, re.I)
        and not PRIVATE_FIELD.search(name)
    ][:12]


def normalized_public_directory_text(raw: str) -> str:
    return normalized_inner_text(raw).casefold()


def identify_canonical_dom_store(
    raw_text: str, data_values: dict[str, str | None],
    stores: dict[str, dict[str, str]],
) -> dict[str, Any]:
    """Pure fail-closed identity, no city-only inference or raw text output."""
    text = normalized_public_directory_text(raw_text[:550])
    matches: set[str] = set()
    source: set[str] = set()
    for key, value in data_values.items():
        if key in DATA_STORE_ATTRIBUTE_NAMES and type(value) is str and value in stores:
            matches.add(value)
            source.add("EXPLICIT_CANONICAL_DATA_ID")
    for market_id, meta in stores.items():
        city = normalized_public_directory_text(meta["city"])
        address = normalized_public_directory_text(meta["address"])
        # Both required; city alone is ambiguous (Kraków, Łódź, etc.).
        if city and address and city in text and address in text:
            matches.add(market_id)
            source.add("EXACT_CANONICAL_DIRECTORY_ADDRESS")
    if len(matches) == 1:
        return {
            "storeNumber": next(iter(matches)),
            "identityStatus": "VERIFIED",
            "identityEvidence": sorted(source),
        }
    return {
        "storeNumber": None,
        "identityStatus": "AMBIGUOUS" if matches else "UNKNOWN",
    }


def visible_modal_scopes(page: Any) -> list[Any]:
    scopes = []
    try:
        nodes = page.locator('dialog,[role="dialog"],[aria-modal="true"]')
        for i in range(min(nodes.count(), 20)):
            node = nodes.nth(i)
            if node.is_visible():
                scopes.append(node)
    except Exception:
        return []
    return scopes


def inspect_post_open_ui(
    page: Any, stores: dict[str, dict[str, str]],
) -> dict[str, Any]:
    """Visible scoped DOM structure, with only allowlisted public identifiers.

    A single visible dialog creates a safe market-row interaction boundary.
    Without one, inspect bounded page structure but do NOT authorize clicks.
    Neither arbitrary modal text nor addresses/coordinates are persisted.
    """
    dialogs = visible_modal_scopes(page)
    diagnostic: dict[str, Any] = {
        "visibleDialogCount": len(dialogs),
        "visibleRegionCount": 0,
        "scope": "ONE_VISIBLE_DIALOG" if len(dialogs) == 1 else "UNSCOPED_PAGE",
        "storeClickScopeVerified": len(dialogs) == 1,
        "dialogRoles": [],
        "headings": [],
        "visibleInputCount": 0,
        "inputs": [],
        "visibleCandidateControlCount": 0,
        "canonicalStoreCandidates": [],
        "candidateStoreRowsCount": 0,
        "dataAttributeNames": [],
        "storeSearchInputObserved": False,
        "structuralOnly": True,
    }
    try:
        for dialog in dialogs[:8]:
            role = dialog.get_attribute("role")
            diagnostic["dialogRoles"].append(
                role if role in ("dialog", "region") else "dialog"
            )
        regions = page.locator('[role="region"]')
        diagnostic["visibleRegionCount"] = sum(
            bool(regions.nth(i).is_visible())
            for i in range(min(regions.count(), 35))
        )
        scope = dialogs[0] if len(dialogs) == 1 else page
        headings = scope.locator('h1,h2,h3,h4,[role="heading"],label')
        for i in range(min(headings.count(), 50)):
            item = headings.nth(i)
            if not item.is_visible():
                continue
            summary = safe_modal_label(item.inner_text(timeout=350))
            if summary is not None and len(diagnostic["headings"]) < 16:
                role = item.get_attribute("role") or "heading/label"
                diagnostic["headings"].append({
                    "role": role if role in ("heading", "label") else "heading/label",
                    **summary,
                })
        inputs = scope.locator('input,textarea,[role="combobox"],[role="searchbox"]')
        for i in range(min(inputs.count(), 60)):
            item = inputs.nth(i)
            if not item.is_visible():
                continue
            diagnostic["visibleInputCount"] += 1
            kind = item.get_attribute("type") or "text"
            kind = kind if kind in (
                "text", "search", "number", "tel", "email", "hidden",
            ) else "other"
            hint = safe_modal_label(
                (item.get_attribute("placeholder") or "") + " "
                + (item.get_attribute("aria-label") or "")
            )
            info = {"type": kind, "storeLocationHint": hint}
            if hint is not None and any(t in hint["keywordTags"]
                                        for t in ("store", "location", "search")):
                diagnostic["storeSearchInputObserved"] = True
            if len(diagnostic["inputs"]) < 16:
                diagnostic["inputs"].append(info)
        nodes = scope.locator(
            'button,[role="button"],[role="option"],a[href],'
            '[data-store-number],[data-store-id],[data-market-id]'
        )
        unique = set()
        attribute_names = set()
        for i in range(min(nodes.count(), 160)):
            item = nodes.nth(i)
            if not item.is_visible():
                continue
            diagnostic["visibleCandidateControlCount"] += 1
            names = item.evaluate(
                "(e) => Array.from(e.attributes).map(a => a.name).slice(0,45)"
            )
            attribute_names.update(safe_store_attribute_names(names))
            data_values = {
                name: item.get_attribute(name) for name in DATA_STORE_ATTRIBUTE_NAMES
            }
            identity = identify_canonical_dom_store(
                item.inner_text(timeout=400), data_values, stores
            )
            if identity["identityStatus"] == "VERIFIED":
                unique.add(identity["storeNumber"])
                if len(diagnostic["canonicalStoreCandidates"]) < 25:
                    diagnostic["canonicalStoreCandidates"].append({
                        **identity,
                        "dataAttributeNames": safe_store_attribute_names(names),
                        "enabled": item.is_enabled(),
                    })
        diagnostic["candidateStoreRowsCount"] = len(unique)
        diagnostic["dataAttributeNames"] = sorted(attribute_names)[:15]
    except Exception:
        diagnostic["inspectionIncomplete"] = True
    return diagnostic


def _stored_state(context: Any, page: Any) -> tuple[Any, Any]:
    """In-memory ephemeral comparison only. NEVER serialize the returned values."""
    try:
        cookies = tuple(sorted((c["name"], c["value"]) for c in context.cookies()))
    except Exception:
        cookies = None
    try:
        storage = page.evaluate(
            "() => Object.keys(localStorage).sort().map(k => [k, localStorage.getItem(k)])"
        )
    except Exception:
        storage = None
    return cookies, storage


def _try_market_choice(page: Any, target_id: str,
                       stores: dict[str, dict[str, str]]) -> dict[str, Any]:
    """Only a verified market row within one visible dialog may be clicked.

    A search field alone never authorizes guessed input. No city-only matching.
    """
    dialogs = visible_modal_scopes(page)
    if len(dialogs) != 1:
        return {"storeNumber": target_id, "status": "NO_UNAMBIGUOUS_DIALOG"}
    matches = []
    try:
        nodes = dialogs[0].locator(
            'button,[role="button"],[role="option"],a[href],'
            '[data-store-number],[data-store-id],[data-market-id]'
        )
        for index in range(min(nodes.count(), 160)):
            node = nodes.nth(index)
            if not node.is_visible():
                continue
            attrs = {
                name: node.get_attribute(name) for name in DATA_STORE_ATTRIBUTE_NAMES
            }
            identity = identify_canonical_dom_store(
                node.inner_text(timeout=400), attrs, stores
            )
            if identity["storeNumber"] == target_id and identity["identityStatus"] == "VERIFIED":
                matches.append(node)
    except Exception:
        return {"storeNumber": target_id, "status": "CONTROL_INSPECTION_FAILED"}
    if len(matches) != 1:
        return {"storeNumber": target_id, "status": "NO_UNAMBIGUOUS_STORE_ROW",
                "matchCount": len(matches)}
    button = matches[0]
    if not button.is_enabled():
        return {"storeNumber": target_id, "status": "STORE_ROW_DISABLED"}
    try:
        # Revalidate before clicking, not by a stale integer DOM index.
        attrs = {name: button.get_attribute(name)
                 for name in DATA_STORE_ATTRIBUTE_NAMES}
        current = identify_canonical_dom_store(
            button.inner_text(timeout=400), attrs, stores
        )
        if current["storeNumber"] != target_id or not button.is_visible():
            return {"storeNumber": target_id, "status": "STORE_ROW_CHANGED"}
        button.scroll_into_view_if_needed(timeout=2500)
        button.click(timeout=3500)
        page.wait_for_timeout(1000)
        return {"storeNumber": target_id, "status": "CLICKED"}
    except Exception as exc:
        return {
            "storeNumber": target_id, "status": "CLICK_FAILED",
            "failureCategory": "TIMEOUT" if type(exc).__name__ == "TimeoutError"
                               else "PLAYWRIGHT_CLICK_ERROR",
        }



def run_browser(obik: str, store: str, other_markets: list[str],
                stores: dict[str, dict[str, str]], out_dir: Path) -> dict[str, Any]:
    from playwright.sync_api import sync_playwright

    result: dict[str, Any] = {
        "obik": obik, "selectedStore": store,
        "testedOtherStores": other_markets,
        "canonicalStoreCount": len(stores),
        "verifiedProductPage": False,
        "initialNuxt": {}, "visibleControls": [], "uiActions": [],
        "observations": [], "observedRequests": [], "requestCountByAction": {},
        "selectedStoreMutation": "UNKNOWN",
        "contractClassification": {"type": "F_INCONCLUSIVE"},
        "researchOnly": True,
    }
    observations = result["observations"]
    requests = result["observedRequests"]
    action = ["initial:page"]
    request_phase: dict[int, str] = {}
    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch(headless=True)
            context = browser.new_context(
                locale="pl-PL",
                user_agent=("Mozilla/5.0 (Linux; Android 13; Mobile) "
                            "AppleWebKit/537.36 (KHTML, like Gecko) "
                            "Chrome/140.0.0.0 Mobile Safari/537.36"),
                viewport={"width": 414, "height": 896},
            )
            page = context.new_page()
            def record_response(response: Any) -> None:
                try:
                    request = response.request
                    # Attribute a response to the phase of its REQUEST,
                    # never to whatever control happens to be active later.
                    phase = request_phase.get(id(request), "UNATTRIBUTED_REQUEST")
                    if (len(observations) >= MAX_RECORDS
                            or (phase == "initial:page" and sum(
                                r["action"] == "initial:page" for r in observations
                            ) >= 25)):
                        return
                    if request.resource_type not in ("xhr", "fetch", "document"):
                        return
                    safe = safe_url_request(
                        request.method, request.url,
                        request.post_data if request.method != "GET" else None,
                        obik, stores,
                    )
                    if safe is None:
                        return
                    mime = response.headers.get("content-type", "").lower()
                    if "json" not in mime:
                        return
                    body = response.body()
                    if len(body) > MAX_JSON_BYTES:
                        return
                    payload = json.loads(body)
                    shape = response_shape(
                        payload, obik, stores, safe["path"]
                    )
                    # Keep bounded shape, not raw JSON or response headers.
                    observations.append({
                        "action": phase, **safe,
                        "status": response.status, "shape": shape,
                    })
                except Exception:
                    return
            def record_request(request: Any) -> None:
                if (len(requests) >= MAX_RECORDS
                        or (action[0] == "initial:page" and sum(
                            r["action"] == "initial:page" for r in requests
                        ) >= 25)):
                    return
                try:
                    if request.resource_type not in ("xhr", "fetch", "document"):
                        return
                    safe = safe_url_request(
                        request.method, request.url,
                        request.post_data if request.method != "GET" else None,
                        obik, stores,
                    )
                    if safe is not None:
                        request_phase[id(request)] = action[0]
                        requests.append({"action": action[0], **safe})
                except Exception:
                    return
            page.on("request", record_request)
            page.on("response", record_response)
            # The same established public store-switch route as production.
            # Never request a new guessed endpoint.
            known_url = (
                "https://www.obi.pl/api/disc/store/change"
                f"?storeNumber={store}&redirectUrl=/p/{obik}"
            )
            page.goto(known_url, wait_until="domcontentloaded", timeout=45000)
            page.wait_for_timeout(1700)
            final_path = urlsplit(page.url).path
            html = page.content()
            nuxt = inspect_initial_nuxt(html, obik, stores, store)
            result["initialNuxt"] = nuxt
            result["verifiedProductPage"] = (
                bool(re.search(r"/p/(?:[a-z0-9-]+-)?"+re.escape(obik)+r"(?:[-/]|$)", final_path, re.I))
                and nuxt.get("productIdentityVerified") is True
            )
            if result["verifiedProductPage"]:
                result["visibleControls"] = discover_controls(page)
            if not result["verifiedProductPage"]:
                result["reason"] = "EXACT_PRODUCT_PAGE_NOT_VERIFIED"
            elif not nuxt.get("selectedStoreVerified"):
                result["reason"] = "SELECTED_STORE_NOT_VERIFIED_IN_PRODUCT_NUXT"
            else:
                initial_contract = classify_contract(
                    observations, stores, obik, nuxt,
                )
                if initial_contract["type"] == "A_ONE_SHOT_ALL_STORES":
                    # A real initial-page response already covers all markets;
                    # no store-state mutation/click is needed merely to probe.
                    result["uiActions"].append({
                        "action": "availability:open",
                        "status": "SKIPPED_COMPLETE_INITIAL_CONTRACT",
                    })
                    result["selectedStoreMutation"] = "NOT_TESTED_NO_UI_INTERACTION"
                else:
                    before = _stored_state(context, page)
                    before_ui = inspect_post_open_ui(page, stores)
                    before_expanded = availability_button_expanded(page)
                    request_count_before = len(requests)
                    response_count_before = len(observations)
                    # Begin the phase BEFORE any Playwright or native DOM click.
                    begin_availability_open_phase(action)
                    control = click_safe_control(page, result["visibleControls"])
                    if control["status"] == "CLICK_DISPATCHED":
                        after_ui = inspect_post_open_ui(page, stores)
                        after_expanded = availability_button_expanded(page)
                        effect = verify_open_observable_effect(
                            before_ui, after_ui, before_expanded,
                            after_expanded, requests[request_count_before:],
                            observations[response_count_before:], obik,
                        )
                        result["openEffectEvidence"] = effect
                        control = finalize_open_control(control, effect)
                    result["uiActions"].append({
                        "action": "availability:open", **control,
                    })
                    if control["status"] == "CLICKED":
                        result["controlsAfterOpen"] = discover_controls(page)
                        result["postOpenUi"] = after_ui
                        post_open_contract = classify_contract(
                            observations, stores, obik, nuxt,
                        )
                        if post_open_contract["type"] == "A_ONE_SHOT_ALL_STORES":
                            result["uiActions"].append({
                                "action": "availability:other-stores",
                                "status": "SKIPPED_COMPLETE_PRODUCT_CONTRACT",
                            })
                        elif result["postOpenUi"]["storeSearchInputObserved"]:
                            # Run #5 learns input structure only; never type
                            # guesses or click market rows in a search UI.
                            result["uiActions"].append({
                                "action": "availability:other-stores",
                                "status": "SEARCH_INPUT_OBSERVED_NO_GUESSED_INPUT",
                            })
                        else:
                            for target in other_markets[:2]:
                                action[0] = "availability:store-" + target
                                outcome = _try_market_choice(page, target, stores)
                                result["uiActions"].append({
                                    "action": "availability:store-check", **outcome,
                                })
                                if outcome["status"] != "CLICKED":
                                    break
                    if control["status"] == "CLICKED":
                        after = _stored_state(context, page)
                        if before[0] is not None and after[0] is not None:
                            result["selectedStoreMutation"] = (
                                "COOKIE_STATE_CHANGED" if before[0] != after[0]
                                else "COOKIE_STATE_UNCHANGED"
                            )
                            result["localStorageChanged"] = (
                                None if before[1] is None or after[1] is None
                                else before[1] != after[1]
                            )
                    else:
                        result["selectedStoreMutation"] = "NOT_TESTED_NO_SAFE_CONTROL"
            result["contractClassification"] = classify_contract(
                observations, stores, obik, nuxt,
            )
            for request in requests:
                key = request["action"]
                result["requestCountByAction"][key] = (
                    result["requestCountByAction"].get(key, 0) + 1
                )
            browser.close()
    except Exception as exc:
        # Never serialize exception text; may contain private request details.
        result["reason"] = "BROWSER_OBSERVATION_FAILED"
        result["failureType"] = type(exc).__name__[:40]
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "locations-summary.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    lines = [
        "OBI MULTI-MARKET RESEARCH (NO PRODUCTION IMPLEMENTATION)",
        f"obik={obik}",
        f"selectedStore={store}",
        f"canonicalStores={len(stores)}",
        f"verifiedProductPage={result['verifiedProductPage']}",
        f"contractClassification={json.dumps(result['contractClassification'])}",
        f"initialNuxt={json.dumps(result['initialNuxt'], ensure_ascii=False)}",
        f"visibleControls={json.dumps(result['visibleControls'], ensure_ascii=False)}",
        f"controlsAfterOpen={json.dumps(result.get('controlsAfterOpen', []), ensure_ascii=False)}",
        f"postOpenUi={json.dumps(result.get('postOpenUi', {}), ensure_ascii=False)}",
        f"openEffectEvidence={json.dumps(result.get('openEffectEvidence', {}), ensure_ascii=False)}",
        f"uiActions={json.dumps(result['uiActions'], ensure_ascii=False)}",
        f"selectedStoreMutation={result['selectedStoreMutation']}",
        f"requestCountByAction={json.dumps(result['requestCountByAction'])}",
        f"observedResponseCount={len(observations)}",
        f"observedRequestCount={len(requests)}",
    ]
    for item in requests[:50]:
        lines.append("request=" + json.dumps(item, ensure_ascii=False, separators=(",", ":")))
    for item in observations[:50]:
        lines.append("observed=" + json.dumps(item, ensure_ascii=False, separators=(",", ":")))
    (out_dir / "locations-summary.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--obik", required=True)
    parser.add_argument("--store", required=True)
    parser.add_argument("--other-markets", default="003,074")
    parser.add_argument("--stores-source", default="app/src/main/java/pl/lukaszpeciak/towarownik/product/ObiStores.kt")
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()
    if not OBIK_NUMBER.fullmatch(args.obik) or not STORE_NUMBER.fullmatch(args.store):
        parser.error("Invalid OBIK or store number")
    stores = canonical_stores(Path(args.stores_source))
    others = [x.strip() for x in args.other_markets.split(",") if x.strip()]
    if (args.store not in stores or len(others) > 2 or
            len(others) != len(set(others)) or
            any(x not in stores or x == args.store for x in others)):
        parser.error("Market IDs must be distinct canonical OBI_STORES entries")
    run_browser(args.obik, args.store, others, stores, Path(args.out_dir))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
