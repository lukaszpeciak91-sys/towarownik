#!/usr/bin/env python3
"""Research-only capture of the real OBI Poland cross-store availability flow.

The script drives the public product UI in Chromium and writes only bounded,
sanitized request/response summaries. Cookies, auth material, raw HTML and raw
JSON are never persisted.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import re
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any

BASE = "https://www.obi.pl"
DIRECTORY_URL = f"{BASE}/api/disc/store/locator/country/PL"
USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 13; Mobile) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/140.0.0.0 Mobile Safari/537.36"
)
ACCEPT_LANGUAGE = "pl-PL,pl;q=0.9"
HTML_ACCEPT = (
    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
)

OBIK_RE = re.compile(r"^\d{7}$")
STORE_RE = re.compile(r"^\d{3}$")
SAFE_QUERY_VALUE_KEYS = {
    "articlenumber",
    "articlenumbers",
    "article",
    "articles",
    "obik",
    "sku",
    "skuid",
    "productnumber",
    "storenumber",
    "storeid",
    "store",
    "country",
    "locale",
    "language",
    "lang",
}
SAFE_SCALAR_KEYS = SAFE_QUERY_VALUE_KEYS | {
    "stock",
    "quantity",
    "price",
    "grossprice",
    "currency",
    "isocode",
    "available",
    "availability",
    "status",
    "isactive",
}
SENSITIVE_HEADER_NAMES = {
    "authorization",
    "cookie",
    "set-cookie",
    "proxy-authorization",
    "x-csrf-token",
    "x-xsrf-token",
}
SAFE_HEADER_VALUE_NAMES = {
    "accept",
    "accept-language",
    "content-type",
    "origin",
    "sec-fetch-dest",
    "sec-fetch-mode",
    "sec-fetch-site",
    "x-requested-with",
}
RELEVANCE_TERMS = (
    "availability",
    "available",
    "stock",
    "inventory",
    "article",
    "product",
    "store",
    "market",
    "reserve",
    "pickup",
    "collect",
)
MAX_EVENTS = 240
MAX_JSON_BYTES = 512 * 1024
MAX_JSON_DEPTH = 4
MAX_JSON_KEYS = 24
MAX_ARRAY_SAMPLE = 4
MAX_POST_BYTES = 32 * 1024
REFERENCE_WRAPPERS = {"Ref", "ShallowRef"}


def validate_obik(value: str) -> None:
    if OBIK_RE.fullmatch(value) is None:
        raise ValueError(f"invalid OBIK {value!r}")


def validate_store(value: str) -> None:
    if STORE_RE.fullmatch(value) is None:
        raise ValueError(f"invalid store {value!r}")


def is_obi_host(host: str | None) -> bool:
    if not host:
        return False
    host = host.lower()
    return (
        host == "obi.pl"
        or host.endswith(".obi.pl")
        or host == "obi.de"
        or host.endswith(".obi.de")
    )


def sanitize_query(url: str) -> list[dict[str, str]]:
    parsed = urllib.parse.urlsplit(url)
    items = []
    for key, value in urllib.parse.parse_qsl(
        parsed.query,
        keep_blank_values=True,
    ):
        lowered = key.lower()
        safe = value if lowered in SAFE_QUERY_VALUE_KEYS else "<redacted>"
        items.append({"name": key, "value": safe[:160]})
    return items[:30]


def sanitize_referer(value: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(value)
    except ValueError:
        return "<invalid>"
    if not is_obi_host(parsed.hostname):
        return "<external>"
    return urllib.parse.urlunsplit(
        (
            parsed.scheme,
            parsed.netloc,
            parsed.path,
            "",
            "",
        ),
    )


def sanitize_headers(headers: dict[str, str]) -> dict[str, Any]:
    names = []
    safe_values = {}
    has_cookie = False
    has_auth = False
    for key, value in headers.items():
        lowered = key.lower()
        if lowered == "cookie":
            has_cookie = bool(value)
            continue
        if lowered in {"authorization", "proxy-authorization"}:
            has_auth = bool(value)
            continue
        if lowered in {"set-cookie", "x-csrf-token", "x-xsrf-token"}:
            continue
        names.append(lowered)
        if lowered in SAFE_HEADER_VALUE_NAMES:
            safe_values[lowered] = (
                sanitize_referer(value)
                if lowered == "origin"
                else value[:240]
            )
        elif lowered == "referer":
            safe_values[lowered] = sanitize_referer(value)
    return {
        "headerNames": sorted(set(names))[:60],
        "safeHeaders": safe_values,
        "hasCookie": has_cookie,
        "hasAuthorization": has_auth,
    }


def scalar_summary(key: str | None, value: Any) -> Any:
    if key and key.lower() in SAFE_SCALAR_KEYS:
        if value is None or isinstance(value, (bool, int, float)):
            return value
        if isinstance(value, str):
            return value[:160]
    if value is None:
        return None
    if isinstance(value, bool):
        return {"type": "boolean"}
    if isinstance(value, int):
        return {"type": "integer"}
    if isinstance(value, float):
        return {"type": "number"}
    if isinstance(value, str):
        return {"type": "string", "length": len(value)}
    return {"type": type(value).__name__}


def summarize_json(
    value: Any,
    *,
    depth: int = 0,
    key: str | None = None,
) -> Any:
    if depth >= MAX_JSON_DEPTH:
        if isinstance(value, dict):
            return {"type": "object", "keyCount": len(value)}
        if isinstance(value, list):
            return {"type": "array", "length": len(value)}
        return scalar_summary(key, value)

    if isinstance(value, dict):
        result = {}
        for child_key in list(value.keys())[:MAX_JSON_KEYS]:
            result[str(child_key)] = summarize_json(
                value[child_key],
                depth=depth + 1,
                key=str(child_key),
            )
        return {
            "type": "object",
            "keys": list(value.keys())[:MAX_JSON_KEYS],
            "fields": result,
        }

    if isinstance(value, list):
        return {
            "type": "array",
            "length": len(value),
            "sample": [
                summarize_json(item, depth=depth + 1)
                for item in value[:MAX_ARRAY_SAMPLE]
            ],
        }

    return scalar_summary(key, value)


def summarize_post_data(
    post_data: str | None,
    content_type: str | None,
) -> dict[str, Any] | None:
    if post_data is None:
        return None
    if len(post_data.encode("utf-8", "replace")) > MAX_POST_BYTES:
        return {
            "type": "oversized",
            "utf8Bytes": len(post_data.encode("utf-8", "replace")),
        }
    content_type = (content_type or "").lower()
    if "json" in content_type:
        try:
            return {
                "type": "json",
                "shape": summarize_json(json.loads(post_data)),
            }
        except Exception:
            return {"type": "malformed_json", "utf8Bytes": len(post_data)}
    if "application/x-www-form-urlencoded" in content_type:
        pairs = urllib.parse.parse_qsl(post_data, keep_blank_values=True)
        return {
            "type": "form",
            "fields": [
                {
                    "name": key,
                    "value": (
                        value
                        if key.lower() in SAFE_QUERY_VALUE_KEYS
                        else "<redacted>"
                    ),
                }
                for key, value in pairs[:30]
            ],
        }
    return {
        "type": "opaque",
        "utf8Bytes": len(post_data.encode("utf-8", "replace")),
    }


def collect_json_keys(value: Any, out: set[str] | None = None) -> set[str]:
    if out is None:
        out = set()
    if isinstance(value, dict):
        for key, child in value.items():
            out.add(str(key).lower())
            collect_json_keys(child, out)
    elif isinstance(value, list):
        for child in value[:100]:
            collect_json_keys(child, out)
    return out


def collect_store_numbers(value: Any) -> set[str]:
    stores: set[str] = set()
    if isinstance(value, dict):
        for key, child in value.items():
            lowered = str(key).lower()
            if "store" in lowered or "market" in lowered:
                if isinstance(child, (str, int)):
                    candidate = str(child)
                    if STORE_RE.fullmatch(candidate):
                        stores.add(candidate)
            stores.update(collect_store_numbers(child))
    elif isinstance(value, list):
        for child in value[:200]:
            stores.update(collect_store_numbers(child))
    return stores


def collect_obiks(value: Any) -> set[str]:
    obiks: set[str] = set()
    if isinstance(value, dict):
        for key, child in value.items():
            lowered = str(key).lower()
            if any(
                token in lowered
                for token in (
                    "article",
                    "obik",
                    "sku",
                    "productnumber",
                )
            ):
                if isinstance(child, (str, int)):
                    candidate = str(child)
                    if OBIK_RE.fullmatch(candidate):
                        obiks.add(candidate)
            obiks.update(collect_obiks(child))
    elif isinstance(value, list):
        for child in value[:200]:
            obiks.update(collect_obiks(child))
    return obiks


def relevance_score(
    path: str,
    query: list[dict[str, str]],
    request_shape: dict[str, Any] | None,
    json_root: Any,
) -> int:
    haystack = path.lower()
    score = sum(1 for term in RELEVANCE_TERMS if term in haystack)
    query_names = " ".join(item["name"].lower() for item in query)
    score += sum(1 for term in RELEVANCE_TERMS if term in query_names)
    if request_shape is not None:
        encoded = json.dumps(request_shape, ensure_ascii=False).lower()
        score += min(3, sum(1 for term in RELEVANCE_TERMS if term in encoded))
    if json_root is not None:
        keys = collect_json_keys(json_root)
        score += min(
            5,
            sum(
                1
                for term in RELEVANCE_TERMS
                if any(term in key for key in keys)
            ),
        )
    return score


def classify_contract(events: list[dict[str, Any]]) -> dict[str, Any]:
    candidates = [
        event
        for event in events
        if event.get("relevanceScore", 0) >= 3
        and event.get("response", {}).get("jsonShape") is not None
        and event.get("response", {}).get("availabilityKeyHits")
        and event.get("stage") != "page_load"
        and "/api/disc/store/locator/country/" not in event.get("path", "").lower()
    ]
    if not candidates:
        return {
            "type": "C",
            "mechanism": "no_verified_availability_json_request",
            "candidateCount": 0,
        }

    multi_store = [
        event
        for event in candidates
        if len(event.get("response", {}).get("storeNumbers", [])) > 1
    ]
    if multi_store:
        return {
            "type": "A",
            "mechanism": "single_request_multiple_stores",
            "candidateCount": len(candidates),
            "eventIds": [event["id"] for event in multi_store[:10]],
        }

    per_store = [
        event
        for event in candidates
        if (
            len(event.get("response", {}).get("storeNumbers", [])) == 1
            or any(
                item["name"].lower()
                in {"storenumber", "storeid", "store"}
                for item in event.get("query", [])
            )
        )
    ]
    if per_store:
        return {
            "type": "B",
            "mechanism": "per_store_request",
            "candidateCount": len(candidates),
            "eventIds": [event["id"] for event in per_store[:20]],
        }

    return {
        "type": "C",
        "mechanism": "other_frontend_contract",
        "candidateCount": len(candidates),
        "eventIds": [event["id"] for event in candidates[:20]],
    }


@dataclass
class RawReplay:
    url: str
    method: str
    headers: dict[str, str]
    post_data: str | None


@dataclass
class NetworkRecorder:
    stage: str = "page_load"
    obik: str | None = None
    target_store: str | None = None
    events: list[dict[str, Any]] = field(default_factory=list)
    raw_replays: dict[int, RawReplay] = field(default_factory=dict)
    next_id: int = 1

    def attach(self, page) -> None:
        page.on("response", self._response)

    def _response(self, response) -> None:
        if len(self.events) >= MAX_EVENTS:
            return
        request = response.request
        parsed = urllib.parse.urlsplit(request.url)
        if not is_obi_host(parsed.hostname):
            return
        if request.resource_type not in {"xhr", "fetch"} and "/api/" not in parsed.path:
            return

        headers = request.all_headers()
        content_type = headers.get("content-type")
        query = sanitize_query(request.url)
        request_shape = summarize_post_data(
            request.post_data,
            content_type,
        )

        response_content_type = response.headers.get("content-type", "")
        json_root = None
        json_shape = None
        store_numbers: list[str] = []
        obiks: list[str] = []
        availability_key_hits: list[str] = []
        body_bytes = None
        if "json" in response_content_type.lower():
            try:
                body_bytes = response.body()
                if len(body_bytes) <= MAX_JSON_BYTES:
                    json_root = json.loads(body_bytes.decode("utf-8"))
                    json_shape = summarize_json(json_root)
                    store_numbers = sorted(collect_store_numbers(json_root))
                    obiks = sorted(collect_obiks(json_root))
                    all_keys = collect_json_keys(json_root)
                    availability_key_hits = sorted(
                        key
                        for key in all_keys
                        if any(
                            term in key
                            for term in (
                                "stock",
                                "availability",
                                "inventory",
                                "article",
                                "quantity",
                                "price",
                            )
                        )
                    )[:30]
                else:
                    json_shape = {
                        "type": "oversized_json",
                        "utf8Bytes": len(body_bytes),
                    }
            except Exception:
                json_shape = {"type": "unreadable_json"}

        event_id = self.next_id
        self.next_id += 1
        score = relevance_score(
            parsed.path,
            query,
            request_shape,
            json_root,
        )
        safe_request_headers = sanitize_headers(headers)
        event = {
            "id": event_id,
            "stage": self.stage,
            "obik": self.obik,
            "targetStore": self.target_store,
            "method": request.method,
            "host": parsed.hostname,
            "path": parsed.path,
            "query": query,
            "request": {
                **safe_request_headers,
                "bodyShape": request_shape,
            },
            "response": {
                "status": response.status,
                "contentType": response_content_type.split(";", 1)[0].strip(),
                "jsonShape": json_shape,
                "storeNumbers": store_numbers,
                "obiks": obiks,
                "availabilityKeyHits": availability_key_hits,
            },
            "relevanceScore": score,
        }
        self.events.append(event)
        self.raw_replays[event_id] = RawReplay(
            url=request.url,
            method=request.method,
            headers=headers,
            post_data=request.post_data,
        )


def visible_text(page, max_chars: int = 1400) -> str:
    try:
        text = page.locator("body").inner_text(timeout=2000)
    except Exception:
        return ""
    return re.sub(r"\s+", " ", text).strip()[:max_chars]


def dismiss_consent(page) -> str | None:
    patterns = [
        re.compile("akceptuj wszystkie", re.I),
        re.compile("zaakceptuj wszystkie", re.I),
        re.compile("zgadzam się", re.I),
        re.compile("^ok$", re.I),
    ]
    for pattern in patterns:
        for role in ("button", "link"):
            try:
                locator = page.get_by_role(role, name=pattern)
                if locator.count() and locator.first.is_visible():
                    label = locator.first.inner_text()[:120]
                    locator.first.click(timeout=3000)
                    page.wait_for_timeout(500)
                    return label
            except Exception:
                pass
    return None


def click_named_control(page, patterns: list[re.Pattern[str]]) -> str | None:
    for pattern in patterns:
        for role in ("button", "link"):
            try:
                locator = page.get_by_role(role, name=pattern)
                if locator.count() and locator.first.is_visible():
                    label = locator.first.inner_text()[:160]
                    locator.first.click(timeout=5000)
                    page.wait_for_timeout(900)
                    return label
            except Exception:
                pass
        try:
            locator = page.get_by_text(pattern)
            if locator.count() and locator.first.is_visible():
                label = locator.first.inner_text()[:160]
                locator.first.click(timeout=5000)
                page.wait_for_timeout(900)
                return label
        except Exception:
            pass

    try:
        raw_patterns = [pattern.pattern for pattern in patterns]
        clicked = page.evaluate(
            """(patterns) => {
                const regexes = patterns.map((value) => new RegExp(value, 'i'));
                const visible = (el) => {
                    const style = window.getComputedStyle(el);
                    const rect = el.getBoundingClientRect();
                    return style.visibility !== 'hidden'
                        && style.display !== 'none'
                        && rect.width > 0
                        && rect.height > 0;
                };
                const nodes = Array.from(
                    document.querySelectorAll(
                        'button,a,[role="button"],[onclick],div,span,p'
                    )
                );
                const matches = nodes
                    .filter((el) => {
                        const text = (el.innerText || '').replace(/\\s+/g, ' ').trim();
                        return text
                            && text.length <= 220
                            && visible(el)
                            && regexes.some((regex) => regex.test(text));
                    })
                    .sort((a, b) => (a.innerText || '').length - (b.innerText || '').length);
                if (!matches.length) return null;
                const source = matches[0];
                const target = source.closest('button,a,[role="button"],[onclick]') || source;
                const label = (source.innerText || target.innerText || '')
                    .replace(/\\s+/g, ' ')
                    .trim()
                    .slice(0, 160);
                target.click();
                return label || target.tagName;
            }""",
            raw_patterns,
        )
        if clicked:
            page.wait_for_timeout(900)
            return str(clicked)[:160]
    except Exception:
        pass
    return None


AVAILABILITY_PATTERNS = [
    re.compile("sprawdź dostępność w innym sklepie", re.I),
    re.compile("sprawdź dostępność w sklepie", re.I),
    re.compile("wybierz sklep obi", re.I),
    re.compile("zarezerwuj i odbierz", re.I),
]
CHANGE_STORE_PATTERNS = [
    re.compile("sprawdź dostępność w innym sklepie", re.I),
    re.compile("wybierz sklep obi", re.I),
    re.compile("zmień sklep", re.I),
    re.compile("zmień market", re.I),
    re.compile("wybierz market", re.I),
    re.compile("inny sklep", re.I),
    re.compile("inny market", re.I),
]


def choose_store(
    page,
    store: dict[str, Any],
) -> dict[str, Any]:
    result: dict[str, Any] = {
        "requestedStore": store.get("storeNumber"),
        "storeName": store.get("name"),
        "city": store.get("city"),
        "searchFilled": False,
        "selected": False,
    }

    change_clicked = click_named_control(page, CHANGE_STORE_PATTERNS)
    result["changeControl"] = change_clicked

    scope = page
    try:
        dialogs = page.locator('[role="dialog"]')
        for index in range(dialogs.count() - 1, -1, -1):
            if dialogs.nth(index).is_visible():
                scope = dialogs.nth(index)
                break
    except Exception:
        pass

    search_value = (
        store.get("city")
        or store.get("name")
        or store.get("storeNumber")
    )
    try:
        inputs = scope.locator("input")
        for index in range(inputs.count()):
            input_box = inputs.nth(index)
            if not input_box.is_visible():
                continue
            placeholder = (input_box.get_attribute("placeholder") or "").lower()
            aria = (input_box.get_attribute("aria-label") or "").lower()
            if (
                not placeholder
                or any(
                    term in placeholder or term in aria
                    for term in (
                        "kod",
                        "miast",
                        "miejsc",
                        "sklep",
                        "market",
                        "lokal",
                        "szuk",
                    )
                )
            ):
                input_box.fill(str(search_value), timeout=3000)
                page.wait_for_timeout(1000)
                result["searchFilled"] = True
                break
    except Exception as exc:
        result["searchError"] = exc.__class__.__name__

    candidates = [
        store.get("name"),
        store.get("city"),
        store.get("storeNumber"),
    ]
    for candidate in [value for value in candidates if value]:
        pattern = re.compile(re.escape(str(candidate)), re.I)
        try:
            locator = scope.get_by_text(pattern)
            if locator.count():
                target = locator.first
                if target.is_visible():
                    target.click(timeout=5000)
                    page.wait_for_timeout(1400)
                    result["selected"] = True
                    result["selectedBy"] = str(candidate)[:160]
                    return result
        except Exception:
            pass

    result["visibleAfterAttempt"] = visible_text(page, 900)
    return result


def load_store_directory(context, stores: list[str]) -> dict[str, dict[str, Any]]:
    response = context.request.get(
        DIRECTORY_URL,
        headers={
            "Accept": "application/json,text/plain,*/*",
            "Accept-Language": ACCEPT_LANGUAGE,
            "User-Agent": USER_AGENT,
        },
        timeout=30000,
    )
    if response.status != 200:
        raise RuntimeError(f"store directory HTTP {response.status}")
    root = response.json()
    rows = root.get("stores") if isinstance(root, dict) else None
    if not isinstance(rows, list):
        raise RuntimeError("store directory shape changed")
    selected = {}
    for item in rows:
        if not isinstance(item, dict):
            continue
        number = str(item.get("storeNumber") or "")
        if number not in stores:
            continue
        address = item.get("address") if isinstance(item.get("address"), dict) else {}
        selected[number] = {
            "storeNumber": number,
            "name": item.get("name"),
            "city": item.get("city") or address.get("city"),
            "isActive": item.get("isActive"),
        }
    return selected


def safe_event_candidates(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return sorted(
        [
            event
            for event in events
            if event.get("relevanceScore", 0) >= 3
            and event.get("response", {}).get("availabilityKeyHits")
            and event.get("stage") != "page_load"
            and "/api/disc/store/locator/country/" not in event.get("path", "").lower()
        ],
        key=lambda event: (-event["relevanceScore"], event["id"]),
    )[:80]


def no_cookie_replay(raw: RawReplay) -> dict[str, Any]:
    headers = {}
    for key, value in raw.headers.items():
        lowered = key.lower()
        if key.startswith(":"):
            continue
        if lowered in {
            "cookie",
            "authorization",
            "proxy-authorization",
            "host",
            "content-length",
            "x-csrf-token",
            "x-xsrf-token",
        }:
            continue
        headers[key] = value
    body = raw.post_data.encode("utf-8") if raw.post_data is not None else None
    request = urllib.request.Request(
        raw.url,
        data=body,
        method=raw.method,
        headers=headers,
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = response.read(MAX_JSON_BYTES + 1)
            content_type = response.headers.get("Content-Type", "")
            result = {
                "status": int(response.status),
                "contentType": content_type.split(";", 1)[0].strip(),
                "durationMs": round((time.monotonic() - started) * 1000),
            }
            if "json" in content_type.lower() and len(payload) <= MAX_JSON_BYTES:
                try:
                    root = json.loads(payload.decode("utf-8"))
                    result["jsonShape"] = summarize_json(root)
                    result["storeNumbers"] = sorted(collect_store_numbers(root))
                    result["obiks"] = sorted(collect_obiks(root))
                except Exception:
                    result["jsonShape"] = {"type": "unreadable_json"}
            return result
    except urllib.error.HTTPError as exc:
        return {
            "status": int(exc.code),
            "contentType": (exc.headers.get("Content-Type", "").split(";", 1)[0].strip()),
            "durationMs": round((time.monotonic() - started) * 1000),
        }
    except Exception as exc:
        return {
            "status": None,
            "error": exc.__class__.__name__,
            "durationMs": round((time.monotonic() - started) * 1000),
        }


def replay_raw(raw: RawReplay) -> dict[str, Any]:
    headers = {}
    for key, value in raw.headers.items():
        lowered = key.lower()
        if key.startswith(":"):
            continue
        if lowered in {"host", "content-length"}:
            continue
        headers[key] = value
    body = raw.post_data.encode("utf-8") if raw.post_data is not None else None
    request = urllib.request.Request(
        raw.url,
        data=body,
        method=raw.method,
        headers=headers,
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            response.read(4096)
            return {
                "status": int(response.status),
                "durationMs": round((time.monotonic() - started) * 1000),
            }
    except urllib.error.HTTPError as exc:
        return {
            "status": int(exc.code),
            "durationMs": round((time.monotonic() - started) * 1000),
        }
    except Exception as exc:
        return {
            "status": None,
            "error": exc.__class__.__name__,
            "durationMs": round((time.monotonic() - started) * 1000),
        }


def performance_for_per_store(
    recorder: NetworkRecorder,
    contract: dict[str, Any],
) -> dict[str, Any] | None:
    if contract.get("type") != "B":
        return None
    raw_requests = [
        recorder.raw_replays[event_id]
        for event_id in contract.get("eventIds", [])
        if event_id in recorder.raw_replays
    ][:5]
    unique = []
    seen = set()
    for raw in raw_requests:
        key = (raw.method, raw.url, raw.post_data)
        if key in seen:
            continue
        seen.add(key)
        unique.append(raw)
    if len(unique) < 2:
        return {
            "note": "insufficient distinct captured per-store requests for bounded replay",
        }

    sequential = [replay_raw(raw) for raw in unique[:3]]
    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(
        max_workers=min(3, len(unique)),
    ) as executor:
        parallel = list(executor.map(replay_raw, unique[:3]))
    wall_ms = round((time.monotonic() - started) * 1000)

    seq_durations = [
        item["durationMs"]
        for item in sequential
        if isinstance(item.get("durationMs"), int)
    ]
    par_durations = [
        item["durationMs"]
        for item in parallel
        if isinstance(item.get("durationMs"), int)
    ]
    return {
        "sequential": {
            "requests": sequential,
            "sumMs": sum(seq_durations),
            "meanMs": round(statistics.mean(seq_durations)) if seq_durations else None,
        },
        "parallel": {
            "requests": parallel,
            "wallMs": wall_ms,
            "meanIndividualMs": (
                round(statistics.mean(par_durations))
                if par_durations
                else None
            ),
        },
    }


def parse_decimal(value: Any) -> str | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        number = Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None
    return format(number, "f") if number >= 0 else None


def nonnegative_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int) and value >= 0:
        return value
    return None


def extract_nuxt_payload(html: str) -> Any:
    match = re.search(
        r'<script\b[^>]*\bid=["\']__NUXT_DATA__["\'][^>]*>(.*?)</script>',
        html,
        flags=re.I | re.S,
    )
    if not match:
        raise ValueError("missing __NUXT_DATA__")
    return json.loads(match.group(1).strip())


def decode_nuxt(root: Any) -> Any:
    if not isinstance(root, list):
        return root
    flattened = root
    visiting: set[int] = set()

    def resolve(value: Any, refs_allowed: bool = True) -> Any:
        if isinstance(value, dict):
            return {key: resolve(child, True) for key, child in value.items()}
        if isinstance(value, list):
            if (
                len(value) >= 2
                and isinstance(value[0], str)
                and value[0] in REFERENCE_WRAPPERS
                and type(value[1]) is int
                and 0 <= value[1] < len(flattened)
            ):
                index = value[1]
                if index in visiting:
                    return value
                visiting.add(index)
                try:
                    return resolve(flattened[index], False)
                finally:
                    visiting.remove(index)
            return [resolve(child, True) for child in value]
        if refs_allowed and type(value) is int and 0 <= value < len(flattened):
            if value in visiting:
                return value
            visiting.add(value)
            try:
                return resolve(flattened[value], False)
            finally:
                visiting.remove(value)
        return value

    return resolve(flattened[0] if flattened else None)


def walk(value: Any):
    yield value
    if isinstance(value, dict):
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def full_lookup_fact(html: str, obik: str, store: str) -> dict[str, Any]:
    try:
        decoded = decode_nuxt(extract_nuxt_payload(html))
    except Exception as exc:
        return {
            "semantic": "malformed_or_missing_nuxt",
            "error": exc.__class__.__name__,
        }

    product_keys = ("skuId", "obik", "productNumber", "articleNumber", "sku")
    for node in walk(decoded):
        if not isinstance(node, dict):
            continue
        if not any(str(node.get(key) or "") == obik for key in product_keys):
            continue
        store_object = node.get("store")
        if not isinstance(store_object, dict):
            continue
        information = store_object.get("information")
        if not isinstance(information, dict):
            continue
        actual_store = str(
            information.get("storeId")
            or information.get("storeNumber")
            or ""
        )
        if actual_store != store:
            continue
        article_data = store_object.get("articleData")
        if not isinstance(article_data, dict):
            return {
                "semantic": "missing_article_data",
                "store": actual_store,
                "stock": None,
                "price": None,
            }
        pricing = article_data.get("pricing")
        return {
            "semantic": "verified_full_lookup",
            "store": actual_store,
            "stock": nonnegative_int(article_data.get("stock")),
            "price": (
                parse_decimal(pricing.get("grossPrice"))
                if isinstance(pricing, dict)
                else None
            ),
        }
    return {
        "semantic": "product_or_store_missing",
        "store": store,
        "stock": None,
        "price": None,
    }


def full_lookup(api_request, obik: str, store: str) -> dict[str, Any]:
    query = urllib.parse.urlencode(
        {
            "storeNumber": store,
            "redirectUrl": f"/p/{obik}",
        },
    )
    response = api_request.get(
        f"{BASE}/api/disc/store/change?{query}",
        headers={
            "User-Agent": USER_AGENT,
            "Accept": HTML_ACCEPT,
            "Accept-Language": ACCEPT_LANGUAGE,
        },
        timeout=30000,
    )
    result = {
        "httpStatus": response.status,
        "contentType": response.headers.get("content-type", "").split(";", 1)[0],
    }
    if response.status == 200:
        result.update(full_lookup_fact(response.text(), obik, store))
    else:
        result["semantic"] = "http_failure"
    return result


def run_probe(obiks: list[str], stores: list[str]) -> dict[str, Any]:
    from playwright.sync_api import sync_playwright

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(
            locale="pl-PL",
            user_agent=USER_AGENT,
            extra_http_headers={
                "Accept-Language": ACCEPT_LANGUAGE,
            },
            viewport={"width": 1280, "height": 900},
        )
        directory = load_store_directory(context, stores)
        verification_api = playwright.request.new_context(
            extra_http_headers={
                "User-Agent": USER_AGENT,
                "Accept": HTML_ACCEPT,
                "Accept-Language": ACCEPT_LANGUAGE,
            },
        )
        missing_stores = sorted(set(stores) - set(directory))
        recorder = NetworkRecorder()
        ui_runs = []
        cross_checks = []

        for obik in obiks:
            page = context.new_page()
            recorder.attach(page)
            recorder.stage = "page_load"
            recorder.obik = obik
            recorder.target_store = None
            page.goto(
                f"{BASE}/p/{obik}",
                wait_until="domcontentloaded",
                timeout=45000,
            )
            page.wait_for_timeout(1800)
            consent = dismiss_consent(page)
            page.wait_for_timeout(500)

            recorder.stage = f"availability_open:{obik}"
            opened = click_named_control(page, AVAILABILITY_PATTERNS)
            page.wait_for_timeout(1200)
            product_run = {
                "obik": obik,
                "finalUrlPath": urllib.parse.urlsplit(page.url).path,
                "consentControl": consent,
                "availabilityControl": opened,
                "visibleAfterOpen": visible_text(page, 1000),
                "storeSelections": [],
            }

            for store_number in stores:
                store = directory.get(store_number)
                if store is None:
                    product_run["storeSelections"].append(
                        {
                            "requestedStore": store_number,
                            "error": "missing_from_directory",
                        },
                    )
                    continue
                recorder.stage = f"store_select:{obik}:{store_number}"
                recorder.target_store = store_number
                selection = choose_store(page, store)
                product_run["storeSelections"].append(selection)
                page.wait_for_timeout(1200)
                cross_checks.append(
                    {
                        "obik": obik,
                        "store": store_number,
                        "fullLookup": full_lookup(verification_api, obik, store_number),
                    },
                )
                recorder.stage = f"availability_reopen:{obik}:{store_number}"
                click_named_control(page, AVAILABILITY_PATTERNS)
                page.wait_for_timeout(700)

            ui_runs.append(product_run)
            page.close()

        contract = classify_contract(recorder.events)
        candidates = safe_event_candidates(recorder.events)

        no_cookie = []
        for event in candidates[:12]:
            raw = recorder.raw_replays.get(event["id"])
            if raw is None:
                continue
            no_cookie.append(
                {
                    "eventId": event["id"],
                    "originalHadCookie": event["request"]["hasCookie"],
                    "replay": no_cookie_replay(raw),
                },
            )

        performance = performance_for_per_store(recorder, contract)

        summary = {
            "inputs": {
                "obiks": obiks,
                "stores": stores,
            },
            "storeDirectory": {
                "endpoint": "/api/disc/store/locator/country/PL",
                "selectedStores": list(directory.values()),
                "missingRequestedStores": missing_stores,
            },
            "uiRuns": ui_runs,
            "contract": contract,
            "candidateNetworkEvents": candidates,
            "cookieRequirementProbe": no_cookie,
            "crossChecks": cross_checks,
            "performance": performance,
            "eventCounts": {
                "captured": len(recorder.events),
                "candidates": len(candidates),
            },
        }
        verification_api.dispose()
        context.close()
        browser.close()
        return summary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--obiks",
        default="3496072,6743009,7156243",
    )
    parser.add_argument(
        "--stores",
        default="075,074,078",
    )
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    obiks = [item.strip() for item in args.obiks.split(",") if item.strip()]
    stores = [item.strip() for item in args.stores.split(",") if item.strip()]
    if len(obiks) < 3 or len(stores) < 3:
        raise SystemExit("at least 3 OBIKs and 3 stores are required")
    for obik in obiks:
        validate_obik(obik)
    for store in stores:
        validate_store(store)

    summary = run_probe(obiks, stores)
    destination = Path(args.out)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(
        json.dumps(
            {
                "contract": summary["contract"],
                "eventCounts": summary["eventCounts"],
                "candidateNetworkEvents": summary["candidateNetworkEvents"],
                "cookieRequirementProbe": summary["cookieRequirementProbe"],
                "crossChecks": summary["crossChecks"],
                "performance": summary["performance"],
                "uiRuns": summary["uiRuns"],
            },
            ensure_ascii=False,
            indent=2,
        ),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
