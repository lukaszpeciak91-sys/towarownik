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
STATUS_WORDS = {
    "available", "in_stock", "low", "low_stock", "unavailable",
    "not_available", "out_of_stock", "limited", "unknown",
    "dostępny", "dostępne", "niedostępny", "niedostępne", "brak",
}
PRIVATE_FIELD = re.compile(
    r"token|secret|pass|auth|cookie|session|bearer|email|phone|"
    r"address|account|user|device|fingerprint|tracking|visitor|ipaddr",
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
PATH_WORD = re.compile(r"[a-z][a-z0-9_-]{0,29}\Z")
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
        if part in {obik, *stores} or (PATH_WORD.fullmatch(part) and not re.search(r"\d{5,}", part)):
            safe.append(part)
        else:
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
    for key, value in pairs[:80]:
        key = safe_field_name(key)
        if key is None:
            continue
        if key not in names and len(names) < 40:
            names.append(key)
        ident = safe_identifier(value, obik, stores)
        if ident is not None and key in (*STORE_KEYS, *PRODUCT_KEYS, "store", "market"):
            safe_values[key] = ident
    return {"names": names, "safeValues": safe_values}


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
    path = record.get("path", "")
    pieces = path.strip("/").split("/")
    if obik in pieces and any(p in {"p", "product", "products", "article", "articles", "sku", "availability"} for p in pieces):
        return "SINGULAR_REQUEST_PRODUCT_PATH"
    for params in (record.get("query") or {}, record.get("body") or {}):
        for name, value in (params.get("safeValues") or {}).items():
            if name in PRODUCT_KEYS and value == obik:
                return "EXACT_REQUEST_PRODUCT_ID"
    return "UNKNOWN"


def response_shape(data: Any, obik: str, stores: dict[str, Any]) -> dict[str, Any]:
    if isinstance(data, dict):
        root_names = [key for key in data if safe_field_name(key) is not None][:35]
        ids = [str(data[key]) for key in PRODUCT_KEYS if key in data
               and type(data[key]) in (str, int) and OBIK_NUMBER.fullmatch(str(data[key]))]
        root_id = ids[0] if len(set(ids)) == 1 else ("CONFLICT" if ids else None)
    else:
        root_names, root_id = [], None
    rows = candidate_rows(data, stores)
    return {"rootType": type(data).__name__, "rootFields": root_names,
            "rootProductId": root_id, "storeRows": rows[:MAX_ROWS],
            "storeRowCount": len(rows)}


def verified_rows(record: dict[str, Any], stores: dict[str, Any]) -> dict[str, dict[str, Any]]:
    rows = (record.get("shape") or {}).get("storeRows") or []
    if not rows or len(rows) > MAX_ROWS:
        return {}
    by_id = {}
    for row in rows:
        ident = row.get("storeNumber")
        if (ident not in stores or ident in by_id
                or row.get("ambiguousFields") or row.get("ambiguousIdentity")
                or row.get("state") not in (
                    "known_zero", "known_positive", "qualitative", "unknown_null"
                )):
            return {}
        by_id[ident] = row
    return by_id


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
                ) and not row.get("ambiguousFields") for row in preloaded)):
            return {"type": "C_FRONTEND_PRELOADED", "reason": "EXACT_PRODUCT_OWNED_NUXT_ROWS",
                    "observedStoreCount": len(ids)}
    scoped = []
    for record in observations:
        if (not record.get("action", "").startswith("availability:")
                or product_identity(record, obik) in ("UNKNOWN", "CONFLICT")):
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
        if len(mapped) >= 2:
            return {"type": "B_ONE_SHOT_SUBSET",
                    "reason": "PRODUCT_BOUND_VERIFIED_SUBSET_OMISSIONS_UNKNOWN",
                    "observedStoreCount": len(mapped)}
    if 2 <= len(scoped) <= 5:
        combined = set().union(*(set(mapped) for _, mapped in scoped))
        if len(combined) >= 2:
            single_scoped = {
                next(iter(mapped)) for _, mapped in scoped if len(mapped) == 1
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



def inspect_initial_nuxt(html: str, obik: str, stores: dict[str, Any]) -> dict[str, Any]:
    """Only attach availability fields to an exact skuId-owning object."""
    result = {"productIdentityVerified": False, "productOwnerCount": 0,
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
            ) and not row.get("ambiguousFields") for row in owned)):
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
    controls = []
    try:
        nodes = page.locator("button,[role='button'],a[href],select")
        for index in range(min(nodes.count(), 220)):
            node = nodes.nth(index)
            if not node.is_visible():
                continue
            raw = node.get_attribute("aria-label") or node.inner_text(timeout=400)
            label = safe_control_text(raw)
            if not label:
                continue
            role = node.get_attribute("role") or node.evaluate("(e) => e.tagName.toLowerCase()")
            try:
                nearby = safe_control_text(
                    node.locator("xpath=..").inner_text(timeout=350)
                )
            except Exception:
                nearby = ""
            controls.append({
                "index": index, "role": role if role in ("button", "a", "select", "link") else "other",
                "label": label, "nearbyText": nearby, "enabled": node.is_enabled(),
            })
            if len(controls) >= MAX_CONTROLS:
                break
    except Exception:
        pass
    return controls


def click_safe_control(page: Any, controls: list[dict[str, Any]]) -> dict[str, Any]:
    """Click only ONE unambiguous harmless store/availability discovery control."""
    eligible = [c for c in controls if c["enabled"]
                and c["role"] in ("button", "a", "link")
                and SELECT_ACTION.search(c["label"])
                and SELECT_VERB.search(c["label"])
                and not UNSAFE_ACTION.search(c["label"])]
    if len(eligible) != 1:
        return {"status": "NO_UNAMBIGUOUS_SAFE_CONTROL", "eligibleCount": len(eligible)}
    target = eligible[0]
    try:
        node = page.locator("button,[role='button'],a[href],select").nth(target["index"])
        if not node.is_visible() or not node.is_enabled():
            return {"status": "CONTROL_CHANGED"}
        node.click(timeout=3500)
        page.wait_for_timeout(1200)
        return {"status": "CLICKED", "label": target["label"], "role": target["role"]}
    except Exception:
        return {"status": "CLICK_FAILED"}


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
    """No city-only match: choose only explicit canonical ID or exact address."""
    meta = stores[target_id]
    matches = []
    try:
        nodes = page.locator("button,[role='button'],a[href]")
        for index in range(min(nodes.count(), 240)):
            node = nodes.nth(index)
            if not node.is_visible() or not node.is_enabled():
                continue
            raw = (node.get_attribute("aria-label") or "") + " " + node.inner_text(timeout=400)
            norm = re.sub(r"\s+", " ", raw).casefold()
            id_match = any(
                node.get_attribute(attr) == target_id
                for attr in ("data-store-number", "data-store-id", "data-market-id")
            )
            address_match = meta["address"].casefold() in norm and meta["city"].casefold() in norm
            if (id_match or address_match) and not UNSAFE_ACTION.search(raw):
                matches.append(index)
    except Exception:
        return {"storeNumber": target_id, "status": "CONTROL_INSPECTION_FAILED"}
    if len(matches) != 1:
        return {"storeNumber": target_id, "status": "NO_UNAMBIGUOUS_STORE_ROW",
                "matchCount": len(matches)}
    try:
        page.locator("button,[role='button'],a[href]").nth(matches[0]).click(timeout=3500)
        page.wait_for_timeout(1200)
        return {"storeNumber": target_id, "status": "CLICKED"}
    except Exception:
        return {"storeNumber": target_id, "status": "CLICK_FAILED"}


def run_browser(obik: str, store: str, other_markets: list[str],
                stores: dict[str, dict[str, str]], out_dir: Path) -> dict[str, Any]:
    from playwright.sync_api import sync_playwright

    result: dict[str, Any] = {
        "obik": obik, "selectedStore": store,
        "testedOtherStores": other_markets,
        "canonicalStoreCount": len(stores),
        "verifiedProductPage": False,
        "initialNuxt": {}, "visibleControls": [], "uiActions": [],
        "observations": [], "requestCountByAction": {},
        "selectedStoreMutation": "UNKNOWN",
        "contractClassification": {"type": "F_INCONCLUSIVE"},
        "researchOnly": True,
    }
    observations = result["observations"]
    action = ["initial:page"]
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
                if len(observations) >= MAX_RECORDS:
                    return
                try:
                    request = response.request
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
                    shape = response_shape(payload, obik, stores)
                    # Keep bounded shape, not raw JSON or response headers.
                    observations.append({
                        "action": action[0], **safe,
                        "status": response.status, "shape": shape,
                    })
                except Exception:
                    return
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
            nuxt = inspect_initial_nuxt(html, obik, stores)
            result["initialNuxt"] = nuxt
            result["verifiedProductPage"] = (
                bool(re.search(r"/p/(?:[a-z0-9-]+-)?"+re.escape(obik)+r"(?:[-/]|$)", final_path, re.I))
                and nuxt.get("productIdentityVerified") is True
            )
            if not result["verifiedProductPage"]:
                result["reason"] = "EXACT_PRODUCT_PAGE_NOT_VERIFIED"
            else:
                result["visibleControls"] = discover_controls(page)
                before = _stored_state(context, page)
                action[0] = "availability:open"
                control = click_safe_control(page, result["visibleControls"])
                result["uiActions"].append({"action": "availability:open", **control})
                if control["status"] == "CLICKED":
                    for target in other_markets[:2]:
                        action[0] = "availability:store-" + target
                        outcome = _try_market_choice(page, target, stores)
                        result["uiActions"].append({
                            "action": "availability:store-check", **outcome
                        })
                        # Stop if the overlay/selector did not provide the row.
                        if outcome["status"] != "CLICKED":
                            break
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
            result["contractClassification"] = classify_contract(
                observations, stores, obik, nuxt,
            )
            for observation in observations:
                key = observation["action"]
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
        f"uiActions={json.dumps(result['uiActions'], ensure_ascii=False)}",
        f"selectedStoreMutation={result['selectedStoreMutation']}",
        f"requestCountByAction={json.dumps(result['requestCountByAction'])}",
        f"observedResponseCount={len(observations)}",
    ]
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
