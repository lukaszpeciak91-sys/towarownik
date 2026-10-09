#!/usr/bin/env python3
"""Research-only live KWANT frontend contract probe.

The live runner uses Playwright lazily so deterministic unit tests do not
require Playwright or network access.
"""

from __future__ import annotations

import argparse
import json
import re
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import parse_qsl, quote, unquote, urljoin, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

KWANT_ORIGIN = "https://kwant.net.pl"
KWANT_HOST = "kwant.net.pl"
KWANT_SERVICES_HOST = "services.kwant.net.pl"
KWANT_SEARCH_API_PATH = "/api/front/search-engine/page"
UNKNOWN = "UNKNOWN"
REDACTED = "REDACTED"
DEPARTMENT_COOKIE_NAME = "departmentCookie"
MAX_DEPARTMENT_COOKIE_ID_LENGTH = 20
MAX_NETWORK_RECORDS = 350
MAX_RESULT_LINKS = 25
MAX_TECHNICAL_LINES = 60
MAX_COOKIE_BUNDLE_EXCERPT_CHARS = 5000
SECRET_KEY_RE = re.compile(
    r"(token|secret|password|passwd|cookie|session|auth|csrf|xsrf|jwt|bearer|key)",
    re.IGNORECASE,
)
SAFE_VALUE_KEY_RE = re.compile(
    r"(query|search|term|phrase|q$|depstock$|branch|oddzial|oddzia[lł]|warehouse|magazyn|store|shop|product|article|ean|sku)",
    re.IGNORECASE,
)
RELEVANT_RESOURCE_TYPES = {"document", "xhr", "fetch"}
NO_RESULTS_RE = re.compile(
    r"(brak\s+wynik|nie\s+znalezion|0\s+wynik)",
    re.IGNORECASE,
)
PRODUCT_PATH_RE = re.compile(r"^/produkt/[^?#]*/?(?:$|[?#])", re.IGNORECASE)
BRANCH_PAGE_ID_RE = re.compile(r"/(?:[^/]+/)*(\d+)/?$")
BRANCH_EVIDENCE_KEY_RE = re.compile(
    r"(branch|oddzial|oddzia[lł]|warehouse|magazyn|store)",
    re.IGNORECASE,
)
PUBLIC_DEPARTMENT_ID_RE = re.compile(
    r"(?:"
    r"[0-9]{1,8}|"
    r"[A-Za-z]{1,4}[0-9]{1,8}|"
    r"[0-9]{1,8}[A-Za-z]{1,4}|"
    r"(?=[A-Za-z0-9_-]*[0-9])"
    r"(?:[A-Za-z]{1,4}|[0-9]{1,8})"
    r"(?:[-_](?:[A-Za-z]{1,4}|[0-9]{1,8})){1,2}"
    r")"
)
BRANCH_SELECTION_PATH_RE = re.compile(
    r"(?:(?:select|set|change|choose|wyb)[^?]*"
    r"(?:branch|oddzial|oddzia[lł]|warehouse|magazyn|store)|"
    r"(?:branch|oddzial|oddzia[lł]|warehouse|magazyn|store)[^?]*"
    r"(?:select|set|change|choose|wyb))",
    re.IGNORECASE,
)
SEARCH_VALUE_KEY_RE = re.compile(
    r"(query|search|term|phrase|q$|article|ean|sku|product)",
    re.IGNORECASE,
)

CONFIRMED_DEPARTMENT_COOKIE_FIELDS = (
    "department_stock_id",
    "department_stock_name",
    "department_stock_postcode",
    "department_stock_street",
)
PUBLIC_BRANCH_FIELD_FOR_COOKIE_FIELD = {
    "department_stock_id": "department_id",
    "department_stock_name": "name",
    "department_stock_postcode": "postcode",
    "department_stock_street": "street",
}


@dataclass
class NetworkRecorder:
    action: str = "baseline"
    records: list[dict[str, Any]] = field(default_factory=list)
    _request_indices: dict[int, int] = field(default_factory=dict)

    def set_action(self, action: str) -> None:
        self.action = action

    def on_request(self, request: Any) -> None:
        if len(self.records) >= MAX_NETWORK_RECORDS:
            return
        if getattr(request, "resource_type", None) not in RELEVANT_RESOURCE_TYPES:
            return
        expected, safe_url = sanitize_kwant_network_url(
            getattr(request, "url", "")
        )
        if not expected:
            return

        method = str(getattr(request, "method", "GET")).upper()
        headers = getattr(request, "headers", {}) or {}
        post_data = getattr(request, "post_data", None)
        parsed_safe_url = urlsplit(safe_url)
        record = {
            "action": self.action,
            "method": method,
            "host": parsed_safe_url.hostname,
            "path": parsed_safe_url.path or "/",
            "query": sanitized_query(
                getattr(request, "url", ""),
                action=self.action,
            ),
            "body": sanitize_body_shape(
                post_data,
                content_type=str(headers.get("content-type", "")),
                action=self.action,
            ),
            "resourceType": getattr(request, "resource_type", None),
            "status": None,
            "contentType": None,
        }
        self.records.append(record)
        self._request_indices[id(request)] = len(self.records) - 1

    def on_response(self, response: Any) -> None:
        request = getattr(response, "request", None)
        if request is None:
            return
        if getattr(request, "resource_type", None) not in RELEVANT_RESOURCE_TYPES:
            return
        expected, safe_url = sanitize_kwant_network_url(
            getattr(response, "url", "")
        )
        if not expected:
            return

        index = self._request_indices.get(id(request))
        if index is None or index >= len(self.records):
            return

        headers = getattr(response, "headers", {}) or {}
        self.records[index]["status"] = getattr(response, "status", None)
        self.records[index]["contentType"] = safe_content_type(
            str(headers.get("content-type", ""))
        )


def validate_inputs(
    product_query: str,
    branch_label: str,
    text_query: str,
    numeric_product_id: str = "580",
) -> None:
    for label, value, limit in (
        ("product query", product_query, 120),
        ("branch label", branch_label, 120),
        ("text query", text_query, 160),
    ):
        if not value or not value.strip():
            raise ValueError(f"{label} must not be blank")
        if len(value) > limit:
            raise ValueError(f"{label} is too long")
        if any(ord(char) < 32 for char in value):
            raise ValueError(f"{label} contains control characters")
    if not re.fullmatch(r"[0-9]{1,12}", numeric_product_id.strip()):
        raise ValueError("numeric product id must contain 1-12 digits")


class NumericRouteRedirectRecorder(HTTPRedirectHandler):
    def __init__(self) -> None:
        super().__init__()
        self.redirects: list[dict[str, Any]] = []

    def redirect_request(
        self,
        req: Any,
        fp: Any,
        code: int,
        msg: str,
        headers: Any,
        newurl: str,
    ) -> Any:
        source_ok, source = sanitize_kwant_url(req.full_url)
        target_ok, target = sanitize_kwant_url(newurl)
        self.redirects.append(
            {
                "status": code,
                "fromPath": urlsplit(source).path if source_ok else REDACTED,
                "toPath": urlsplit(target).path if target_ok else REDACTED,
            }
        )
        return super().redirect_request(
            req,
            fp,
            code,
            msg,
            headers,
            newurl,
        )


def next_data_product_id(html: str) -> str:
    match = re.search(
        r"""<script[^>]+id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
        html,
        re.IGNORECASE | re.DOTALL,
    )
    if not match:
        return UNKNOWN
    try:
        root = json.loads(match.group(1))
        product = root["props"]["pageProps"]["product"]
        product_id = product.get("id")
    except (KeyError, TypeError, json.JSONDecodeError):
        return UNKNOWN
    rendered = str(product_id).strip()
    return rendered if re.fullmatch(r"[0-9]{1,12}", rendered) else UNKNOWN


def probe_numeric_product_route_http(product_id: str) -> dict[str, Any]:
    normalized_id = product_id.strip()
    if not re.fullmatch(r"[0-9]{1,12}", normalized_id):
        raise ValueError("numeric product id must contain 1-12 digits")

    requested_url = f"{KWANT_ORIGIN}/produkt/{normalized_id}"
    recorder = NumericRouteRedirectRecorder()
    opener = build_opener(recorder)
    request = Request(
        requested_url,
        headers={
            "User-Agent": (
                "Mozilla/5.0 (Linux; Android 13; Mobile) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/140.0.0.0 Mobile Safari/537.36"
            ),
            "Accept": "text/html,application/xhtml+xml",
            "Accept-Language": "pl-PL,pl;q=0.9",
        },
        method="GET",
    )

    with opener.open(request, timeout=30) as response:
        final_status = int(getattr(response, "status", response.getcode()))
        final_raw_url = response.geturl()
        body = response.read().decode("utf-8", errors="replace")

    final_ok, final_url = sanitize_kwant_url(final_raw_url)
    parsed_product_id = next_data_product_id(body)
    redirect_chain = list(recorder.redirects)
    initial_status = (
        redirect_chain[0]["status"]
        if redirect_chain
        else final_status
    )
    return {
        "requestedPath": f"/produkt/{normalized_id}",
        "responseStatus": initial_status,
        "redirectChain": redirect_chain,
        "finalStatus": final_status,
        "finalUrl": final_url if final_ok else REDACTED,
        "finalParsedProductId": parsed_product_id,
        "matchesRequestedProductId": parsed_product_id == normalized_id,
    }


def sanitize_kwant_network_url(raw_url: str) -> tuple[bool, str]:
    try:
        parsed = urlsplit(raw_url)
        port = parsed.port
    except (TypeError, ValueError):
        return False, "REDACTED_INVALID_URL"

    expected = bool(
        parsed.scheme == "https"
        and parsed.hostname in {KWANT_HOST, KWANT_SERVICES_HOST}
        and port in (None, 443)
        and parsed.username is None
        and parsed.password is None
    )
    if not expected:
        return False, "REDACTED_UNEXPECTED_HOST"

    return True, urlunsplit(
        (
            "https",
            parsed.hostname or "",
            parsed.path or "/",
            "",
            "",
        )
    )


def sanitize_kwant_url(raw_url: str) -> tuple[bool, str]:
    try:
        parsed = urlsplit(raw_url)
        port = parsed.port
    except (TypeError, ValueError):
        return False, "REDACTED_INVALID_URL"

    expected = bool(
        parsed.scheme == "https"
        and parsed.hostname == KWANT_HOST
        and port in (None, 443)
        and parsed.username is None
        and parsed.password is None
    )
    if not expected:
        return False, "REDACTED_UNEXPECTED_HOST"

    return True, urlunsplit(("https", KWANT_HOST, parsed.path or "/", "", ""))


def sanitize_public_url(raw_url: str, base_url: str = KWANT_ORIGIN) -> str:
    try:
        absolute = urljoin(base_url, raw_url)
        parsed = urlsplit(absolute)
        port = parsed.port
    except (TypeError, ValueError):
        return "REDACTED_INVALID_URL"

    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or port not in (None, 443)
        or parsed.username is not None
        or parsed.password is not None
    ):
        return "REDACTED_INVALID_URL"

    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path or "/", "", ""))


def strip_query(safe_url: str) -> str:
    parsed = urlsplit(safe_url)
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path, "", ""))


def sanitized_query(raw_url: str, action: str = "") -> dict[str, Any]:
    try:
        pairs = parse_qsl(urlsplit(raw_url).query, keep_blank_values=True)
    except ValueError:
        return {"names": [], "safeValues": {}}

    names: list[str] = []
    safe_values: dict[str, str] = {}
    for key, value in pairs:
        if key not in names:
            names.append(key)
        if SECRET_KEY_RE.search(key):
            continue
        if SAFE_VALUE_KEY_RE.search(key) and is_safe_public_value(value):
            safe_values[key] = value[:160]
    return {"names": names[:50], "safeValues": safe_values}


def sanitize_body_shape(
    post_data: str | None,
    *,
    content_type: str = "",
    action: str = "",
) -> dict[str, Any] | None:
    if not post_data:
        return None

    fields: list[str] = []
    safe_values: dict[str, Any] = {}

    def collect(mapping: dict[str, Any]) -> None:
        for key, value in mapping.items():
            key_text = str(key)
            if key_text not in fields:
                fields.append(key_text)
            if SECRET_KEY_RE.search(key_text):
                continue
            if SAFE_VALUE_KEY_RE.search(key_text) and isinstance(
                value, (str, int, float, bool)
            ):
                rendered = str(value)
                if is_safe_public_value(rendered):
                    safe_values[key_text] = value

    stripped = post_data.strip()
    try:
        if "json" in content_type.lower() or stripped.startswith(("{", "[")):
            parsed = json.loads(stripped)
            if isinstance(parsed, dict):
                collect(parsed)
            else:
                return {
                    "encoding": "json",
                    "type": type(parsed).__name__,
                    "fields": [],
                    "safeValues": {},
                }
            return {
                "encoding": "json",
                "fields": fields[:80],
                "safeValues": safe_values,
            }
    except json.JSONDecodeError:
        pass

    try:
        form = dict(parse_qsl(stripped, keep_blank_values=True))
        if form:
            collect(form)
            return {
                "encoding": "form",
                "fields": fields[:80],
                "safeValues": safe_values,
            }
    except ValueError:
        pass

    return {
        "encoding": "opaque",
        "length": len(post_data),
        "fields": [],
        "safeValues": {},
    }


def is_safe_public_value(value: str) -> bool:
    if len(value) > 180:
        return False
    if any(ord(char) < 32 for char in value):
        return False
    if re.search(r"(?i)(bearer\s|eyJ[a-zA-Z0-9_-]{10,}\.|[a-f0-9]{32,})", value):
        return False
    return True


def safe_content_type(raw: str) -> str | None:
    if not raw:
        return None
    return raw.split(";", 1)[0].strip().lower()[:100] or None


def cookie_summary(cookies: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for cookie in cookies:
        name = str(cookie.get("name", ""))
        if not name:
            continue
        result.append(
            {
                "name": name[:120],
                "domain": str(cookie.get("domain", ""))[:160],
                "path": str(cookie.get("path", ""))[:160],
                "secure": bool(cookie.get("secure", False)),
                "httpOnly": bool(cookie.get("httpOnly", False)),
                "sameSite": cookie.get("sameSite"),
            }
        )
    return sorted(result, key=lambda item: (item["domain"], item["name"]))


def cookie_state_name(key: str) -> str:
    parts = key.split("|", 2)
    return parts[2] if len(parts) == 3 else ""


def is_department_cookie_state_key(key: str) -> bool:
    return cookie_state_name(key) == DEPARTMENT_COOKIE_NAME


def safe_department_cookie_value(value: str | None) -> str:
    if value is None:
        return UNKNOWN
    rendered = str(value).strip()
    if rendered in {UNKNOWN, REDACTED}:
        return rendered
    if not rendered:
        return UNKNOWN
    if len(rendered) > MAX_DEPARTMENT_COOKIE_ID_LENGTH:
        return REDACTED
    if SECRET_KEY_RE.search(rendered):
        return REDACTED
    if not PUBLIC_DEPARTMENT_ID_RE.fullmatch(rendered):
        return REDACTED
    return rendered


def raw_department_cookie_value(
    cookies: dict[str, str],
) -> str | None:
    values = [
        value
        for key, value in cookies.items()
        if is_department_cookie_state_key(key)
    ]
    return values[0] if len(values) == 1 else None


def parse_department_cookie_object(
    raw_value: str | None,
) -> tuple[dict[str, Any] | None, list[str]]:
    if not raw_value:
        return None, []
    candidates = [
        (raw_value, ["JSON.stringify"]),
        (
            unquote(raw_value),
            ["JSON.stringify", "percent-encoding-by-cookie-helper"],
        ),
    ]
    seen: set[str] = set()
    for candidate, steps in candidates:
        if candidate in seen:
            continue
        seen.add(candidate)
        try:
            parsed = json.loads(candidate)
        except (TypeError, json.JSONDecodeError):
            continue
        if isinstance(parsed, dict):
            return parsed, steps
    return None, []


def json_object_around_marker(
    text: str,
    marker: str,
) -> dict[str, Any] | None:
    marker_index = text.find(marker)
    if marker_index < 0:
        return None
    for start in range(marker_index, max(-1, marker_index - 5000), -1):
        if text[start] != "{":
            continue
        decoder = json.JSONDecoder()
        try:
            parsed, consumed = decoder.raw_decode(text[start:])
        except json.JSONDecodeError:
            continue
        if (
            isinstance(parsed, dict)
            and marker in text[start : start + consumed]
        ):
            return parsed
    return None


def public_branch_object_from_html(
    html: str,
    branch_page_identifier: str,
) -> dict[str, Any] | None:
    if (
        not branch_page_identifier
        or branch_page_identifier == UNKNOWN
    ):
        return None
    marker = f'"department_id":{branch_page_identifier}'
    return json_object_around_marker(html, marker)


def evidenced_department_cookie_fields(
    excerpt: str,
) -> list[str]:
    compact = re.sub(r"\s+", "", excerpt or "")
    if "setUnauthDepartmentCookie" not in compact:
        return []
    ordered = "|".join(
        re.escape(field)
        for field in CONFIRMED_DEPARTMENT_COOKIE_FIELDS
    )
    if not re.search(
        rf"department_stock_id[^{{}}]{{0,700}}"
        rf"department_stock_name[^{{}}]{{0,700}}"
        rf"department_stock_postcode[^{{}}]{{0,700}}"
        rf"department_stock_street",
        compact,
    ):
        return []
    return [
        field
        for field in CONFIRMED_DEPARTMENT_COOKIE_FIELDS
        if re.search(rf"\b{re.escape(field)}\b", excerpt)
    ]


def public_branch_field_mapping(
    evidenced_fields: Iterable[str],
    public_branch: dict[str, Any] | None,
) -> dict[str, str]:
    if not public_branch:
        return {}
    result: dict[str, str] = {}
    for cookie_field in evidenced_fields:
        public_field = PUBLIC_BRANCH_FIELD_FOR_COOKIE_FIELD.get(
            cookie_field
        )
        if (
            public_field is not None
            and public_field in public_branch
        ):
            result[cookie_field] = public_field
    return result


def public_branch_reproduces_cookie_fields(
    cookie_object: dict[str, Any] | None,
    source_mapping: dict[str, str],
    public_branch: dict[str, Any] | None,
) -> bool | None:
    if not cookie_object or not source_mapping or not public_branch:
        return None
    if set(source_mapping) != set(cookie_object):
        return None
    for cookie_field, public_field in source_mapping.items():
        if public_field not in public_branch:
            return None
        if str(cookie_object[cookie_field]) != str(
            public_branch[public_field]
        ):
            return False
    return True


def constructed_cookie_value_matches_observed(
    raw_value: str | None,
    source_mapping: dict[str, str],
    public_branch: dict[str, Any] | None,
) -> bool | None:
    if not raw_value or not source_mapping or not public_branch:
        return None
    if set(source_mapping) != set(
        CONFIRMED_DEPARTMENT_COOKIE_FIELDS
    ):
        return None
    if any(
        public_field not in public_branch
        for public_field in source_mapping.values()
    ):
        return None

    constructed = {
        cookie_field: public_branch[public_field]
        for cookie_field, public_field in source_mapping.items()
    }
    serialized = json.dumps(
        constructed,
        ensure_ascii=False,
        separators=(",", ":"),
    )
    observed_candidates = {raw_value, unquote(raw_value)}
    return serialized in observed_candidates


def safe_department_stock_id(
    cookie_object: dict[str, Any] | None,
    source_mapping: dict[str, str],
    public_branch: dict[str, Any] | None,
    branch_page_identifier: str,
) -> str:
    if (
        not cookie_object
        or not public_branch
        or source_mapping.get("department_stock_id")
        != "department_id"
    ):
        return UNKNOWN
    cookie_value = cookie_object.get("department_stock_id")
    public_value = public_branch.get("department_id")
    if (
        cookie_value is None
        or public_value is None
        or str(cookie_value) != str(public_value)
        or str(public_value) != str(branch_page_identifier)
    ):
        return UNKNOWN
    rendered = str(cookie_value).strip()
    return (
        rendered
        if PUBLIC_DEPARTMENT_ID_RE.fullmatch(rendered)
        else UNKNOWN
    )

def department_cookie_value(
    cookies: dict[str, str],
) -> str:
    values = [
        value
        for key, value in cookies.items()
        if is_department_cookie_state_key(key)
    ]
    if not values:
        return UNKNOWN

    safe_values = [safe_department_cookie_value(value) for value in values]
    if any(value == REDACTED for value in safe_values):
        return REDACTED

    public_values = {
        value
        for value in safe_values
        if value != UNKNOWN
    }
    if len(public_values) == 1:
        return next(iter(public_values))
    return UNKNOWN


def department_cookie_observed(
    changed: dict[str, list[str]],
) -> bool:
    return any(
        is_department_cookie_state_key(key)
        for key in changed.get("cookies", [])
    )


def department_cookie_persisted(
    persisted: dict[str, list[str]],
) -> bool:
    return any(
        is_department_cookie_state_key(key)
        for key in persisted.get("cookies", [])
    )


def storage_key_summary(storage: dict[str, dict[str, str]]) -> dict[str, list[str]]:
    return {
        "localStorage": sorted(storage.get("localStorage", {}).keys())[:120],
        "sessionStorage": sorted(storage.get("sessionStorage", {}).keys())[:120],
    }


def state_changed_keys(
    before_cookies: dict[str, str],
    after_cookies: dict[str, str],
    before_storage: dict[str, dict[str, str]],
    after_storage: dict[str, dict[str, str]],
) -> dict[str, list[str]]:
    cookie_names = sorted(
        key
        for key in set(before_cookies) | set(after_cookies)
        if before_cookies.get(key) != after_cookies.get(key)
    )
    local_names = sorted(
        key
        for key in set(before_storage.get("localStorage", {}))
        | set(after_storage.get("localStorage", {}))
        if before_storage.get("localStorage", {}).get(key)
        != after_storage.get("localStorage", {}).get(key)
    )
    session_names = sorted(
        key
        for key in set(before_storage.get("sessionStorage", {}))
        | set(after_storage.get("sessionStorage", {}))
        if before_storage.get("sessionStorage", {}).get(key)
        != after_storage.get("sessionStorage", {}).get(key)
    )
    return {
        "cookies": cookie_names,
        "localStorage": local_names,
        "sessionStorage": session_names,
    }


def persisted_state_keys(
    after_cookies: dict[str, str],
    reload_cookies: dict[str, str],
    after_storage: dict[str, dict[str, str]],
    reload_storage: dict[str, dict[str, str]],
    changed: dict[str, list[str]],
) -> dict[str, list[str]]:
    return {
        "cookies": [
            key
            for key in changed["cookies"]
            if key in after_cookies
            and after_cookies.get(key) == reload_cookies.get(key)
        ],
        "localStorage": [
            key
            for key in changed["localStorage"]
            if key in after_storage.get("localStorage", {})
            and after_storage["localStorage"].get(key)
            == reload_storage.get("localStorage", {}).get(key)
        ],
        "sessionStorage": [
            key
            for key in changed["sessionStorage"]
            if key in after_storage.get("sessionStorage", {})
            and after_storage["sessionStorage"].get(key)
            == reload_storage.get("sessionStorage", {}).get(key)
        ],
    }


def branch_related_state_keys(
    evidence: dict[str, list[str]],
) -> dict[str, list[str]]:
    return {
        scope: [
            key
            for key in evidence.get(scope, [])
            if BRANCH_EVIDENCE_KEY_RE.search(key)
            or (
                scope == "cookies"
                and is_department_cookie_state_key(key)
            )
        ]
        for scope in ("cookies", "localStorage", "sessionStorage")
    }


def branch_selection_request_observed(
    network: list[dict[str, Any]],
) -> bool:
    for record in network:
        if record.get("action") != "branch:select":
            continue

        path = str(record.get("path", ""))
        if BRANCH_SELECTION_PATH_RE.search(path):
            return True

        for source in (
            record.get("query", {}).get("safeValues", {}),
            (record.get("body") or {}).get("safeValues", {}),
        ):
            if any(
                BRANCH_EVIDENCE_KEY_RE.search(str(key))
                for key in source
            ):
                return True
    return False


def derive_branch_selection_status(
    *,
    branch_click_ok: bool,
    marker_after_select: bool,
    marker_after_reload: bool,
    changed: dict[str, list[str]],
    persisted: dict[str, list[str]],
    network: list[dict[str, Any]],
) -> tuple[bool | None, bool | None]:
    if not branch_click_ok:
        return None, None

    branch_changed = branch_related_state_keys(changed)
    branch_persisted = branch_related_state_keys(persisted)
    selection_observed = bool(
        marker_after_select
        or any(branch_changed.values())
        or branch_selection_request_observed(network)
    )
    selection_persisted = bool(
        marker_after_reload
        or any(branch_persisted.values())
    )

    return (
        True if selection_observed else None,
        True if selection_persisted else None,
    )


def extract_branch_page_identifier(url: str) -> str:
    expected, safe = sanitize_kwant_url(url)
    if not expected:
        return UNKNOWN
    match = BRANCH_PAGE_ID_RE.search(urlsplit(safe).path)
    return match.group(1) if match else UNKNOWN


def infer_backend_branch_identifier(
    network: list[dict[str, Any]],
) -> str:
    candidates: list[str] = []
    for record in network:
        if record.get("action") != "branch:select":
            continue
        for source in (
            record.get("query", {}).get("safeValues", {}),
            (record.get("body") or {}).get("safeValues", {}),
        ):
            for key, value in source.items():
                if re.search(
                    r"(branch|oddzial|oddzia[lł]|warehouse|magazyn|store)",
                    key,
                    re.IGNORECASE,
                ):
                    rendered = str(value).strip()
                    if rendered and rendered not in candidates:
                        candidates.append(rendered)
    return candidates[0] if len(candidates) == 1 else UNKNOWN


def normalize_text(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def normalize_branch_identity(value: str) -> str:
    decoded = unquote(value or "").translate(
        str.maketrans({"ł": "l", "Ł": "L"})
    )
    ascii_text = "".join(
        char
        for char in unicodedata.normalize("NFKD", decoded)
        if not unicodedata.combining(char)
    )
    return normalize_text(
        re.sub(r"[^a-z0-9]+", " ", ascii_text.lower())
    )


def branch_label_matches(
    branch_label: str,
    *,
    link_text: str = "",
    page_url: str = "",
    card_text: str = "",
) -> bool:
    wanted = normalize_branch_identity(branch_label)
    if not wanted:
        return False
    haystacks = (
        normalize_branch_identity(link_text),
        normalize_branch_identity(urlsplit(page_url).path),
        normalize_branch_identity(card_text),
    )
    return any(
        re.search(rf"\b{re.escape(wanted)}\b", value)
        for value in haystacks
        if value
    )


def canonical_branch_page_url(
    raw_url: str,
    base_url: str = KWANT_ORIGIN,
) -> str:
    safe = sanitize_public_url(raw_url, base_url)
    if safe.startswith("REDACTED_"):
        return ""
    expected, kwant_safe = sanitize_kwant_url(safe)
    if not expected:
        return ""
    parsed = urlsplit(kwant_safe)
    decoded_path = unquote(parsed.path or "/")
    canonical_path = quote(
        decoded_path.rstrip("/") or "/",
        safe="/:@-._~",
    )
    return urlunsplit(("https", KWANT_HOST, canonical_path, "", ""))


def unique_branch_page_urls(
    raw_urls: Iterable[str],
    base_url: str = KWANT_ORIGIN,
) -> list[str]:
    unique: list[str] = []
    for raw_url in raw_urls:
        canonical = canonical_branch_page_url(raw_url, base_url)
        if canonical and canonical not in unique:
            unique.append(canonical)
    return unique


def target_branch_page_path(branch_page_url: str) -> str:
    canonical = canonical_branch_page_url(branch_page_url)
    if not canonical:
        return UNKNOWN
    return urlsplit(canonical).path or "/"


def branch_target_candidate_valid(
    candidate: dict[str, Any],
    branch_label: str,
) -> bool:
    page_url = canonical_branch_page_url(
        str(candidate.get("pageUrl", ""))
    )
    if not page_url:
        return False
    if not branch_label_matches(
        branch_label,
        link_text=str(candidate.get("linkText", "")),
        page_url=page_url,
        card_text=str(candidate.get("cardText", "")),
    ):
        return False
    if not branch_label_matches(
        branch_label,
        card_text=str(candidate.get("cardText", "")),
    ):
        return False
    branch_urls = unique_branch_page_urls(
        [
            str(value)
            for value in candidate.get("branchPageUrls", [])
            if isinstance(value, str)
        ]
    )
    if branch_urls != [page_url]:
        return False
    return int(candidate.get("selectButtonCount", 0)) >= 1


def resolve_unique_branch_candidate(
    candidates: list[dict[str, Any]],
    branch_label: str,
) -> dict[str, Any] | None:
    matching = [
        candidate
        for candidate in candidates
        if branch_target_candidate_valid(candidate, branch_label)
    ]
    by_branch_url: dict[str, dict[str, Any]] = {}
    for candidate in matching:
        canonical = canonical_branch_page_url(
            str(candidate.get("pageUrl", ""))
        )
        if canonical and canonical not in by_branch_url:
            by_branch_url[canonical] = candidate
    return (
        next(iter(by_branch_url.values()))
        if len(by_branch_url) == 1
        else None
    )


def parse_number_text(value: str) -> str:
    return value.replace("\xa0", " ").strip()


def extract_label_value(body_text: str, labels: list[str]) -> str:
    lines = [normalize_text(line) for line in body_text.splitlines()]
    lines = [line for line in lines if line]
    normalized_labels = [re.compile(label, re.IGNORECASE) for label in labels]
    for index, line in enumerate(lines):
        for label in normalized_labels:
            direct = re.match(
                rf"^{label.pattern}\s*[:：]\s*(.+)$",
                line,
                re.IGNORECASE,
            )
            if direct and direct.group(1).strip():
                return direct.group(1).strip()
            if (
                label.fullmatch(line.rstrip(":：").strip())
                and index + 1 < len(lines)
            ):
                return lines[index + 1]
    return UNKNOWN


def extract_stock_value(body_text: str, label_pattern: str) -> str:
    match = re.search(
        rf"{label_pattern}[ \t]*:[ \t]*([0-9][0-9 \t\u00a0.,]*)[ \t]*([A-Za-zĄĆĘŁŃÓŚŹŻąćęłńóśźż.]*)",
        body_text,
        re.IGNORECASE,
    )
    if not match:
        return UNKNOWN
    amount = normalize_text(match.group(1))
    unit = normalize_text(match.group(2))
    return normalize_text(f"{amount} {unit}")


def extract_selected_branch_stock(body_text: str, branch_label: str) -> str:
    escaped = re.escape(branch_label)
    patterns = (
        rf"{escaped}[^\n]{{0,80}}?([0-9][0-9\s.,]*)\s*(szt\.?|opak\.?|m|mb|kpl\.?)",
        rf"(?:oddzia[lł]|magazyn)[^\n]{{0,50}}?{escaped}[^\n]{{0,80}}?([0-9][0-9\s.,]*)\s*(szt\.?|opak\.?|m|mb|kpl\.?)",
    )
    for pattern in patterns:
        match = re.search(pattern, body_text, re.IGNORECASE)
        if match:
            return normalize_text(f"{match.group(1)} {match.group(2)}")
    return UNKNOWN


def extract_price(body_text: str) -> str:
    match = re.search(
        r"([0-9][0-9 \t\u00a0]*[,.][0-9]{2})[ \t]*z[lł][ \t]*brutto",
        body_text,
        re.IGNORECASE,
    )
    return parse_number_text(match.group(1)) if match else UNKNOWN


def extract_technical_data(body_text: str) -> list[str]:
    match = re.search(
        r"(?:^|\n)\s*Specyfikacja\s*(.*?)(?=\n\s*(?:Producent\s*/|Opis\b|Pliki\b|Produkty\b)|\Z)",
        body_text,
        re.IGNORECASE | re.DOTALL,
    )
    if not match:
        return []
    lines = [normalize_text(line) for line in match.group(1).splitlines()]
    return [line for line in lines if line][:MAX_TECHNICAL_LINES]


def flatten_json_ld(value: Any) -> Iterable[dict[str, Any]]:
    if isinstance(value, dict):
        if isinstance(value.get("@graph"), list):
            for child in value["@graph"]:
                yield from flatten_json_ld(child)
        yield value
    elif isinstance(value, list):
        for child in value:
            yield from flatten_json_ld(child)


def product_json_ld(raw_blocks: Iterable[str]) -> dict[str, Any] | None:
    for raw in raw_blocks:
        try:
            parsed = json.loads(raw)
        except (TypeError, json.JSONDecodeError):
            continue
        for item in flatten_json_ld(parsed):
            item_type = item.get("@type")
            types = item_type if isinstance(item_type, list) else [item_type]
            if any(str(value).lower() == "product" for value in types if value):
                return item
    return None


def structured_product_fields(
    raw_blocks: Iterable[str],
    page_url: str,
) -> dict[str, Any]:
    product = product_json_ld(raw_blocks)
    if not product:
        return {}

    brand = product.get("brand")
    if isinstance(brand, dict):
        brand = brand.get("name")
    image = product.get("image")
    if isinstance(image, list):
        image = image[0] if image else None

    offers = product.get("offers")
    if isinstance(offers, list):
        offers = offers[0] if offers else None

    return {
        "name": public_scalar(product.get("name")),
        "articleNumber": public_scalar(
            product.get("sku") or product.get("mpn")
        ),
        "ean": public_scalar(
            product.get("gtin13")
            or product.get("gtin")
            or product.get("ean")
        ),
        "manufacturer": public_scalar(brand),
        "imageUrl": (
            sanitize_public_url(str(image), page_url)
            if isinstance(image, str)
            else UNKNOWN
        ),
        "canonicalUrl": (
            sanitize_public_url(
                str(product.get("url") or page_url),
                page_url,
            )
        ),
        "structuredPrice": (
            public_scalar(offers.get("price"))
            if isinstance(offers, dict)
            else UNKNOWN
        ),
    }


def public_scalar(value: Any) -> str:
    if isinstance(value, (str, int, float)) and not isinstance(value, bool):
        rendered = normalize_text(str(value))
        if rendered and is_safe_public_value(rendered):
            return rendered[:500]
    return UNKNOWN


def product_page_identifier(url: str) -> str:
    expected, safe = sanitize_kwant_url(url)
    if not expected:
        return UNKNOWN
    match = re.search(r"-(\d+)/?$", urlsplit(safe).path)
    return match.group(1) if match else UNKNOWN


def product_snapshot(
    *,
    page_url: str,
    body_text: str,
    json_ld_blocks: Iterable[str],
    branch_label: str,
) -> dict[str, Any]:
    structured = structured_product_fields(json_ld_blocks, page_url)

    article = extract_label_value(
        body_text,
        [r"Kod\s+produktu", r"Nr\s+kat\.?"],
    )
    ean = extract_label_value(body_text, [r"EAN"])
    manufacturer = extract_label_value(
        body_text,
        [r"Producent"],
    )
    canonical = structured.get("canonicalUrl", UNKNOWN)
    if canonical == UNKNOWN:
        expected, safe = sanitize_kwant_url(page_url)
        canonical = safe if expected else UNKNOWN

    return {
        "name": coalesce(
            structured.get("name"),
            extract_heading(body_text),
        ),
        "articleNumber": coalesce(
            article,
            structured.get("articleNumber"),
        ),
        "ean": coalesce(ean, structured.get("ean")),
        "manufacturer": coalesce(
            manufacturer,
            structured.get("manufacturer"),
        ),
        "productId": product_page_identifier(page_url),
        "technicalData": extract_technical_data(body_text),
        "imageUrl": structured.get("imageUrl", UNKNOWN),
        "canonicalUrl": canonical,
        "price": coalesce(
            extract_price(body_text),
            structured.get("structuredPrice"),
        ),
        "priceScope": "ONLINE"
        if coalesce(
            extract_price(body_text),
            structured.get("structuredPrice"),
        )
        != UNKNOWN
        else UNKNOWN,
        "selectedBranchStock": extract_selected_branch_stock(
            body_text, branch_label
        ),
        "centralStock": extract_stock_value(
            body_text,
            r"Centrala",
        ),
        "aggregateBranchStock": extract_stock_value(
            body_text,
            r"W\s+oddzia[lł]ach",
        ),
    }


def extract_heading(body_text: str) -> str:
    lines = [normalize_text(line) for line in body_text.splitlines()]
    for line in lines:
        if line and len(line) > 10 and "wyłącznik" in line.lower():
            return line[:500]
    return UNKNOWN


def coalesce(*values: Any) -> Any:
    for value in values:
        if value not in (None, "", UNKNOWN, []):
            return value
    return UNKNOWN


def normalized_evidence_value(value: Any) -> str:
    return normalize_text(str(value)).casefold()


def search_request_observed(
    network: list[dict[str, Any]],
    *,
    action: str,
    query: str,
) -> bool:
    expected = normalized_evidence_value(query)
    if not expected:
        return False

    for record in network:
        if record.get("action") != action:
            continue
        if record.get("method") != "POST":
            continue
        if record.get("host") != KWANT_SERVICES_HOST:
            continue
        if record.get("path") != KWANT_SEARCH_API_PATH:
            continue
        safe_values = (record.get("body") or {}).get("safeValues", {})
        if normalized_evidence_value(safe_values.get("q", "")) == expected:
            return True
    return False


def query_reflected_in_url(raw_url: str, query: str) -> bool:
    expected = normalized_evidence_value(query)
    if not expected:
        return False
    try:
        values = [
            value
            for key, value in parse_qsl(
                urlsplit(raw_url).query,
                keep_blank_values=True,
            )
            if SEARCH_VALUE_KEY_RE.search(key)
        ]
    except ValueError:
        return False
    return any(
        normalized_evidence_value(value) == expected
        for value in values
    )


def safe_search_status(
    *,
    interaction_ok: bool,
    target_found: bool,
    query_specific_evidence: bool,
    any_products: bool,
    body_text: str,
) -> str:
    if interaction_ok and NO_RESULTS_RE.search(body_text or ""):
        return "UNSUPPORTED"
    if (
        interaction_ok
        and query_specific_evidence
        and (target_found or any_products)
    ):
        return "SUPPORTED"
    return UNKNOWN


def empty_search_result(query: str) -> dict[str, Any]:
    return {
        "query": query,
        "status": UNKNOWN,
        "reportedResultCount": UNKNOWN,
        "productIdentifiers": [],
        "productUrls": [],
    }


def build_safe_summary(
    *,
    requested_branch_label: str,
    branch_page_url: str = "",
    target_branch_resolved: bool = False,
    selection_observed: bool | None = None,
    selection_persisted: bool | None = None,
    department_cookie_observed: bool | None = None,
    department_cookie_persisted: bool | None = None,
    department_cookie_value_safe: str = UNKNOWN,
    department_stock_id: str = UNKNOWN,
    backend_branch_identifier: str = UNKNOWN,
    searches: dict[str, dict[str, Any]] | None = None,
    product_before: dict[str, Any] | None = None,
    product_after: dict[str, Any] | None = None,
    network: list[dict[str, Any]] | None = None,
    state: dict[str, Any] | None = None,
    frontend_clues: list[dict[str, Any]] | None = None,
    department_cookie_research: dict[str, Any] | None = None,
    numeric_product_route: dict[str, Any] | None = None,
) -> dict[str, Any]:
    searches = searches or {}
    product = product_after or product_before or {}

    return {
        "branch": {
            "requestedLabel": requested_branch_label,
            "targetBranchResolved": target_branch_resolved,
            "targetBranchPageUrl": (
                strip_query(branch_page_url)
                if branch_page_url
                else UNKNOWN
            ),
            "targetBranchPagePath": (
                target_branch_page_path(branch_page_url)
                if branch_page_url
                else UNKNOWN
            ),
            "selectionObserved": tri_state(selection_observed),
            "selectionPersisted": tri_state(selection_persisted),
            "departmentCookieObserved": tri_state(
                department_cookie_observed
            ),
            "departmentCookiePersisted": tri_state(
                department_cookie_persisted
            ),
            "departmentCookieValue": safe_department_cookie_value(
                department_cookie_value_safe
            ),
            "branchPageIdentifier": (
                extract_branch_page_identifier(branch_page_url)
                if branch_page_url
                else UNKNOWN
            ),
            "departmentStockId": department_stock_id or UNKNOWN,
            "backendBranchIdentifier": backend_branch_identifier or UNKNOWN,
        },
        "search": {
            "articleCode": searches.get(
                "articleCode", empty_search_result(UNKNOWN)
            ),
            "ean": searches.get("ean", empty_search_result(UNKNOWN)),
            "text": searches.get("text", empty_search_result(UNKNOWN)),
        },
        "product": {
            "articleNumber": product.get("articleNumber", UNKNOWN),
            "ean": product.get("ean", UNKNOWN),
            "productId": product.get("productId", UNKNOWN),
            "name": product.get("name", UNKNOWN),
            "manufacturer": product.get("manufacturer", UNKNOWN),
            "technicalData": product.get("technicalData", []),
            "imageUrl": product.get("imageUrl", UNKNOWN),
            "canonicalUrl": product.get("canonicalUrl", UNKNOWN),
            "price": product.get("price", UNKNOWN),
            "priceScope": product.get("priceScope", UNKNOWN),
            "selectedBranchStock": product.get(
                "selectedBranchStock", UNKNOWN
            ),
            "centralStock": product.get("centralStock", UNKNOWN),
            "aggregateBranchStock": product.get(
                "aggregateBranchStock", UNKNOWN
            ),
            "beforeBranchSelection": product_before or {},
            "afterBranchSelection": product_after or {},
        },
        "network": network or [],
        "state": state or {},
        "frontendClues": frontend_clues or [],
        "departmentCookieResearch": safe_department_cookie_research(
            department_cookie_research
        ),
        "numericProductRoute": numeric_product_route or {
            "requestedPath": UNKNOWN,
            "responseStatus": UNKNOWN,
            "redirectChain": [],
            "finalStatus": UNKNOWN,
            "finalUrl": UNKNOWN,
            "finalParsedProductId": UNKNOWN,
            "matchesRequestedProductId": UNKNOWN,
        },
    }


def tri_state(value: bool | None) -> str | bool:
    if value is None:
        return UNKNOWN
    return value


def write_safe_summary(summary: dict[str, Any], out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    branch = summary["branch"]
    search = summary["search"]
    product = summary["product"]
    lines = [
        "KWANT LIVE CONTRACT SUMMARY",
        "",
        "BRANCH",
        f"requestedLabel={branch['requestedLabel']}",
        f"targetBranchResolved={format_scalar(branch['targetBranchResolved'])}",
        f"targetBranchPagePath={branch['targetBranchPagePath']}",
        f"branchPageIdentifier={branch['branchPageIdentifier']}",
        f"departmentStockId={branch['departmentStockId']}",
        f"selectionObserved={format_scalar(branch['selectionObserved'])}",
        f"selectionPersisted={format_scalar(branch['selectionPersisted'])}",
        f"departmentCookieObserved={format_scalar(branch['departmentCookieObserved'])}",
        f"departmentCookiePersisted={format_scalar(branch['departmentCookiePersisted'])}",
        f"departmentCookieValue={format_scalar(branch['departmentCookieValue'])}",
        f"backendBranchIdentifier={branch['backendBranchIdentifier']}",
        "",
        "DEPARTMENT COOKIE CONSTRUCTOR",
        (
            "departmentCookieConstructorFound="
            f"{format_scalar(summary['departmentCookieResearch'].get('departmentCookieConstructorFound', UNKNOWN))}"
        ),
        (
            "constructorSourcePath="
            f"{summary['departmentCookieResearch'].get('constructorSourcePath', UNKNOWN)}"
        ),
        (
            "valueFormat="
            f"{format_scalar(summary['departmentCookieResearch'].get('valueFormat', UNKNOWN))}"
        ),
        (
            "sourceBranchFields="
            f"{format_scalar(summary['departmentCookieResearch'].get('sourceBranchFields', UNKNOWN))}"
        ),
        (
            "encodingSteps="
            f"{format_scalar(summary['departmentCookieResearch'].get('encodingSteps', UNKNOWN))}"
        ),
        (
            "cookieOptions="
            f"{format_scalar(summary['departmentCookieResearch'].get('cookieOptions', UNKNOWN))}"
        ),
        (
            "reproducibleFromPublicData="
            f"{format_scalar(summary['departmentCookieResearch'].get('reproducibleFromPublicData', UNKNOWN))}"
        ),
        (
            "constructedValueMatchesObserved="
            f"{format_scalar(summary['departmentCookieResearch'].get('constructedValueMatchesObserved', UNKNOWN))}"
        ),
        "",
        "SEARCH",
        f"articleCode={search['articleCode'].get('status', UNKNOWN)}",
        f"ean={search['ean'].get('status', UNKNOWN)}",
        f"text={search['text'].get('status', UNKNOWN)}",
        "",
        "PRODUCT",
        f"articleNumber={format_scalar(product['articleNumber'])}",
        f"ean={format_scalar(product['ean'])}",
        f"productId={format_scalar(product['productId'])}",
        f"name={format_scalar(product['name'])}",
        f"manufacturer={format_scalar(product['manufacturer'])}",
        f"imageUrl={format_scalar(product['imageUrl'])}",
        f"canonicalUrl={format_scalar(product['canonicalUrl'])}",
        f"price={format_scalar(product['price'])}",
        f"priceScope={format_scalar(product['priceScope'])}",
        f"selectedBranchStock={format_scalar(product['selectedBranchStock'])}",
        f"centralStock={format_scalar(product['centralStock'])}",
        f"aggregateBranchStock={format_scalar(product['aggregateBranchStock'])}",
        "",
        "NUMERIC PRODUCT ROUTE",
        (
            "requestedPath="
            f"{summary['numericProductRoute'].get('requestedPath', UNKNOWN)}"
        ),
        (
            "responseStatus="
            f"{format_scalar(summary['numericProductRoute'].get('responseStatus', UNKNOWN))}"
        ),
        (
            "redirectChain="
            f"{format_scalar(summary['numericProductRoute'].get('redirectChain', []))}"
        ),
        (
            "finalStatus="
            f"{format_scalar(summary['numericProductRoute'].get('finalStatus', UNKNOWN))}"
        ),
        (
            "finalUrl="
            f"{summary['numericProductRoute'].get('finalUrl', UNKNOWN)}"
        ),
        (
            "finalParsedProductId="
            f"{format_scalar(summary['numericProductRoute'].get('finalParsedProductId', UNKNOWN))}"
        ),
        (
            "matchesRequestedProductId="
            f"{format_scalar(summary['numericProductRoute'].get('matchesRequestedProductId', UNKNOWN))}"
        ),
        "",
        "NETWORK",
    ]
    for record in summary.get("network", []):
        query = record.get("query") or {}
        body = record.get("body") or {}
        lines.append(
            "- "
            + " ".join(
                [
                    f"action={record.get('action', UNKNOWN)}",
                    f"method={record.get('method', UNKNOWN)}",
                    f"host={record.get('host', UNKNOWN)}",
                    f"path={record.get('path', UNKNOWN)}",
                    f"queryNames={query.get('names', [])}",
                    f"querySafeValues={query.get('safeValues', {})}",
                    f"bodyFields={body.get('fields', [])}",
                    f"bodySafeValues={body.get('safeValues', {})}",
                    f"status={record.get('status', UNKNOWN)}",
                    f"contentType={record.get('contentType', UNKNOWN)}",
                ]
            )
        )

    locations = summary.get("locationsResearch")
    if isinstance(locations, dict):
        lines.extend([
            "", "PRODUCT LOCATIONS RESEARCH",
            "verifiedProductPage=" + format_scalar(locations.get("verifiedProductPage", False)),
            "directoryBranchCount=" + str(locations.get("directoryBranchCount", 0)),
            "oneShotPerBranchResponseObserved=" + format_scalar(locations.get("oneShotPerBranchResponseObserved", False)),
            "candidateMultiBranchResponseObserved=" + format_scalar(locations.get("candidateMultiBranchResponseObserved", False)),
            "productPageShape=" + format_scalar(locations.get("productPageShape", {})),
            "strongestCandidate=" + format_scalar(locations.get("strongestCandidate")),
            "sumConfirmedObservedBranchStock=" + format_scalar(locations.get("sumConfirmedObservedBranchStock")),
            "parsedAggregateBranchStock=" + format_scalar(locations.get("parsedAggregateBranchStock")),
            "searchRankingEvidence=" + format_scalar(locations.get("searchRankingEvidence", {})),
            "productPageBranchListCandidate=" + format_scalar(locations.get("productPageBranchListCandidate", False)),
            "completeness=" + str(locations.get("completeness", UNKNOWN)),
            "aggregateReconciliation=" + str(locations.get("aggregateReconciliation", "NOT_EVALUATED")),
            "uiActions=" + format_scalar(locations.get("uiActions", [])),
            "availabilitySelector=" + str(locations.get("availabilitySelector", UNKNOWN)),
            "availabilityControlFound=" + format_scalar(locations.get("availabilityControlFound", False)),
            "contractClassification=" + format_scalar(locations.get("contractClassification", {})),
            "availabilityUi=" + format_scalar(locations.get("availabilityUi", {})),
            "filterResearch=" + format_scalar(locations.get("filterResearch", {})),
            "searchRequestDepstockObserved=" + format_scalar(locations.get("searchRequestDepstockObserved", False)),
            "batchPricesResearch=" + format_scalar(locations.get("batchPricesResearch", [])),
            "selectedBranchStock=" + format_scalar(locations.get("selectedBranchStock")),
            "selectedBranchStockSource=" + str(locations.get("selectedBranchStockSource", UNKNOWN)),
            "centralStock=" + format_scalar(locations.get("centralStock")),
            "centralStockSource=" + str(locations.get("centralStockSource", UNKNOWN)),
            "aggregateBranchStockSource=" + str(locations.get("aggregateBranchStockSource", UNKNOWN)),
        ])
        for observed in locations.get("observedResponses", []):
            lines.append("observed=" + format_scalar(observed))
        for request in locations.get("observedRequests", []):
            lines.append("availabilityRequest=" + format_scalar(request))
    (out_dir / "summary.txt").write_text(
        "\n".join(lines) + "\n",
        encoding="utf-8",
    )


def format_scalar(value: Any) -> str:
    if value is True:
        return "true"
    if value is False:
        return "false"
    if value is None:
        return UNKNOWN
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return str(value)


def safe_report_contains_secret(report: Any, secret: str) -> bool:
    return secret in json.dumps(report, ensure_ascii=False)


def browser_storage_values(page: Any) -> dict[str, dict[str, str]]:
    return page.evaluate(
        """
        () => ({
          localStorage: Object.fromEntries(
            Array.from({length: localStorage.length}, (_, i) => {
              const key = localStorage.key(i);
              return [key, localStorage.getItem(key)];
            })
          ),
          sessionStorage: Object.fromEntries(
            Array.from({length: sessionStorage.length}, (_, i) => {
              const key = sessionStorage.key(i);
              return [key, sessionStorage.getItem(key)];
            })
          ),
        })
        """
    )


def cookie_values(context: Any) -> tuple[dict[str, str], list[dict[str, Any]]]:
    cookies = context.cookies()
    values = {
        f"{cookie.get('domain', '')}|{cookie.get('path', '')}|{cookie.get('name', '')}":
        str(cookie.get("value", ""))
        for cookie in cookies
        if cookie.get("name")
    }
    return values, cookies


def branch_marker(page: Any, branch_label: str) -> bool:
    escaped = re.escape(branch_label)
    selector = (
        "[aria-current='true'],[aria-current='page'],"
        "[aria-selected='true'],"
        "[data-state='selected'],[data-state='active'],"
        "[class~='selected'],[class~='active'],[class~='current'],"
        "[class~='wybrany'],[class~='aktywny']"
    )
    try:
        candidates = page.locator(selector)
        count = min(candidates.count(), 40)
        for index in range(count):
            text = normalize_text(
                candidates.nth(index).inner_text(timeout=300)
            )
            if re.search(escaped, text, re.IGNORECASE):
                return True
    except Exception:
        return False
    return False


def search_input(page: Any) -> Any:
    selectors = (
        "input[type='search']",
        "input[placeholder*='Wyszuk']",
        "input[aria-label*='Wyszuk']",
        "input[name*='search']",
        "input[name*='query']",
    )
    for selector in selectors:
        locator = page.locator(selector)
        try:
            count = locator.count()
            for index in range(min(count, 10)):
                candidate = locator.nth(index)
                if candidate.is_visible():
                    return candidate
        except Exception:
            continue
    return None


def product_links(page: Any) -> list[dict[str, str]]:
    result: list[dict[str, str]] = []
    try:
        anchors = page.locator("a[href*='/produkt/']")
        for index in range(min(anchors.count(), MAX_RESULT_LINKS * 3)):
            anchor = anchors.nth(index)
            href = anchor.get_attribute("href") or ""
            text = normalize_text(anchor.inner_text(timeout=300))
            url = sanitize_public_url(href, page.url)
            if url.startswith("REDACTED_"):
                continue
            item = {"url": url, "text": text[:300]}
            if item not in result:
                result.append(item)
            if len(result) >= MAX_RESULT_LINKS:
                break
    except Exception:
        pass
    if PRODUCT_PATH_RE.match(urlsplit(page.url).path):
        expected, safe = sanitize_kwant_url(page.url)
        if expected:
            current = {"url": safe, "text": normalize_text(page.title())[:300]}
            if current not in result:
                result.insert(0, current)
    return result


def result_identifiers(links: list[dict[str, str]]) -> list[str]:
    identifiers: list[str] = []
    for item in links:
        identifier = product_page_identifier(item.get("url", ""))
        if identifier != UNKNOWN and identifier not in identifiers:
            identifiers.append(identifier)
    return identifiers


def reported_result_count(body_text: str) -> str:
    patterns = (
        r"([0-9]+)\s+wynik",
        r"wynik[^0-9]{0,20}([0-9]+)",
    )
    for pattern in patterns:
        match = re.search(pattern, body_text, re.IGNORECASE)
        if match:
            return match.group(1)
    return UNKNOWN


def run_search(
    page: Any,
    recorder: NetworkRecorder,
    *,
    query: str,
    action: str,
    target_hint: str = "",
    raw_dir: Path,
) -> dict[str, Any]:
    recorder.set_action(action)
    page.goto(KWANT_ORIGIN, wait_until="domcontentloaded", timeout=45000)
    page.wait_for_timeout(500)

    input_box = search_input(page)
    interaction_ok = input_box is not None
    if not interaction_ok:
        return empty_search_result(query)

    input_box.fill(query)
    input_box.press("Enter")
    try:
        page.wait_for_load_state("networkidle", timeout=12000)
    except Exception:
        page.wait_for_timeout(1500)

    body = page.locator("body").inner_text(timeout=5000)
    links = product_links(page)
    target_found = bool(
        target_hint
        and any(
            target_hint.lower()
            in f"{item.get('text', '')} {item.get('url', '')}".lower()
            for item in links
        )
    )
    query_specific_evidence = (
        search_request_observed(
            recorder.records,
            action=action,
            query=query,
        )
        or query_reflected_in_url(page.url, query)
    )
    status = safe_search_status(
        interaction_ok=True,
        target_found=target_found,
        query_specific_evidence=query_specific_evidence,
        any_products=bool(links),
        body_text=body,
    )
    raw_dir.mkdir(parents=True, exist_ok=True)
    (raw_dir / f"{action.replace(':', '-')}.html").write_text(
        page.content(),
        encoding="utf-8",
    )
    return {
        "query": query,
        "status": status,
        "reportedResultCount": reported_result_count(body),
        "productIdentifiers": result_identifiers(links),
        "productUrls": [item["url"] for item in links],
        "_links": links,
    }


def branch_page_urls_in_card(
    card: Any,
    *,
    page_url: str,
) -> list[str]:
    branch_links = card.locator(
        "a[href*='hurtownia-elektryczna']"
    )
    raw_urls: list[str] = []
    for index in range(min(branch_links.count(), 12)):
        href = branch_links.nth(index).get_attribute("href") or ""
        raw_urls.append(href)
    return unique_branch_page_urls(raw_urls, page_url)


def matching_branch_buttons(card: Any) -> Any:
    return card.get_by_role(
        "button",
        name=re.compile(
            r"Wybierz\s+oddzia[lł]",
            re.IGNORECASE,
        ),
    )


def first_visible_enabled_button(buttons: Any) -> Any | None:
    for index in range(buttons.count()):
        button = buttons.nth(index)
        try:
            if button.is_visible() and button.is_enabled():
                return button
        except Exception:
            continue
    return None


def branch_card_for_link(
    link: Any,
    *,
    branch_label: str,
    page_url: str,
    expected_page_url: str,
) -> Any | None:
    canonical_expected = canonical_branch_page_url(
        expected_page_url,
        page_url,
    )
    if not canonical_expected:
        return None
    try:
        current = link
        for _ in range(16):
            parent = current.locator("xpath=..")
            if not parent.count():
                return None
            card = parent.first
            card_text = normalize_text(card.inner_text(timeout=500))
            branch_page_urls = branch_page_urls_in_card(
                card,
                page_url=page_url,
            )
            buttons = matching_branch_buttons(card)
            if (
                branch_label_matches(
                    branch_label,
                    card_text=card_text,
                )
                and branch_page_urls == [canonical_expected]
                and buttons.count() >= 1
            ):
                return card
            current = card
    except Exception:
        return None
    return None


def branch_candidate_from_link(
    link: Any,
    *,
    link_index: int,
    page_url: str,
    branch_label: str,
) -> dict[str, Any] | None:
    try:
        raw_href = link.get_attribute("href") or ""
        canonical_url = canonical_branch_page_url(
            raw_href,
            page_url,
        )
        if not canonical_url:
            return None
        link_text = normalize_text(link.inner_text(timeout=300))

        card = branch_card_for_link(
            link,
            branch_label=branch_label,
            page_url=page_url,
            expected_page_url=canonical_url,
        )
        if card is None:
            return None
        card_text = normalize_text(card.inner_text(timeout=500))
        if not branch_label_matches(
            branch_label,
            link_text=link_text,
            page_url=canonical_url,
            card_text=card_text,
        ):
            return None

        branch_page_urls = branch_page_urls_in_card(
            card,
            page_url=page_url,
        )
        buttons = matching_branch_buttons(card)
        return {
            "linkIndex": link_index,
            "linkText": link_text,
            "pageUrl": canonical_url,
            "cardText": card_text,
            "branchPageUrls": branch_page_urls,
            "selectButtonCount": buttons.count(),
        }
    except Exception:
        return None


def enumerate_branch_candidates(
    page: Any,
    branch_label: str,
) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    try:
        links = page.locator(
            "a[href*='hurtownia-elektryczna']"
        )
        for index in range(min(links.count(), 120)):
            candidate = branch_candidate_from_link(
                links.nth(index),
                link_index=index,
                page_url=page.url,
                branch_label=branch_label,
            )
            if candidate is not None:
                result.append(candidate)
    except Exception:
        return []
    return result


def validated_branch_button(
    page: Any,
    *,
    candidate: dict[str, Any],
    branch_label: str,
) -> Any | None:
    try:
        links = page.locator(
            "a[href*='hurtownia-elektryczna']"
        )
        link_index = int(candidate["linkIndex"])
        if link_index < 0 or link_index >= links.count():
            return None
        link = links.nth(link_index)
        refreshed = branch_candidate_from_link(
            link,
            link_index=link_index,
            page_url=page.url,
            branch_label=branch_label,
        )
        if refreshed is None:
            return None

        expected_url = canonical_branch_page_url(
            str(candidate.get("pageUrl", ""))
        )
        refreshed_url = canonical_branch_page_url(
            str(refreshed.get("pageUrl", ""))
        )
        if not expected_url or refreshed_url != expected_url:
            return None
        if not branch_target_candidate_valid(
            refreshed,
            branch_label,
        ):
            return None

        card = branch_card_for_link(
            link,
            branch_label=branch_label,
            page_url=page.url,
            expected_page_url=expected_url,
        )
        if card is None:
            return None

        branch_urls = branch_page_urls_in_card(
            card,
            page_url=page.url,
        )
        if branch_urls != [expected_url]:
            return None

        return first_visible_enabled_button(
            matching_branch_buttons(card)
        )
    except Exception:
        return None


def choose_branch(
    page: Any,
    recorder: NetworkRecorder,
    branch_label: str,
    raw_dir: Path,
) -> tuple[bool, str, bool]:
    recorder.set_action("branch:list")
    page.goto(
        f"{KWANT_ORIGIN}/lista-hurtowni-elektrycznych",
        wait_until="domcontentloaded",
        timeout=45000,
    )
    page.wait_for_timeout(500)

    candidates = enumerate_branch_candidates(
        page,
        branch_label,
    )
    target = resolve_unique_branch_candidate(
        candidates,
        branch_label,
    )
    branch_page_url = (
        str(target.get("pageUrl", ""))
        if target is not None
        else ""
    )
    target_resolved = target is not None

    raw_dir.mkdir(parents=True, exist_ok=True)
    branch_before_html = page.content()
    (raw_dir / "branch-before-select.html").write_text(
        branch_before_html,
        encoding="utf-8",
    )

    if target is None:
        return False, branch_page_url, False

    recorder.set_action("branch:select")
    try:
        targeted_button = validated_branch_button(
            page,
            candidate=target,
            branch_label=branch_label,
        )
        if targeted_button is None:
            return False, branch_page_url, False
        targeted_button.click(timeout=7000)
        try:
            page.wait_for_load_state("networkidle", timeout=10000)
        except Exception:
            page.wait_for_timeout(1200)
        (raw_dir / "branch-after-select.html").write_text(
            page.content(),
            encoding="utf-8",
        )
        return True, branch_page_url, target_resolved
    except Exception:
        return False, branch_page_url, target_resolved


def open_product(
    page: Any,
    recorder: NetworkRecorder,
    *,
    url: str,
    action: str,
    branch_label: str,
    raw_dir: Path,
) -> dict[str, Any]:
    recorder.set_action(action)
    page.goto(url, wait_until="domcontentloaded", timeout=45000)
    try:
        page.wait_for_load_state("networkidle", timeout=10000)
    except Exception:
        page.wait_for_timeout(1200)

    body = page.locator("body").inner_text(timeout=6000)
    blocks = page.locator("script[type='application/ld+json']").all_text_contents()
    snapshot = product_snapshot(
        page_url=page.url,
        body_text=body,
        json_ld_blocks=blocks,
        branch_label=branch_label,
    )
    raw_dir.mkdir(parents=True, exist_ok=True)
    (raw_dir / f"{action.replace(':', '-')}.html").write_text(
        page.content(),
        encoding="utf-8",
    )
    return snapshot


def matching_product_url(
    search_result: dict[str, Any],
    hint: str,
) -> str:
    links = search_result.get("_links", [])
    lowered = hint.lower()
    for item in links:
        if lowered in f"{item.get('text', '')} {item.get('url', '')}".lower():
            return item.get("url", "")
    return ""


def selected_marker_after_reload(page: Any, branch_label: str) -> bool:
    return branch_marker(page, branch_label)


def same_origin_script_sources(page: Any) -> list[str]:
    try:
        raw_sources = page.locator("script[src]").evaluate_all(
            "els => els.map(el => el.src)"
        )
    except Exception:
        return []

    result: list[str] = []
    for source in raw_sources:
        expected, safe = sanitize_kwant_url(str(source))
        if not expected or not safe.endswith(".js"):
            continue
        if safe not in result:
            result.append(safe)
    return result


def bounded_marker_excerpts(
    text: str,
    markers: Iterable[str],
    *,
    radius: int = 1800,
) -> list[tuple[str, str]]:
    result: list[tuple[str, str]] = []
    for marker in markers:
        start_at = 0
        count = 0
        while count < 3:
            index = text.find(marker, start_at)
            if index < 0:
                break
            start = max(0, index - radius)
            end = min(len(text), index + len(marker) + radius)
            result.append((marker, text[start:end]))
            start_at = index + len(marker)
            count += 1
    return result


def bounded_cookie_excerpt(
    text: str,
    marker: str = DEPARTMENT_COOKIE_NAME,
) -> str:
    index = text.find(marker)
    if index < 0:
        return ""
    half = MAX_COOKIE_BUNDLE_EXCERPT_CHARS // 2
    start = max(0, index - half)
    end = min(len(text), index + len(marker) + half)
    return text[start:end]


def analyze_department_cookie_constructor(
    script_path: str,
    excerpt: str,
) -> dict[str, Any]:
    unknown = {
        "departmentCookieConstructorFound": UNKNOWN,
        "constructorSourcePath": UNKNOWN,
        "valueFormat": UNKNOWN,
        "sourceBranchFields": UNKNOWN,
        "encodingSteps": UNKNOWN,
        "cookieOptions": UNKNOWN,
    }
    if DEPARTMENT_COOKIE_NAME not in excerpt:
        return unknown

    compact = re.sub(r"\s+", "", excerpt)
    cookie_var_match = re.search(
        r"(?:let|const|var)([A-Za-z_$][\w$]*)="
        r"[\"']departmentCookie[\"']",
        compact,
    )
    if not cookie_var_match:
        return unknown

    cookie_ref = re.escape(cookie_var_match.group(1))
    getter_reads_cookie = bool(
        re.search(
            rf"(?:getCookie\)\(|getCookie\()"
            rf"{cookie_ref}\)",
            compact,
        )
    )
    getter_parses_json = getter_reads_cookie and "JSON.parse" in compact
    setter_writes_cookie = bool(
        re.search(
            rf"(?:setCookie\)\(|setCookie\()"
            rf"{cookie_ref},JSON\.stringify\(",
            compact,
        )
    )
    setter_has_expires = bool(
        setter_writes_cookie
        and re.search(r"\{expires:[^}]+\}", compact)
    )
    expires_360_days = bool(
        setter_has_expires
        and re.search(r"add\(360,[\"']days[\"']\)", compact)
    )

    if not (
        getter_parses_json
        and setter_writes_cookie
        and setter_has_expires
    ):
        return unknown

    fields = evidenced_department_cookie_fields(excerpt)
    return {
        "departmentCookieConstructorFound": True,
        "constructorSourcePath": script_path or UNKNOWN,
        "valueFormat": "JSON serialized branch object",
        "sourceBranchFields": fields if fields else UNKNOWN,
        "encodingSteps": ["JSON.stringify -> cookie value"],
        "cookieOptions": (
            {"expires": "360 days"}
            if expires_360_days
            else {"expires": UNKNOWN}
        ),
    }

def safe_department_cookie_research(
    research: dict[str, Any] | None,
) -> dict[str, Any]:
    source = research or {}
    allowed = {
        "departmentCookieConstructorFound",
        "constructorSourcePath",
        "valueFormat",
        "sourceBranchFields",
        "encodingSteps",
        "cookieOptions",
        "reproducibleFromPublicData",
        "constructedValueMatchesObserved",
        "backendBranchIdentifier",
        "cookieObjectFieldNames",
        "matchedBundles",
        "scannedSameOriginScriptCount",
    }
    result: dict[str, Any] = {
        "departmentCookieConstructorFound": UNKNOWN,
        "constructorSourcePath": UNKNOWN,
        "valueFormat": UNKNOWN,
        "sourceBranchFields": UNKNOWN,
        "encodingSteps": UNKNOWN,
        "cookieOptions": UNKNOWN,
        "reproducibleFromPublicData": UNKNOWN,
        "constructedValueMatchesObserved": UNKNOWN,
        "backendBranchIdentifier": UNKNOWN,
    }
    for key in allowed:
        if key not in source:
            continue
        value = source[key]
        if key in {
            "constructorSourcePath",
            "valueFormat",
            "backendBranchIdentifier",
        }:
            rendered = str(value)
            if (
                len(rendered) <= 500
                and not SECRET_KEY_RE.search(rendered)
            ):
                result[key] = rendered
        elif key in {
            "departmentCookieConstructorFound",
            "reproducibleFromPublicData",
            "constructedValueMatchesObserved",
            "scannedSameOriginScriptCount",
        }:
            if isinstance(value, (bool, int)) or value == UNKNOWN:
                result[key] = value
        elif key in {
            "sourceBranchFields",
            "cookieObjectFieldNames",
        }:
            if isinstance(value, dict):
                result[key] = {
                    str(k)[:120]: str(v)[:120]
                    for k, v in value.items()
                    if not SECRET_KEY_RE.search(str(k))
                    and not SECRET_KEY_RE.search(str(v))
                }
            elif isinstance(value, list):
                result[key] = [
                    str(item)[:120]
                    for item in value
                    if not SECRET_KEY_RE.search(str(item))
                ][:40]
            elif value == UNKNOWN:
                result[key] = UNKNOWN
        elif key == "encodingSteps":
            if isinstance(value, list):
                safe_steps = {
                    "JSON.stringify",
                    "JSON.stringify -> cookie value",
                    "percent-encoding-by-cookie-helper",
                }
                result[key] = [
                    str(item)[:120]
                    for item in value
                    if (
                        str(item) in safe_steps
                        or not SECRET_KEY_RE.search(str(item))
                    )
                ][:20]
            elif value == UNKNOWN:
                result[key] = UNKNOWN
        elif key == "cookieOptions":
            if isinstance(value, dict):
                result[key] = {
                    str(k)[:120]: (
                        value[k]
                        if isinstance(value[k], (bool, int))
                        else str(value[k])[:160]
                    )
                    for k in value
                    if not SECRET_KEY_RE.search(str(k))
                }
            elif value == UNKNOWN:
                result[key] = UNKNOWN
        elif key == "matchedBundles" and isinstance(value, list):
            safe_matches = []
            for item in value[:20]:
                if not isinstance(item, dict):
                    continue
                safe_matches.append(
                    {
                        "scriptPath": str(
                            item.get("scriptPath", UNKNOWN)
                        )[:500],
                        "markers": [
                            str(marker)[:120]
                            for marker in item.get("markers", [])[:40]
                        ],
                    }
                )
            result[key] = safe_matches
    return result


def cookie_code_markers(excerpt: str) -> list[str]:
    markers = (
        "JSON.stringify",
        "JSON.parse",
        "getCookie",
        "setCookie",
        "encodeURIComponent",
        "decodeURIComponent",
        "btoa(",
        "atob(",
        "document.cookie",
        "Cookies.set",
        ".set(",
        "maxAge",
        "expires",
        "sameSite",
        "secure",
        "path",
        "department_id",
    )
    return [marker for marker in markers if marker in excerpt]


def collect_department_cookie_bundle_evidence(
    page: Any,
    context: Any,
    *,
    page_urls: Iterable[str],
    raw_dir: Path,
) -> dict[str, Any]:
    script_sources: list[str] = []
    matched: list[dict[str, Any]] = []
    for page_url in page_urls:
        if not page_url:
            continue
        expected, safe_page_url = sanitize_kwant_url(page_url)
        if not expected:
            continue
        try:
            page.goto(
                safe_page_url,
                wait_until="domcontentloaded",
                timeout=45000,
            )
            page.wait_for_timeout(300)
        except Exception:
            continue
        for source in same_origin_script_sources(page):
            if source not in script_sources:
                script_sources.append(source)

    for source in script_sources[:60]:
        try:
            response = context.request.get(source, timeout=5000)
            if not response.ok:
                continue
            text = response.text()
        except Exception:
            continue
        excerpt = bounded_cookie_excerpt(text)
        if not excerpt:
            continue
        matched.append(
            {
                "scriptPath": urlsplit(source).path,
                "markers": cookie_code_markers(excerpt),
                "_excerpt": excerpt,
                "_related": bounded_marker_excerpts(
                    text,
                    (
                        "department_stock_id",
                        "department_stock_name",
                        "department_id",
                        "setUserStockDepartment",
                    ),
                ),
            }
        )
        if len(matched) >= 3:
            break

    raw_dir.mkdir(parents=True, exist_ok=True)
    if matched:
        raw_lines: list[str] = []
        for item in matched:
            raw_lines.extend(
                [
                    f"SOURCE {item['scriptPath']}",
                    item["_excerpt"],
                    "",
                ]
            )
            for marker, excerpt in item.get("_related", []):
                raw_lines.extend(
                    [
                        f"RELATED {marker} {item['scriptPath']}",
                        excerpt,
                        "",
                    ]
                )
        (raw_dir / "department-cookie-bundle-excerpts.txt").write_text(
            "\n".join(raw_lines),
            encoding="utf-8",
        )

    safe_matches = [
        {
            "scriptPath": item["scriptPath"],
            "markers": item["markers"],
        }
        for item in matched
    ]
    constructor_analyses = [
        analyze_department_cookie_constructor(
            item["scriptPath"],
            item["_excerpt"],
        )
        for item in matched
    ]
    confirmed = [
        item
        for item in constructor_analyses
        if item["departmentCookieConstructorFound"] is True
    ]
    base = (
        confirmed[0]
        if len(confirmed) == 1
        else {
            "departmentCookieConstructorFound": UNKNOWN,
            "constructorSourcePath": UNKNOWN,
            "valueFormat": UNKNOWN,
            "encodingSteps": UNKNOWN,
            "cookieOptions": UNKNOWN,
        }
    )
    return {
        **base,
        "reproducibleFromPublicData": UNKNOWN,
        "constructedValueMatchesObserved": UNKNOWN,
        "backendBranchIdentifier": UNKNOWN,
        "matchedBundles": safe_matches,
        "scannedSameOriginScriptCount": len(script_sources),
    }


def collect_frontend_clues(page: Any, context: Any) -> list[dict[str, Any]]:
    terms = (
        "branch",
        "oddzial",
        "warehouse",
        "magazyn",
        "stock",
        "inventory",
        "availability",
        "search",
        "ean",
        "product",
        "price",
    )
    clues: list[dict[str, Any]] = []
    try:
        sources = page.locator("script[src]").evaluate_all(
            "els => els.map(el => el.src)"
        )
    except Exception:
        return clues

    for source in sources[:20]:
        expected, safe = sanitize_kwant_url(source)
        if not expected:
            continue
        try:
            response = context.request.get(source, timeout=12000)
            if not response.ok:
                continue
            text = response.text()
        except Exception:
            continue
        lowered = text.lower()
        matched = [term for term in terms if term in lowered]
        if matched:
            clues.append(
                {
                    "scriptPath": urlsplit(safe).path,
                    "matchedTerms": matched,
                }
            )
        if len(clues) >= 20:
            break
    return clues


def run_live_probe(
    *,
    product_query: str,
    branch_label: str,
    text_query: str,
    numeric_product_id: str,
    out_dir: Path,
) -> dict[str, Any]:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError as exc:
        raise RuntimeError(
            "Playwright is required for the live KWANT probe"
        ) from exc

    raw_dir = out_dir / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    recorder = NetworkRecorder()
    locations_capture = ObservedLocationsResponses(recorder, numeric_product_id)
    numeric_product_route = probe_numeric_product_route_http(
        numeric_product_id
    )

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(
            locale="pl-PL",
            user_agent=(
                "Mozilla/5.0 (X11; Linux x86_64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/140.0.0.0 Safari/537.36"
            ),
            viewport={"width": 1440, "height": 1000},
        )
        page = context.new_page()
        page.on("request", recorder.on_request)
        page.on("response", recorder.on_response)
        page.on("response", locations_capture.on_response)
        page.on("request", locations_capture.on_request)

        recorder.set_action("baseline")
        page.goto(KWANT_ORIGIN, wait_until="domcontentloaded", timeout=45000)
        page.wait_for_timeout(500)
        (raw_dir / "baseline.html").write_text(
            page.content(),
            encoding="utf-8",
        )

        before_cookie_values, before_cookies = cookie_values(context)
        before_storage = browser_storage_values(page)

        article_before = run_search(
            page,
            recorder,
            query=product_query,
            action="search:article:before",
            target_hint=product_query,
            raw_dir=raw_dir,
        )
        product_url = matching_product_url(article_before, product_query)
        product_before = (
            open_product(
                page,
                recorder,
                url=product_url,
                action="product:before",
                branch_label=branch_label,
                raw_dir=raw_dir,
            )
            if product_url
            else {}
        )

        page.goto(KWANT_ORIGIN, wait_until="domcontentloaded", timeout=45000)
        (
            branch_click_ok,
            branch_page_url,
            target_branch_resolved,
        ) = choose_branch(
            page,
            recorder,
            branch_label,
            raw_dir,
        )

        after_cookie_values, after_cookies = cookie_values(context)
        after_storage = browser_storage_values(page)
        marker_after_select = branch_marker(page, branch_label)
        changed = state_changed_keys(
            before_cookie_values,
            after_cookie_values,
            before_storage,
            after_storage,
        )

        recorder.set_action("branch:reload")
        page.reload(wait_until="domcontentloaded", timeout=45000)
        page.wait_for_timeout(500)
        reload_cookie_values, reload_cookies = cookie_values(context)
        reload_storage = browser_storage_values(page)
        marker_after_reload = selected_marker_after_reload(
            page, branch_label
        )
        persisted = persisted_state_keys(
            after_cookie_values,
            reload_cookie_values,
            after_storage,
            reload_storage,
            changed,
        )
        department_observed = department_cookie_observed(changed)
        department_persisted = department_cookie_persisted(persisted)
        department_value = department_cookie_value(after_cookie_values)
        raw_department_value = raw_department_cookie_value(
            after_cookie_values
        )
        parsed_department_cookie, cookie_encoding_steps = (
            parse_department_cookie_object(raw_department_value)
        )
        branch_identifier = (
            extract_branch_page_identifier(branch_page_url)
            if branch_page_url
            else UNKNOWN
        )
        branch_before_html = (
            (raw_dir / "branch-before-select.html").read_text(
                encoding="utf-8"
            )
            if (raw_dir / "branch-before-select.html").exists()
            else ""
        )
        public_branch = public_branch_object_from_html(
            branch_before_html,
            branch_identifier,
        )
        source_mapping: dict[str, str] = {}
        public_data_reproducible: bool | None = None
        constructed_matches: bool | None = None
        department_stock_id = UNKNOWN

        selection_observed, selection_persisted = (
            derive_branch_selection_status(
                branch_click_ok=branch_click_ok,
                marker_after_select=marker_after_select,
                marker_after_reload=marker_after_reload,
                changed=changed,
                persisted=persisted,
                network=recorder.records,
            )
        )

        article_after = run_search(
            page,
            recorder,
            query=product_query,
            action="search:article",
            target_hint=product_query,
            raw_dir=raw_dir,
        )
        product_url = (
            matching_product_url(article_after, product_query)
            or product_url
        )
        product_after = (
            open_product(
                page,
                recorder,
                url=product_url,
                action="product:after",
                branch_label=branch_label,
                raw_dir=raw_dir,
            )
            if product_url
            else {}
        )

        ean = coalesce(
            product_after.get("ean"),
            product_before.get("ean"),
        )
        ean_search = (
            run_search(
                page,
                recorder,
                query=str(ean),
                action="search:ean",
                target_hint=product_query,
                raw_dir=raw_dir,
            )
            if ean != UNKNOWN
            else empty_search_result(UNKNOWN)
        )
        text_search = run_search(
            page,
            recorder,
            query=text_query,
            action="search:text",
            target_hint=product_query,
            raw_dir=raw_dir,
        )

        # Resolve the selected branch before locations research. The later
        # departmentStockId cookie mapping is deliberately not yet available.
        trusted_selected_branch_id = resolved_public_selected_branch_id(
            branch_page_url=branch_page_url,
            branch_html=branch_before_html,
            selected_branch_label=branch_label,
            selection_confirmed=(
                target_branch_resolved and selection_observed is True
            ),
        )
        locations_research = research_product_locations(
            page, recorder, locations_capture,
            product_id=numeric_product_id,
            branch_html=branch_before_html,
            selected_branch_id=(
                trusted_selected_branch_id
                if trusted_selected_branch_id != UNKNOWN else ""
            ),
            selected_branch_label=branch_label,
        )
        recorder.set_action("home:after-locations")
        page.goto(KWANT_ORIGIN, wait_until="domcontentloaded", timeout=45000)
        frontend_clues = collect_frontend_clues(page, context)
        department_cookie_research = (
            collect_department_cookie_bundle_evidence(
                page,
                context,
                page_urls=(
                    f"{KWANT_ORIGIN}/lista-hurtowni-elektrycznych",
                    branch_page_url,
                    product_url,
                ),
                raw_dir=raw_dir,
            )
        )
        if parsed_department_cookie:
            cookie_keys = sorted(
                key
                for key in parsed_department_cookie
                if not SECRET_KEY_RE.search(str(key))
            )
            evidenced_fields = department_cookie_research.get(
                "sourceBranchFields"
            )
            source_mapping = public_branch_field_mapping(
                (
                    evidenced_fields
                    if isinstance(evidenced_fields, list)
                    else []
                ),
                public_branch,
            )
            public_data_reproducible = (
                public_branch_reproduces_cookie_fields(
                    parsed_department_cookie,
                    source_mapping,
                    public_branch,
                )
            )
            constructed_matches = (
                constructed_cookie_value_matches_observed(
                    raw_department_value,
                    source_mapping,
                    public_branch,
                )
            )
            department_stock_id = safe_department_stock_id(
                parsed_department_cookie,
                source_mapping,
                public_branch,
                branch_identifier,
            )
            department_cookie_research.update(
                {
                    "reproducibleFromPublicData": tri_state(
                        public_data_reproducible
                    ),
                    "constructedValueMatchesObserved": tri_state(
                        constructed_matches
                    ),
                    "backendBranchIdentifier": UNKNOWN,
                    "cookieObjectFieldNames": cookie_keys,
                }
            )

        summary = build_safe_summary(
            requested_branch_label=branch_label,
            branch_page_url=branch_page_url,
            target_branch_resolved=target_branch_resolved,
            selection_observed=selection_observed,
            selection_persisted=selection_persisted,
            department_cookie_observed=department_observed,
            department_cookie_persisted=department_persisted,
            department_cookie_value_safe=department_value,
            department_stock_id=department_stock_id,
            backend_branch_identifier=infer_backend_branch_identifier(
                recorder.records
            ),
            searches={
                "articleCode": strip_private_search_fields(article_after),
                "ean": strip_private_search_fields(ean_search),
                "text": strip_private_search_fields(text_search),
            },
            product_before=product_before,
            product_after=product_after,
            network=recorder.records,
            state={
                "baseline": {
                    "cookies": cookie_summary(before_cookies),
                    "storageKeys": storage_key_summary(before_storage),
                },
                "afterSelection": {
                    "cookies": cookie_summary(after_cookies),
                    "storageKeys": storage_key_summary(after_storage),
                    "changedKeyNames": changed,
                    "branchMarkerObserved": marker_after_select,
                },
                "afterReload": {
                    "cookies": cookie_summary(reload_cookies),
                    "storageKeys": storage_key_summary(reload_storage),
                    "persistedChangedKeyNames": persisted,
                    "branchMarkerObserved": marker_after_reload,
                },
            },
            frontend_clues=frontend_clues,
            department_cookie_research=department_cookie_research,
            numeric_product_route=numeric_product_route,
        )
        # Generic full-page regexes can read recommended-product stock.
        # Replace *all* displayed stock scopes with exact-product evidence.
        for candidate in (
            summary["product"],
            summary["product"].get("beforeBranchSelection", {}),
            summary["product"].get("afterBranchSelection", {}),
        ):
            if isinstance(candidate, dict):
                for field in (
                    "selectedBranchStock", "centralStock", "aggregateBranchStock"
                ):
                    candidate[field] = UNKNOWN
        for field, loc_key in (
            ("selectedBranchStock", "selectedBranchStock"),
            ("centralStock", "centralStock"),
            ("aggregateBranchStock", "aggregateBranchStock"),
        ):
            quantity = locations_research.get(loc_key)
            if quantity not in (None, UNKNOWN):
                summary["product"][field] = quantity
            summary["product"][field + "Source"] = locations_research.get(
                loc_key + "Source", "UNKNOWN"
            )
        summary["locationsResearch"] = locations_research
        browser.close()
        return summary


def strip_private_search_fields(
    value: dict[str, Any],
) -> dict[str, Any]:
    return {
        key: child
        for key, child in value.items()
        if not key.startswith("_")
    }



# Locations research is deliberately diagnostic only. No endpoint is guessed or
# requested directly: all API evidence below comes from observed browser traffic.
LOCATIONS_RESEARCH_MAX_RESPONSES = 85
LOCATIONS_RESEARCH_MAX_BODY_BYTES = 1_500_000
LOCATIONS_RESEARCH_MAX_BRANCH_ROWS = 60


def public_branch_directory_from_html(html: str) -> dict[str, str]:
    """Use only verified public department IDs/names from the branch directory."""
    match = re.search(
        r"""<script[^>]+id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
        html,
        re.IGNORECASE | re.DOTALL,
    )
    if not match:
        return {}
    try:
        payload = json.loads(match.group(1))
        rows = payload["props"]["pageProps"]["departments"]["list"]
    except (ValueError, KeyError, TypeError):
        return {}
    directory: dict[str, str] = {}
    if not isinstance(rows, list):
        return directory
    for item in rows[:150]:
        if not isinstance(item, dict):
            continue
        branch_id, name = item.get("department_id"), item.get("name")
        if (
            type(branch_id) is int
            and 0 <= branch_id <= 99999999
            and isinstance(name, str)
            and 0 < len(name) <= 120
            and not SECRET_KEY_RE.search(name)
            and is_safe_public_value(name)
        ):
            directory[str(branch_id)] = name
    return directory


def resolved_public_selected_branch_id(
    *,
    branch_page_url: str,
    branch_html: str,
    selected_branch_label: str,
    selection_confirmed: bool,
) -> str:
    """Cross-check the resolved selected branch page against live directory.

    This is available BEFORE later cookie-constructor research sets the
    final summary departmentStockId. Names only corroborate a numeric ID
    already established by the public branch page and directory.
    """
    if not selection_confirmed:
        return UNKNOWN
    page_id = extract_branch_page_identifier(branch_page_url)
    directory = public_branch_directory_from_html(branch_html)
    if (
        page_id == UNKNOWN
        or page_id not in directory
        or not branch_label_matches(
            selected_branch_label, link_text=directory[page_id]
        )
    ):
        return UNKNOWN
    return page_id


# Explicit research hypotheses, NOT established inventory semantics.
BRANCH_ID_CANDIDATE_FIELDS = (
    "department_id", "department_stock_id", "departmentId", "departmentStockId",
)
STOCK_QUANTITY_CANDIDATE_KEYS = frozenset({
    "stock", "stocknum", "stockqty", "stockquantity",
    "available", "availablestock", "availablestocknum",
    "availablestockquantity", "availabilityquantity",
    "availabilityqty", "quantityinstock", "quantityavailable",
    "physicalstock", "branchstock", "departmentstock",
})
SEARCH_CENTRAL_OR_AGGREGATE_KEYS = frozenset({
    "centralstock", "centralstocknum", "aggregatestock",
    "aggregatebranchstock", "totalstock", "totalbranchstock",
    "stocktotal", "stockaggregate",
})
SAFE_JSON_FIELD_RE = re.compile(r"[A-Za-z][A-Za-z0-9_]{0,55}\Z")


def safe_json_field(key: Any) -> bool:
    return (
        isinstance(key, str)
        and SAFE_JSON_FIELD_RE.fullmatch(key) is not None
        and SECRET_KEY_RE.search(key) is None
    )


def normalized_quantity_key(key: str) -> str:
    return key.replace("_", "").lower()


def is_stock_candidate_field(key: str) -> bool:
    return (
        safe_json_field(key)
        and normalized_quantity_key(key) in STOCK_QUANTITY_CANDIDATE_KEYS
    )


def is_search_stock_field(key: str) -> bool:
    return (
        is_stock_candidate_field(key)
        or (
            safe_json_field(key)
            and normalized_quantity_key(key) in SEARCH_CENTRAL_OR_AGGREGATE_KEYS
        )
    )


def numeric_branch_id(value: Any) -> str | None:
    if type(value) is int and 0 <= value <= 99999999:
        return str(value)
    if isinstance(value, str) and re.fullmatch(r"[0-9]{1,8}", value):
        return value
    return None


def observed_branch_identity(
    value: dict[str, Any],
) -> tuple[str | None, str | None]:
    matches = [
        (key, numeric_branch_id(value[key]))
        for key in BRANCH_ID_CANDIDATE_FIELDS
        if key in value and numeric_branch_id(value[key]) is not None
    ]
    if not matches or len({match[1] for match in matches}) != 1:
        return None, None
    return matches[0][1], matches[0][0]


def candidate_quantity(value: Any) -> tuple[int | None, str]:
    if value is None:
        return None, "unknown_null"
    if type(value) is not int or not (0 <= value <= 1_000_000_000):
        return None, "invalid"
    return value, "known_zero" if value == 0 else "known_positive"


def search_stock_classification(fields: list[str]) -> str:
    if not fields:
        return "NO_STOCK_FIELD_OBSERVED"
    if all(
        normalized_quantity_key(name.rsplit(".", 1)[-1])
        in SEARCH_CENTRAL_OR_AGGREGATE_KEYS
        for name in fields
    ):
        return "CENTRAL_OR_AGGREGATE_LOOKING_ONLY"
    return "STOCK_SHAPED_SCOPE_UNKNOWN"


def research_json_shape(
    payload: Any,
    expected_product_id: str,
    directory: dict[str, str],
) -> dict[str, Any]:
    """Bounded, privacy-filtered structural observations, never trusted stock."""
    if not isinstance(payload, (dict, list)):
        return {"rootType": type(payload).__name__, "branchRows": []}
    root = payload if isinstance(payload, dict) else {}
    root_id = root.get("product_id", root.get("productId", root.get("id")))
    identity = (
        str(root_id) == expected_product_id
        if type(root_id) in (str, int) else UNKNOWN
    )
    hits = root.get("hits")
    summary: dict[str, Any] = {
        "rootType": type(payload).__name__,
        "rootFields": sorted(k for k in root if safe_json_field(k))[:65],
        "productIdentityMatches": identity,
        "branchRows": [],
        "searchStockEvidence": "NOT_A_SEARCH_RESPONSE",
    }
    if isinstance(hits, list):
        first = next((h for h in hits[:30] if isinstance(h, dict)), {})
        summary["searchHitCount"] = len(hits)
        summary["searchHitFields"] = sorted(
            k for k in first if safe_json_field(k)
        )[:75]
        stock_fields: set[str] = set()
        control_candidates: list[dict[str, Any]] = []
        for hit in hits[:30]:
            if not isinstance(hit, dict):
                continue
            hit_id = hit.get("id")
            is_control = type(hit_id) in (str, int) and (
                str(hit_id) == expected_product_id
            )
            def inspect_hit(obj: dict[str, Any], prefix: str) -> None:
                for key, child in list(obj.items())[:100]:
                    if not safe_json_field(key):
                        continue
                    field_path = (prefix + "." if prefix else "") + key
                    if is_search_stock_field(key):
                        stock_fields.add(field_path)
                    if (
                        is_control and is_stock_candidate_field(key)
                        and len(control_candidates) < 10
                    ):
                        branch_id, _ = observed_branch_identity(obj)
                        value, state = candidate_quantity(child)
                        if branch_id is not None and state in (
                            "known_zero", "known_positive"
                        ):
                            control_candidates.append({
                                "branchId": branch_id,
                                "candidateField": field_path,
                                "candidateValue": value,
                            })
                    if (
                        isinstance(child, dict) and not prefix
                        and len(stock_fields) < 75
                    ):
                        inspect_hit(child, field_path)
            inspect_hit(hit, "")
        summary["searchHitStockFields"] = sorted(stock_fields)[:75]
        summary["searchStockEvidence"] = search_stock_classification(
            summary["searchHitStockFields"]
        )
        summary["searchHitControlCandidates"] = control_candidates

    candidate_rows: list[dict[str, Any]] = []
    inspected = 0

    def visit(node: Any, path: str, depth: int) -> None:
        nonlocal inspected
        if inspected >= 5000 or depth > 7:
            return
        inspected += 1
        if isinstance(node, dict):
            branch_id, branch_field = observed_branch_identity(node)
            if branch_id is not None:
                for key, value in list(node.items())[:120]:
                    if (
                        len(candidate_rows) >= LOCATIONS_RESEARCH_MAX_BRANCH_ROWS
                        or not is_stock_candidate_field(key)
                    ):
                        continue
                    stock, state = candidate_quantity(value)
                    candidate_rows.append({
                        "jsonPath": path,
                        "branchId": branch_id,
                        "branchIdField": branch_field,
                        "branchKnownInDirectory": branch_id in directory,
                        "branchNameFromDirectory": directory.get(branch_id),
                        "candidateField": key,
                        "candidateValue": stock,
                        "candidateState": state,
                        # Backward-compatible diagnostic alias; still unverified.
                        "stockState": state,
                    })
            for key, value in list(node.items())[:120]:
                if safe_json_field(key):
                    visit(value, path + "." + key, depth + 1)
        elif isinstance(node, list):
            for element in node[:120]:
                visit(element, path + "[]", depth + 1)

    visit(payload, "$", 0)
    summary["branchRows"] = candidate_rows
    summary["branchRowCountCaptured"] = len(candidate_rows)
    summary["branchDistinctIds"] = len({
        row["branchId"] for row in candidate_rows
    })
    summary["verifiedDirectoryBranchCount"] = len({
        row["branchId"] for row in candidate_rows
        if row["branchKnownInDirectory"]
    })
    summary["numericCandidateCount"] = sum(
        row["candidateState"] in ("known_zero", "known_positive")
        and row["branchKnownInDirectory"]
        for row in candidate_rows
    )
    return summary



AVAILABILITY_ARIA_LABEL = "Sprawdź stan i kup towar w oddziałach Kwant"
AVAILABLE_ONLY_LABEL_RE = re.compile(
    r"Pokaż\s+tylko\s+oddziały\s+w\s+których\s+produkt\s+jest\s+dostępny",
    re.I,
)
CURRENT_STOCK_PATH = re.compile(r"/api/front/products/([0-9]+)/current\Z")
BATCH_PRICES_PATH = re.compile(
    r"/api/front/products/prices/([0-9]+(?:,[0-9]+){1,99})\Z"
)


AVAILABILITY_CHILD_SELECTOR = (
    '[aria-label="Sprawdź stan i kup towar w oddziałach Kwant"]'
)
AVAILABILITY_PARENT_SELECTOR = "xpath=ancestor::*[@role='button'][1]"
AVAILABILITY_STOCK_ROW_RE = re.compile(
    r"\b(?:[0-9]{1,9}\s*szt\.?|brak|niedostepn\w*|dostepn\w*|stan)\b",
    re.I,
)
AVAILABILITY_EXCLUDED_ROW_RE = re.compile(
    r"\b(?:zapytaj\s+eksperta|kontakt\s+z\s+ekspertem|"
    r"dodaj\s+do\s+koszyka|kup\s+teraz|zadzwo[nń]|telefon)\b",
    re.I,
)


def is_selected_branch_stock_row(
    parent_text: str, selected_branch_label: str,
) -> bool:
    """Screen exact inventory row text; name alone is never sufficient."""
    if not isinstance(parent_text, str) or len(parent_text) > 360:
        return False
    normalized = normalize_branch_identity(parent_text)
    branch = normalize_branch_identity(selected_branch_label)
    if not normalized or not branch or len(branch) > 100:
        return False
    if not re.search(rf"(?<!\w){re.escape(branch)}(?!\w)", normalized):
        return False
    return (
        AVAILABILITY_STOCK_ROW_RE.search(normalized) is not None
        and AVAILABILITY_EXCLUDED_ROW_RE.search(normalized) is None
    )


def availability_button(
    page: Any, selected_branch_label: str,
) -> Any | None:
    """Resolve actual clickable ancestor, never the duplicate expert row.

    Run #17: aria-label is on a child, role=button on its parent. Require
    precisely one eligible visible, enabled stock-row parent for the publicly
    verified selected branch; do not rely on DOM order.
    """
    if not normalize_branch_identity(selected_branch_label):
        return None
    try:
        descendants = page.locator(AVAILABILITY_CHILD_SELECTOR)
        count = descendants.count()
        if not 1 <= count <= 12:
            return None
        matches: list[Any] = []
        for index in range(count):
            child = descendants.nth(index)
            if not child.is_visible():
                continue
            parent = child.locator(AVAILABILITY_PARENT_SELECTOR)
            if parent.count() != 1:
                continue
            button = parent.first
            if (
                button.get_attribute("role") != "button"
                or not button.is_visible()
                or not button.is_enabled()
            ):
                continue
            text = button.inner_text(timeout=1200)[:361]
            if is_selected_branch_stock_row(text, selected_branch_label):
                matches.append(button)
        return matches[0] if len(matches) == 1 else None
    except Exception:
        return None


def inspect_availability_dom(
    page: Any,
    directory: dict[str, str],
) -> dict[str, Any]:
    """Bounded UI-only evidence. A visible name never proves a department ID."""
    result: dict[str, Any] = {
        "scopedListFound": False, "visibleDirectoryNames": [],
        "explicitBranchIds": [], "explicitBranchLinkIds": [],
        "zeroLabelVisible": False,
        "quantityTextVisible": False, "centralLabelVisible": False,
        "filterVisible": False,
    }
    try:
        dialogs = page.locator('[role="dialog"],[aria-modal="true"]')
        scope = None
        for i in range(min(dialogs.count(), 5)):
            if dialogs.nth(i).is_visible():
                scope = dialogs.nth(i)
                break
        if scope is None:
            # The filter is a unique availability-only landmark; avoid body text
            # or recommendations when a modal has no dialog role.
            filter_text = page.get_by_text(AVAILABLE_ONLY_LABEL_RE)
            if filter_text.count() == 1 and filter_text.first.is_visible():
                scope = filter_text.first.locator("xpath=../..")
        if scope is None:
            return result
        result["scopedListFound"] = True
        bounded_text = scope.inner_text(timeout=2000)[:16000]
        result["visibleDirectoryNames"] = [
            name for name in directory.values()
            if re.search(r"(?<!\w)" + re.escape(name) + r"(?!\w)",
                         bounded_text, re.I)
        ][:60]
        result["zeroLabelVisible"] = bool(
            re.search(r"\b(?:0\s*szt\.?|brak\s+na\s+stanie)\b",
                      bounded_text, re.I)
        )
        result["quantityTextVisible"] = bool(
            re.search(r"\b\d{1,8}\s*szt\.?", bounded_text, re.I)
        )
        result["centralLabelVisible"] = bool(
            re.search(r"\bCentrala\b", bounded_text, re.I)
        )
        result["filterVisible"] = bool(
            AVAILABLE_ONLY_LABEL_RE.search(bounded_text)
        )
        attrs = scope.locator("[data-department-id],[data-branch-id],[data-depstock]")
        identifiers: set[str] = set()
        for i in range(min(attrs.count(), 90)):
            node = attrs.nth(i)
            for attr in ("data-department-id", "data-branch-id", "data-depstock"):
                bid = numeric_branch_id(node.get_attribute(attr))
                if bid in directory:
                    identifiers.add(bid)
        result["explicitBranchIds"] = sorted(identifiers)[:60]
        links = scope.locator("a[href*='lista-hurtowni-elektrycznych']")
        link_ids: set[str] = set()
        for index in range(min(links.count(), 70)):
            href = links.nth(index).get_attribute("href") or ""
            absolute = urljoin(KWANT_ORIGIN, href)
            safe, _ = sanitize_kwant_url(absolute)
            if not safe:
                continue
            bid = extract_branch_page_identifier(absolute)
            if bid in directory:
                link_ids.add(bid)
        result["explicitBranchLinkIds"] = sorted(link_ids)[:60]
    except Exception:
        result["inspectionFailed"] = True
    return result


def classify_availability_filter(
    before: dict[str, Any], after: dict[str, Any],
    new_requests: int, toggled: bool,
) -> dict[str, Any]:
    state = "NOT_TOGGLED"
    if toggled:
        if new_requests > 0:
            state = "REQUEST_TRIGGERED"
        elif (
            before.get("visibleDirectoryNames", [])
            != after.get("visibleDirectoryNames", [])
            or before.get("zeroLabelVisible") != after.get("zeroLabelVisible")
        ):
            state = "CLIENT_SIDE_FILTER_CANDIDATE"
        else:
            state = "NO_API_REQUEST_OBSERVED"
    return {
        "classification": state,
        "newObservedApiResponses": max(0, new_requests),
        "beforeVisibleBranchNameCount": len(before.get("visibleDirectoryNames", [])),
        "afterVisibleBranchNameCount": len(after.get("visibleDirectoryNames", [])),
        "beforeZeroVisible": before.get("zeroLabelVisible", False),
        "afterZeroVisible": after.get("zeroLabelVisible", False),
        "quantityLabelsStillVisible": after.get("quantityTextVisible", False),
        "requestEvidenceOnly": new_requests > 0,
    }


def extract_exact_product_central_stock(
    next_payload: Any, expected_id: str,
) -> dict[str, Any]:
    """Only main product in structured Next pageProps, never recommendations."""
    unknown = {"stock": None, "source": "UNKNOWN"}
    if not isinstance(next_payload, dict):
        return unknown
    props = next_payload.get("props")
    page_props = props.get("pageProps") if isinstance(props, dict) else None
    product = page_props.get("product") if isinstance(page_props, dict) else None
    if not isinstance(product, dict):
        return unknown
    if str(product.get("id")) != expected_id:
        return unknown
    value, state = candidate_quantity(product.get("stock"))
    if state not in ("known_zero", "known_positive"):
        return unknown
    return {
        "stock": value, "source": "NEXT_DATA_EXACT_PRODUCT_ID",
        "productIdVerified": True,
    }


def selected_branch_current_diagnostic(
    observations: list[dict[str, Any]],
    expected_id: str,
    branch_id: str,
) -> dict[str, Any]:
    """Authorize one quantity only after exact CURRENT root+department match."""
    unknown = {"stock": None, "source": "UNKNOWN"}
    for item in reversed(observations):
        if (
            item.get("host") != KWANT_SERVICES_HOST
            or item.get("method") != "GET" or item.get("status") != 200
            or item.get("path") != f"/api/front/products/{expected_id}/current"
            or str((item.get("query") or {}).get("safeValues", {}).get("depstock")) != branch_id
        ):
            continue
        shape = item.get("shape", {})
        if shape.get("productIdentityMatches") is not True:
            continue
        valid = [
            r for r in shape.get("branchRows", [])
            if r.get("jsonPath") == "$.department_stock"
            and r.get("branchId") == branch_id
            and r.get("candidateField") == "stock"
            and r.get("candidateState") in ("known_zero", "known_positive")
        ]
        if len(valid) == 1:
            return {
                "stock": valid[0]["candidateValue"],
                "source": "CURRENT_IDENTITY_AND_DEPSTOCK_VERIFIED",
            }
    return unknown


def product_identity_evidence(
    observation: dict[str, Any], expected_id: str,
) -> str:
    """Bound a response to exactly one product; no timing or batch inference."""
    if observation.get("host") not in {KWANT_SERVICES_HOST, KWANT_HOST}:
        return "UNKNOWN"
    if observation.get("status") != 200:
        return "UNKNOWN"
    shape = observation.get("shape") or {}
    # Explicit conflicting response identity is not overridden by the URL.
    if shape.get("productIdentityMatches") is False:
        return "UNKNOWN"
    if shape.get("productIdentityMatches") is True:
        return "RESPONSE_PRODUCT_ID"
    path = observation.get("path", "")
    # Match a singular product ID path segment, not a substring or a
    # multi-product /products/prices/<id>,<id> endpoint.
    if re.fullmatch(
        r"/api/front/products/" + re.escape(expected_id) + r"(?:/[^?]*)?",
        path,
    ):
        return "SINGULAR_REQUEST_PRODUCT_PATH"
    if BATCH_PRICES_PATH.fullmatch(path):
        return "UNKNOWN"
    body = (observation.get("body") or {}).get("safeValues") or {}
    if (
        str(body.get("product_id", "")) == expected_id
        or str(body.get("productId", "")) == expected_id
    ):
        return "EXACT_REQUEST_BODY_PRODUCT_ID"
    return "UNKNOWN"


def request_bound_product_identity(
    observation: dict[str, Any], expected_id: str,
) -> bool:
    return product_identity_evidence(observation, expected_id) != "UNKNOWN"


def classify_locations_contract(
    observations: list[dict[str, Any]],
    directory: dict[str, str], expected_id: str,
    product_page_shape: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Preliminary A-F classification; no automatic production validation."""
    outcome = {"type": "F_INCONCLUSIVE", "reason": "INSUFFICIENT_PRODUCT_BOUND_EVIDENCE"}
    if not directory:
        return outcome
    page_shape = product_page_shape or {}
    if (
        page_shape.get("productIdentityMatches") is True
        and evaluate_candidate_coverage(page_shape, directory)["completeness"]
        == "ALL_DIRECTORY_BRANCHES"
        and {
            row["branchId"] for row in page_shape.get("branchRows", [])
            if row.get("branchKnownInDirectory")
            and row.get("candidateState") in ("known_zero", "known_positive")
        } == set(directory)
    ):
        return {"type": "C_FRONTEND_PRELOADED", "reason": "VERIFIED_PAGE_PRODUCT_AND_DIRECTORY_ROWS"}
    scoped = [
        item for item in observations
        if item.get("action", "").startswith("locations:")
        and request_bound_product_identity(item, expected_id)
    ]
    multi = [
        item for item in scoped
        if (item.get("shape") or {}).get("verifiedDirectoryBranchCount", 0) >= 2
    ]
    if len(multi) == 1:
        coverage = evaluate_candidate_coverage(multi[0]["shape"], directory)
        all_rows = multi[0]["shape"].get("branchRows", [])
        numeric_ids = {
            row["branchId"] for row in all_rows
            if row.get("branchKnownInDirectory")
            and row.get("candidateState") in ("known_zero", "known_positive")
        }
        # For discovery, alternative candidate fields may coexist. Keep the
        # reconciliation sum stricter, but do not miss the shape itself.
        if (
            numeric_ids == set(directory)
            and coverage["completeness"] == "ALL_DIRECTORY_BRANCHES"
        ):
            return {"type": "A_ONE_SHOT_ALL_BRANCHES",
                    "reason": "PRODUCT_BOUND_FULL_DIRECTORY_STRUCTURAL_CANDIDATE"}
        if coverage["sumConfirmedObservedBranchStock"] is not None:
            if coverage["completeness"] == "ALL_DIRECTORY_BRANCHES":
                return {"type": "A_ONE_SHOT_ALL_BRANCHES", "reason": "ONE_PRODUCT_BOUND_FULL_DIRECTORY_RESPONSE"}
            if coverage["completeness"] == "POSITIVE_ONLY_CANDIDATE":
                return {"type": "B_ONE_SHOT_POSITIVE_ONLY", "reason": "CANDIDATE_ONLY_POSITIVE_SUBSET_NOT_PROOF_OF_ZERO_OMISSIONS"}
    if len(multi) > 1 and len(multi) <= 5:
        combined_ids = set()
        for item in multi:
            for row in item["shape"].get("branchRows", []):
                if (
                    row.get("branchKnownInDirectory")
                    and row.get("candidateState") in ("known_zero", "known_positive")
                ):
                    combined_ids.add(row["branchId"])
        if combined_ids == set(directory):
            return {"type": "D_MULTI_REQUEST_BOUNDED", "reason": "MULTIPLE_PRODUCT_BOUND_RESPONSES_COVER_DIRECTORY"}
    depstocks: set[str] = set()
    for item in scoped:
        match = CURRENT_STOCK_PATH.fullmatch(item.get("path", ""))
        depstock = str((item.get("query") or {}).get("safeValues", {}).get("depstock"))
        if match and match.group(1) == expected_id and depstock in directory:
            depstocks.add(depstock)
    if len(depstocks) >= 3:
        return {"type": "E_PER_BRANCH_FANOUT", "reason": "THREE_OR_MORE_DISTINCT_BRANCH_SCOPED_REQUESTS",
                "distinctBranchRequestCount": len(depstocks)}
    return outcome


def batch_prices_stock_evidence(
    path: str, data: Any, expected_id: str, depstock: str | None = None,
) -> dict[str, Any] | None:
    """Observe only confirmed per-row product identity, never infer joins by index."""
    match = BATCH_PRICES_PATH.fullmatch(path)
    if not match:
        return None
    ids = match.group(1).split(",")
    payload = data.get("list") if isinstance(data, dict) else data
    rows = payload if isinstance(payload, list) else []
    control_rows = []
    identity_rows = 0
    scoped_rows = 0
    requested_id_rows = 0
    selected_branch_rows = 0
    for entry in rows[:120]:
        if not isinstance(entry, dict):
            continue
        identity = entry.get("product_id", entry.get("id"))
        if type(identity) not in (int, str) or not str(identity).isdigit():
            continue
        identity_rows += 1
        if str(identity) in ids:
            requested_id_rows += 1
        department = entry.get("department_stock")
        if isinstance(department, dict):
            bid, _ = observed_branch_identity(department)
            candidate_fields = [
                field for field in ("stock", "stock_num", "stockNum")
                if field in department
            ]
            candidate_values = [
                (field, *candidate_quantity(department[field]))
                for field in candidate_fields
            ]
            valid_values = [
                (field, quantity) for field, quantity, state in candidate_values
                if state in ("known_zero", "known_positive")
            ]
            if (
                bid is not None and valid_values
                and len({value for _, value in valid_values}) == 1
                and len(valid_values) == len(candidate_fields)
            ):
                scoped_rows += 1
                if depstock is not None and bid == depstock:
                    selected_branch_rows += 1
                if str(identity) == expected_id and str(identity) in ids:
                    control_rows.append({
                        "branchId": bid,
                        "stock": valid_values[0][1],
                        "candidateField": valid_values[0][0],
                    })
    return {
        "requestedProductIdCount": len(ids),
        "responseRowCount": len(rows),
        "identityBoundRowCount": identity_rows,
        "requestedProductIdentityRowCount": requested_id_rows,
        "selectedBranchRowCount": selected_branch_rows,
        "departmentStockRowCount": scoped_rows,
        "controlProductRows": control_rows[:3],
        "usableBatchCandidate": (
            bool(rows) and identity_rows == len(rows)
            and requested_id_rows == len(rows)
            and scoped_rows == len(rows)
            and depstock is not None
            and selected_branch_rows == len(rows)
        ),
        "status": "STRUCTURAL_CANDIDATE_NOT_PRODUCTION_VERIFIED",
    }


def parse_public_aggregate_quantity(value: Any) -> int | None:
    if not isinstance(value, str):
        return None
    match = re.fullmatch(
        r"\s*([0-9]{1,3}(?:[ \u00a0][0-9]{3})*|[0-9]+)\s*szt\.?\s*",
        value, re.IGNORECASE,
    )
    if not match:
        return None
    result = int(re.sub(r"\s", "", match.group(1)))
    return result if result <= 1_000_000_000 else None


def evaluate_candidate_coverage(
    shape: dict[str, Any],
    directory: dict[str, str],
    *,
    product_scope_verified: bool = False,
) -> dict[str, Any]:
    """Compare real branch IDs with explicitly proven response/request scope.

    The shape-only default preserves conservative behavior. Callers may supply
    an independent, verified one-product request binding. Unknown/mismatched
    response identity is never silently upgraded to trusted stock.
    """
    rows = shape.get("branchRows", [])
    known = [row for row in rows if row.get("branchKnownInDirectory")]
    represented = {row["branchId"] for row in known}
    numeric = [
        row for row in known
        if row.get("candidateState") in ("known_zero", "known_positive")
    ]
    identity_verified = (
        shape.get("productIdentityMatches") is True
        or (product_scope_verified and shape.get("productIdentityMatches") is not False)
    )
    result: dict[str, Any] = {
        "completeness": UNKNOWN,
        "verifiedDirectoryBranchCount": len(represented),
        "directoryBranchCount": len(directory),
        "sumConfirmedObservedBranchStock": None,
    }
    # More than one row per branch (including different stock-shaped fields)
    # prevents an unambiguous quantity mapping; do not choose a favorite field.
    unique = (
        len(known) == len(represented)
        and len(known) == len(numeric)
        and all(row["candidateValue"] is not None for row in numeric)
    )
    if identity_verified and len(represented) >= 2 and directory:
        all_ids = represented == set(directory) and all(
            row["branchKnownInDirectory"] for row in rows
        )
        if all_ids:
            result["completeness"] = "ALL_DIRECTORY_BRANCHES"
        elif (
            unique and len(rows) == len(known)
            and all(row["candidateValue"] > 0 for row in numeric)
        ):
            result["completeness"] = "POSITIVE_ONLY_CANDIDATE"
        else:
            result["completeness"] = "PARTIAL"
        if unique and len(rows) == len(known):
            result["sumConfirmedObservedBranchStock"] = sum(
                row["candidateValue"] for row in numeric
            )
    return result


def select_strongest_location_candidate(
    observations: list[dict[str, Any]],
    directory: dict[str, str],
    aggregate: Any,
) -> dict[str, Any]:
    """Diagnostic ranking solely by observed identity, coverage, quantities and UI."""
    aggregate_quantity = parse_public_aggregate_quantity(aggregate)
    scored: list[tuple[tuple[int, ...], int, dict[str, Any], dict[str, Any]]] = []
    for index, observation in enumerate(observations):
        if not str(observation.get("action", "")).startswith("locations:"):
            continue
        shape = observation.get("shape") or {}
        if not isinstance(shape, dict):
            continue
        count = shape.get("verifiedDirectoryBranchCount", 0)
        if count < 2:
            continue
        coverage = evaluate_candidate_coverage(shape, directory)
        triggered_ui = observation.get("action") not in (
            "locations:page",
        )
        score = (
            int(shape.get("productIdentityMatches") is True),
            int(count >= 2),
            int(shape.get("numericCandidateCount", 0) > 0),
            int(triggered_ui),
            count,
            int(shape.get("numericCandidateCount", 0)),
        )
        scored.append((score, index, shape, coverage))

    if not scored:
        return {
            "strongestCandidate": None,
            "completeness": UNKNOWN,
            "aggregateReconciliation": "NOT_EVALUATED",
            "sumConfirmedObservedBranchStock": None,
            "parsedAggregateBranchStock": aggregate_quantity,
        }

    score, index, shape, coverage = max(
        scored, key=lambda item: (item[0], -item[1])
    )
    scope_verified = shape.get("productIdentityMatches") is True
    total = coverage["sumConfirmedObservedBranchStock"]
    complete = coverage["completeness"] == "ALL_DIRECTORY_BRANCHES"
    if not scope_verified:
        reconciliation = "NOT_EVALUATED"
    elif not complete or total is None or aggregate_quantity is None:
        reconciliation = "NOT_COMPARABLE"
    else:
        reconciliation = (
            "MATCH" if total == aggregate_quantity else "MISMATCH"
        )
    return {
        "strongestCandidate": {
            "observationIndex": index,
            "productIdentityVerified": scope_verified,
            "uiTriggered": score[3] == 1,
            "verifiedDirectoryBranchCount": coverage["verifiedDirectoryBranchCount"],
            "numericCandidateCount": shape.get("numericCandidateCount", 0),
            "reason": "STRUCTURAL_CANDIDATE_ONLY_NOT_PRODUCTION_CONTRACT",
        },
        "completeness": coverage["completeness"],
        "aggregateReconciliation": reconciliation,
        "sumConfirmedObservedBranchStock": total,
        "parsedAggregateBranchStock": aggregate_quantity,
    }


def summarize_search_ranking_evidence(
    observations: list[dict[str, Any]],
    directory: dict[str, str],
    expected_product_id: str,
) -> dict[str, Any]:
    """Prove search-hit branch scope only by independent CURRENT corroboration."""
    relevant = [
        item.get("shape", {}) for item in observations
        if str(item.get("action", "")).startswith("search:")
        and "searchHitFields" in item.get("shape", {})
    ]
    fields = sorted({
        field for shape in relevant
        for field in shape.get("searchHitStockFields", [])
    })[:75]
    # This is the existing, separately established single-branch CURRENT contract,
    # not an inferred location endpoint or a stock field name heuristic.
    confirmed_current: set[tuple[str, int]] = set()
    for item in observations:
        if (
            item.get("method") != "GET"
            or item.get("host") != KWANT_SERVICES_HOST
            or item.get("path") != (
                f"/api/front/products/{expected_product_id}/current"
            )
            or item.get("status") != 200
        ):
            continue
        shape = item.get("shape") or {}
        if shape.get("productIdentityMatches") is not True:
            continue
        depstock = (item.get("query") or {}).get("safeValues", {}).get(
            "depstock"
        )
        if depstock not in directory:
            continue
        for row in shape.get("branchRows", []):
            if (
                row.get("branchId") == depstock
                and row.get("jsonPath") == "$.department_stock"
                and row.get("candidateField") == "stock"
                and row.get("candidateState")
                in ("known_zero", "known_positive")
            ):
                confirmed_current.add((depstock, row["candidateValue"]))

    proven = any(
        (candidate["branchId"], candidate["candidateValue"])
        in confirmed_current
        for item in observations
        if item.get("path") == KWANT_SEARCH_API_PATH
        and item.get("method") == "POST"
        for shape in [item.get("shape") or {}]
        for candidate in shape.get("searchHitControlCandidates", [])
        if str((item.get("body") or {}).get("safeValues", {}).get(
            "depstock", ""
        )) == candidate["branchId"]
    )
    scope = (
        "SELECTED_BRANCH_STOCK_PROVEN_FOR_CONTROL"
        if proven else (
            search_stock_classification(fields) if relevant else UNKNOWN
        )
    )
    return {
        "observedSearchResponseCount": len(relevant),
        "searchHitStockFields": fields,
        "selectedBranchStockProven": proven,
        "stockScope": scope,
        "reason": (
            "INDEPENDENT_CURRENT_PRODUCT_AND_BRANCH_CORROBORATION"
            if proven else (
                "SEARCH_FIELD_HAS_NO_MATCHING_VERIFIED_BRANCH_EVIDENCE"
                if fields else "NO_STOCK_FIELDS_CONFIRMED"
            )
        ),
    }


class ObservedLocationsResponses:
    """Conservative, bounded observer for *real* frontend JSON responses."""

    def __init__(self, recorder: NetworkRecorder, product_id: str) -> None:
        self.recorder = recorder
        self.product_id = product_id
        self.directory: dict[str, str] = {}
        self.records: list[dict[str, Any]] = []
        self.request_records: list[dict[str, Any]] = []

    def on_request(self, request: Any) -> None:
        """Record availability requests separately from page-wide recorder cap."""
        action = self.recorder.action
        if not action.startswith("locations:") or len(self.request_records) >= 60:
            return
        if getattr(request, "resource_type", None) not in {"xhr", "fetch"}:
            return
        ok, safe = sanitize_kwant_network_url(getattr(request, "url", ""))
        if not ok:
            return
        parsed = urlsplit(safe)
        if (
            parsed.hostname not in {KWANT_HOST, KWANT_SERVICES_HOST}
            or not parsed.path.startswith("/api/")
        ):
            return
        headers = getattr(request, "headers", {}) or {}
        self.request_records.append({
            "action": action,
            "method": str(getattr(request, "method", "")).upper(),
            "host": parsed.hostname,
            "path": parsed.path,
            "query": sanitized_query(getattr(request, "url", "")),
            "body": sanitize_body_shape(
                getattr(request, "post_data", None),
                content_type=str(headers.get("content-type", "")),
            ),
        })

    def on_response(self, response: Any) -> None:
        action = self.recorder.action
        # Reserve response capacity for the availability action; page startup
        # calls must not exhaust the diagnostic budget before the UI click.
        if len(self.records) >= LOCATIONS_RESEARCH_MAX_RESPONSES:
            return
        if (
            not action.startswith("locations:")
            and (
                sum(not r["action"].startswith("locations:")
                    for r in self.records) >= 35
            )
        ):
            return
        if not (
            action.startswith("search:")
            or action.startswith("product:")
            or action.startswith("locations:")
        ):
            return
        request = getattr(response, "request", None)
        if not request or getattr(request, "resource_type", None) not in {"xhr", "fetch"}:
            return
        ok, safe = sanitize_kwant_network_url(getattr(response, "url", ""))
        if not ok:
            return
        parsed = urlsplit(safe)
        if (
            not action.startswith("locations:")
            and parsed.hostname != KWANT_SERVICES_HOST
        ):
            return
        if (
            not action.startswith("locations:")
            and parsed.path != KWANT_SEARCH_API_PATH
            and not CURRENT_STOCK_PATH.fullmatch(parsed.path)
            and not BATCH_PRICES_PATH.fullmatch(parsed.path)
        ):
            return
        # The observer records existing service calls, never probes invented URLs.
        if (
            parsed.hostname not in {KWANT_HOST, KWANT_SERVICES_HOST}
            or not parsed.path.startswith("/api/")
        ):
            return
        headers = getattr(response, "headers", {}) or {}
        if "json" not in str(headers.get("content-type", "")).lower():
            return
        try:
            length = int(headers.get("content-length", "0"))
        except (ValueError, TypeError):
            return
        if length > LOCATIONS_RESEARCH_MAX_BODY_BYTES:
            return
        try:
            body = response.body()
            if len(body) > LOCATIONS_RESEARCH_MAX_BODY_BYTES:
                return
            data = json.loads(body)
        except Exception:
            # A failed browser response read must not break the whole probe.
            return
        summary = research_json_shape(data, self.product_id, self.directory)
        batch_evidence = batch_prices_stock_evidence(
            parsed.path, data, self.product_id,
            depstock=sanitized_query(
                getattr(request, "url", ""), action=action
            )["safeValues"].get("depstock"),
        )
        method = str(getattr(request, "method", "")).upper()
        req_headers = getattr(request, "headers", {}) or {}
        self.records.append({
            "action": action,
            "host": parsed.hostname,
            "path": parsed.path,
            "method": method,
            "status": getattr(response, "status", None),
            "query": sanitized_query(getattr(request, "url", ""), action=action),
            "body": sanitize_body_shape(
                getattr(request, "post_data", None),
                content_type=str(req_headers.get("content-type", "")),
                action=action,
            ),
            "shape": summary,
            **({"batchPrices": batch_evidence} if batch_evidence else {}),
        })


def research_product_locations(
    page: Any,
    recorder: NetworkRecorder,
    capture: ObservedLocationsResponses,
    *,
    product_id: str,
    branch_html: str,
    selected_branch_id: str,
    selected_branch_label: str,
) -> dict[str, Any]:
    """Inspect exact-product availability control; never submit a cart action."""
    capture.directory = public_branch_directory_from_html(branch_html)
    result: dict[str, Any] = {
        "productId": product_id,
        "directoryBranchCount": len(capture.directory),
        "verifiedProductPage": False,
        "availabilitySelector": (
            "child " + AVAILABILITY_CHILD_SELECTOR
            + " -> closest parent [role=button]; selected-branch stock-row"
        ),
        "selectedBranchIdForResearch": selected_branch_id or UNKNOWN,
        "selectedBranchIdSource": (
            "RESOLVED_PUBLIC_BRANCH_PAGE_AND_LIVE_DIRECTORY"
            if selected_branch_id else "UNKNOWN"
        ),
        "availabilityControlFound": False,
        "productPageShape": {},
        "productPageBranchListCandidate": False,
        "uiActions": [],
        "availabilityUi": {},
        "filterResearch": {"classification": "NOT_TOGGLED"},
        "observedResponses": [],
        "observedRequests": [],
        "oneShotPerBranchResponseObserved": False,
        "candidateMultiBranchResponseObserved": False,
        "completeness": UNKNOWN,
        "aggregateReconciliation": "NOT_EVALUATED",
        "aggregateBranchStock": UNKNOWN,
        "aggregateBranchStockSource": "UNKNOWN",
        "selectedBranchStock": None,
        "selectedBranchStockSource": "UNKNOWN",
        "centralStock": None,
        "centralStockSource": "UNKNOWN",
        "contractClassification": {"type": "F_INCONCLUSIVE"},
    }
    try:
        recorder.set_action("locations:page")
        page.goto(
            f"{KWANT_ORIGIN}/produkt/{product_id}",
            wait_until="domcontentloaded",
            timeout=45000,
        )
        page.wait_for_timeout(1000)
        if product_page_identifier(page.url) != product_id:
            result["reason"] = "PRODUCT_ID_NOT_VERIFIED"
            return result
        result["verifiedProductPage"] = True
        try:
            next_json = page.locator("script#__NEXT_DATA__").first.text_content(
                timeout=1500
            )
            page_data = json.loads(next_json or "{}")
            props = page_data.get("props", {}).get("pageProps", {})
            exact_product = props.get("product") if isinstance(props, dict) else None
            # Research the exact product subtree only; Next root includes
            # unrelated recommendation and storefront data.
            result["productPageShape"] = research_json_shape(
                exact_product, product_id, capture.directory
            )
            central = extract_exact_product_central_stock(
                page_data, product_id
            )
            result["centralStock"] = central["stock"]
            result["centralStockSource"] = central["source"]
            result["productPageBranchListCandidate"] = (
                result["productPageShape"].get("verifiedDirectoryBranchCount", 0) >= 2
            )
        except Exception:
            result["productPageShape"] = {"status": "UNKNOWN"}

        target = availability_button(page, selected_branch_label)
        if target is None:
            result["reason"] = "ACCESSIBLE_AVAILABILITY_BUTTON_NOT_FOUND"
            return result
        result["availabilityControlFound"] = True
        # This text is scoped to the exact availability button, not to full
        # page body/recommendations. Only a literal aggregate is accepted.
        try:
            control_text = target.inner_text(timeout=1200)
            match = re.search(
                r"W\s+oddzia[lł]ach\s*:\s*(\d[\d \u00a0]*)\s*szt\.?",
                control_text, re.I,
            )
            if match and parse_public_aggregate_quantity(
                match.group(1) + " szt."
            ) is not None:
                result["aggregateBranchStock"] = match.group(1).strip() + " szt."
                result["aggregateBranchStockSource"] = "EXACT_AVAILABILITY_BUTTON"
        except Exception:
            pass

        recorder.set_action("locations:open-branches")
        before = len(capture.records)
        before_requests = len(capture.request_records)
        try:
            target.click(timeout=5000)
            page.wait_for_timeout(1800)
            opened = inspect_availability_dom(page, capture.directory)
            result["availabilityUi"] = opened
            result["uiActions"].append({
                "target": "exact-availability-aria-button",
                "found": True, "clicked": True,
                "newApiResponses": len(capture.records) - before,
                "newApiRequests": len(capture.request_records) - before_requests,
            })
        except Exception:
            result["uiActions"].append({
                "target": "exact-availability-aria-button",
                "found": True, "clicked": False,
            })
            result["reason"] = "AVAILABILITY_CLICK_FAILED"
            return result

        # Optional harmless filter. Never click controls representing cart,
        # purchase, reservation or pickup submission.
        filter_control = None
        try:
            checkbox = page.get_by_role(
                "checkbox", name=AVAILABLE_ONLY_LABEL_RE
            )
            if checkbox.count() == 1 and checkbox.first.is_visible():
                filter_control = checkbox.first
            else:
                label = page.get_by_text(AVAILABLE_ONLY_LABEL_RE)
                if label.count() == 1 and label.first.is_visible():
                    filter_control = label.first
        except Exception:
            filter_control = None
        if filter_control is not None:
            recorder.set_action("locations:available-only-filter")
            before_filter = len(capture.request_records)
            try:
                filter_control.click(timeout=3000)
                page.wait_for_timeout(1300)
                filtered = inspect_availability_dom(page, capture.directory)
                result["filterResearch"] = classify_availability_filter(
                    opened, filtered,
                    len(capture.request_records) - before_filter, True,
                )
                result["availabilityUiAfterFilter"] = filtered
            except Exception:
                result["filterResearch"] = classify_availability_filter(
                    opened, opened, 0, False,
                )
                result["filterResearch"]["reason"] = "FILTER_CLICK_FAILED"
    except Exception:
        result["reason"] = "PUBLIC_PAGE_UNAVAILABLE"
    finally:
        result["observedResponses"] = list(capture.records)
        result["observedRequests"] = list(capture.request_records)
        result["candidateMultiBranchResponseObserved"] = any(
            row.get("shape", {}).get("verifiedDirectoryBranchCount", 0) >= 2
            for row in capture.records
            if row.get("action", "").startswith("locations:")
        )
        strongest = select_strongest_location_candidate(
            capture.records, capture.directory,
            result.get("aggregateBranchStock", UNKNOWN),
        )
        result.update(strongest)
        result["contractClassification"] = classify_locations_contract(
            capture.records, capture.directory, product_id,
            result.get("productPageShape"),
        )
        # A structural classification still needs a manual live review.
        result["oneShotPerBranchResponseObserved"] = (
            result["contractClassification"]["type"] in (
                "A_ONE_SHOT_ALL_BRANCHES", "B_ONE_SHOT_POSITIVE_ONLY"
            )
        )
        selected = selected_branch_current_diagnostic(
            capture.records, product_id, selected_branch_id
        )
        result["selectedBranchStock"] = selected["stock"]
        result["selectedBranchStockSource"] = selected["source"]
        result["searchRankingEvidence"] = summarize_search_ranking_evidence(
            capture.records, capture.directory, product_id
        )
        result["searchRequestDepstockObserved"] = any(
            item.get("path") == KWANT_SEARCH_API_PATH
            and item.get("method") == "POST"
            and str((item.get("body") or {}).get("safeValues", {}).get(
                "depstock", ""
            )) == selected_branch_id
            for item in capture.records
        )
        result["batchPricesResearch"] = [
            item["batchPrices"]
            for item in capture.records
            if "batchPrices" in item
        ][:8]
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--product-query", default="MBN116E")
    parser.add_argument("--branch-label", default="Nowy Sącz")
    parser.add_argument(
        "--text-query",
        default="wyłącznik nadprądowy B16 Hager",
    )
    parser.add_argument(
        "--numeric-product-id",
        default="580",
    )
    parser.add_argument(
        "--out-dir",
        default="build/kwant-live-contract",
    )
    args = parser.parse_args()

    try:
        validate_inputs(
            args.product_query,
            args.branch_label,
            args.text_query,
            args.numeric_product_id,
        )
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    summary = run_live_probe(
        product_query=args.product_query.strip(),
        branch_label=args.branch_label.strip(),
        text_query=args.text_query.strip(),
        numeric_product_id=args.numeric_product_id.strip(),
        out_dir=out_dir,
    )
    write_safe_summary(summary, out_dir)

    print((out_dir / "summary.txt").read_text(encoding="utf-8"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
