import json
import unittest
from pathlib import Path

import kwant_live_contract_probe as probe


class UrlSanitizationTest(unittest.TestCase):
    def test_expected_kwant_url_is_accepted_and_query_is_dropped(self):
        expected, safe = probe.sanitize_kwant_url(
            "https://kwant.net.pl/produkt/example-580?token=secret#frag"
        )
        self.assertTrue(expected)
        self.assertEqual(
            "https://kwant.net.pl/produkt/example-580",
            safe,
        )

    def test_unexpected_hosts_and_unsafe_url_shapes_are_redacted(self):
        cases = [
            "https://example.com/produkt/example-580",
            "http://kwant.net.pl/produkt/example-580",
            "https://user:pass@kwant.net.pl/produkt/example-580",
            "https://kwant.net.pl:444/produkt/example-580",
            "https://[invalid",
        ]
        for value in cases:
            with self.subTest(value=value):
                expected, safe = probe.sanitize_kwant_url(value)
                self.assertFalse(expected)
                self.assertTrue(safe.startswith("REDACTED_"))


class SanitizationTest(unittest.TestCase):
    def test_secret_query_and_body_values_do_not_leak(self):
        secret = "SUPER_SECRET_SESSION_TOKEN"
        query = probe.sanitized_query(
            "https://kwant.net.pl/search?"
            "query=MBN116E&sessionToken=" + secret
        )
        body = probe.sanitize_body_shape(
            json.dumps(
                {
                    "query": "MBN116E",
                    "warehouseId": "205",
                    "csrfToken": secret,
                    "auth": secret,
                }
            ),
            content_type="application/json",
        )

        self.assertIn("sessionToken", query["names"])
        self.assertNotIn("sessionToken", query["safeValues"])
        self.assertEqual("MBN116E", query["safeValues"]["query"])

        self.assertIn("csrfToken", body["fields"])
        self.assertIn("auth", body["fields"])
        self.assertNotIn("csrfToken", body["safeValues"])
        self.assertNotIn("auth", body["safeValues"])
        self.assertEqual("MBN116E", body["safeValues"]["query"])
        self.assertEqual("205", body["safeValues"]["warehouseId"])

        serialized = json.dumps(
            {"query": query, "body": body},
            ensure_ascii=False,
        )
        self.assertNotIn(secret, serialized)

    def test_cookie_summary_contains_names_not_values(self):
        secret = "COOKIE_VALUE_SHOULD_NOT_LEAK"
        summary = probe.cookie_summary(
            [
                {
                    "name": "branch",
                    "value": secret,
                    "domain": "kwant.net.pl",
                    "path": "/",
                    "secure": True,
                    "httpOnly": False,
                    "sameSite": "Lax",
                }
            ]
        )

        serialized = json.dumps(summary)
        self.assertIn("branch", serialized)
        self.assertNotIn(secret, serialized)

    def test_safe_summary_does_not_contain_injected_secret(self):
        secret = "VERY_SECRET_TOKEN"
        summary = probe.build_safe_summary(
            requested_branch_label="Nowy Sącz",
            network=[
                {
                    "action": "branch:select",
                    "method": "POST",
                    "path": "/branch/select",
                    "query": {"names": ["token"], "safeValues": {}},
                    "body": {
                        "encoding": "json",
                        "fields": ["token"],
                        "safeValues": {},
                    },
                    "status": 200,
                    "contentType": "application/json",
                }
            ],
        )
        self.assertFalse(
            probe.safe_report_contains_secret(summary, secret)
        )


class MissingEvidenceTest(unittest.TestCase):
    def test_unknown_is_used_when_evidence_is_absent(self):
        summary = probe.build_safe_summary(
            requested_branch_label="Nowy Sącz",
        )

        self.assertFalse(
            summary["branch"]["targetBranchResolved"]
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["targetBranchPagePath"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["selectionObserved"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["selectionPersisted"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["backendBranchIdentifier"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["search"]["articleCode"]["status"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["product"]["ean"],
        )
        self.assertEqual([], summary["product"]["technicalData"])

    def test_optional_product_fields_are_robustly_missing(self):
        snapshot = probe.product_snapshot(
            page_url=(
                "https://kwant.net.pl/produkt/"
                "synthetic-mbn116e-580"
            ),
            body_text="Synthetic product",
            json_ld_blocks=[],
            branch_label="Nowy Sącz",
        )

        self.assertEqual("580", snapshot["productId"])
        self.assertEqual(probe.UNKNOWN, snapshot["articleNumber"])
        self.assertEqual(probe.UNKNOWN, snapshot["ean"])
        self.assertEqual(probe.UNKNOWN, snapshot["manufacturer"])
        self.assertEqual(probe.UNKNOWN, snapshot["price"])
        self.assertEqual(
            probe.UNKNOWN,
            snapshot["selectedBranchStock"],
        )


class ProductParsingTest(unittest.TestCase):
    def test_structured_product_data_extracts_public_identity(self):
        blocks = [
            json.dumps(
                {
                    "@context": "https://schema.org",
                    "@type": "Product",
                    "name": "Wyłącznik MBN116E HAGER",
                    "sku": "MBN116E/HAG",
                    "gtin13": "3250614312762",
                    "brand": {"@type": "Brand", "name": "HAGER"},
                    "image": [
                        "https://cdn.example.test/product.jpg"
                    ],
                    "url": (
                        "https://kwant.net.pl/produkt/"
                        "wylacznik-mbn116e-hager-580"
                    ),
                    "offers": {
                        "@type": "Offer",
                        "price": "19.99",
                    },
                }
            )
        ]

        fields = probe.structured_product_fields(
            blocks,
            "https://kwant.net.pl/produkt/wylacznik-580",
        )

        self.assertEqual(
            "Wyłącznik MBN116E HAGER",
            fields["name"],
        )
        self.assertEqual("MBN116E/HAG", fields["articleNumber"])
        self.assertEqual("3250614312762", fields["ean"])
        self.assertEqual("HAGER", fields["manufacturer"])
        self.assertEqual("19.99", fields["structuredPrice"])
        self.assertEqual(
            "https://cdn.example.test/product.jpg",
            fields["imageUrl"],
        )

    def test_text_parser_extracts_known_public_fields_and_stock_scopes(self):
        body = """
        Wyłącznik nadprądowy B16 A 1P 6kA MBN116E HAGER
        Producent:
        HAGER
        Kod produktu:
        MBN116E/HAG
        EAN:
        3250614312762
        19,99 zł brutto
        Centrala: 15 szt.
        W oddziałach: 7 szt.
        Nowy Sącz: 3 szt.
        Specyfikacja
        Napięcie znamionowe [V]
        230 / 400 V
        Prąd znamionowy [A]
        16A
        Opis
        """

        snapshot = probe.product_snapshot(
            page_url=(
                "https://kwant.net.pl/produkt/"
                "wylacznik-nadpradowy-mbn116e-hager-580"
            ),
            body_text=body,
            json_ld_blocks=[],
            branch_label="Nowy Sącz",
        )

        self.assertEqual("MBN116E/HAG", snapshot["articleNumber"])
        self.assertEqual("3250614312762", snapshot["ean"])
        self.assertEqual("HAGER", snapshot["manufacturer"])
        self.assertEqual("19,99", snapshot["price"])
        self.assertEqual("ONLINE", snapshot["priceScope"])
        self.assertEqual("15 szt.", snapshot["centralStock"])
        self.assertEqual(
            "7 szt.",
            snapshot["aggregateBranchStock"],
        )
        self.assertEqual(
            "3 szt.",
            snapshot["selectedBranchStock"],
        )
        self.assertIn(
            "Napięcie znamionowe [V]",
            snapshot["technicalData"],
        )


class _FakeCandidate:
    def __init__(self, text):
        self._text = text

    def inner_text(self, timeout=0):
        return self._text


class _FakeLocator:
    def __init__(self, items):
        self._items = items

    def count(self):
        return len(self._items)

    def nth(self, index):
        return self._items[index]


class _FakeBranchButton:
    def __init__(self, token, *, visible=True, enabled=True):
        self.token = token
        self._visible = visible
        self._enabled = enabled
        self.clicked = False

    def is_visible(self):
        return self._visible

    def is_enabled(self):
        return self._enabled

    def click(self, timeout=0):
        self.clicked = True


class _FakeBranchLink:
    def __init__(self, href, text, parent=None):
        self.href = href
        self.text = text
        self.parent = parent

    def get_attribute(self, name):
        return self.href if name == "href" else None

    def inner_text(self, timeout=0):
        return self.text

    def locator(self, selector):
        if selector == "xpath=.." and self.parent is not None:
            return _FakeLocatorWithFirst([self.parent])
        return _FakeLocatorWithFirst([])


class _FakeBranchCard:
    def __init__(self, text, links, buttons, parent=None):
        self.text = text
        self.links = links
        self.buttons = buttons
        self.parent = parent
        for link in self.links:
            link.parent = self

    def inner_text(self, timeout=0):
        return self.text

    def locator(self, selector):
        if selector == "xpath=..":
            return _FakeLocatorWithFirst(
                [self.parent] if self.parent is not None else []
            )
        if "a[href*='hurtownia-elektryczna']" in selector:
            return _FakeLocatorWithFirst(self.links)
        return _FakeLocatorWithFirst([])

    def get_by_role(self, role, name=None):
        if role == "button":
            return _FakeLocatorWithFirst(self.buttons)
        return _FakeLocatorWithFirst([])


class _FakeLocatorWithFirst(_FakeLocator):
    @property
    def first(self):
        return self._items[0]


class _FakeBranchPage:
    def __init__(self, url, cards):
        self.url = url
        self.links = [
            link
            for card in cards
            for link in card.links
        ]

    def locator(self, selector):
        if "a[href*='hurtownia-elektryczna']" in selector:
            return _FakeLocatorWithFirst(self.links)
        return _FakeLocatorWithFirst([])


def _fake_branch_page(*, hrefs, buttons, card_text="Nowy Sącz Wybierz oddział"):
    links = [
        _FakeBranchLink(href, "Nowy Sącz")
        for href in hrefs
    ]
    card = _FakeBranchCard(card_text, links, buttons)
    return _FakeBranchPage(
        "https://kwant.net.pl/lista-hurtowni-elektrycznych",
        [card],
    ), card


class _GenericBranchListPage:
    def locator(self, selector):
        if "[class*='branch']" in selector:
            return _FakeLocator(
                [_FakeCandidate("Oddział Nowy Sącz Wybierz oddział")]
            )
        return _FakeLocator([])


class _SelectedBranchPage:
    def locator(self, selector):
        if "[aria-current='true']" in selector:
            return _FakeLocator(
                [_FakeCandidate("Aktualny oddział Nowy Sącz")]
            )
        return _FakeLocator([])


class BranchTargetingTest(unittest.TestCase):
    def candidate(
        self,
        *,
        label,
        page_url,
        card_text,
        branch_page_urls=None,
        select_button_count=1,
        button_token="button",
    ):
        return {
            "linkIndex": 0,
            "linkText": label,
            "pageUrl": page_url,
            "cardText": card_text,
            "branchPageUrls": (
                branch_page_urls
                if branch_page_urls is not None
                else [page_url]
            ),
            "selectButtonCount": select_button_count,
            "buttonToken": button_token,
        }

    def test_multiple_branch_cards_resolve_exact_requested_branch(self):
        bialystok = self.candidate(
            label="Białystok",
            page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-bialystok/216"
            ),
            card_text="Białystok Wybierz oddział",
            button_token="bialystok-button",
        )
        nowy_sacz = self.candidate(
            label="Nowy Sącz",
            page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-nowy%20sacz/205"
            ),
            card_text="Nowy Sącz Wybierz oddział",
            button_token="nowy-sacz-button",
        )
        central = self.candidate(
            label="Magazyn centralny",
            page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "magazyn-centralny/1"
            ),
            card_text="Magazyn centralny Wybierz oddział",
            button_token="central-button",
        )

        resolved = probe.resolve_unique_branch_candidate(
            [bialystok, nowy_sacz, central],
            "Nowy Sącz",
        )

        self.assertIsNotNone(resolved)
        self.assertEqual("nowy-sacz-button", resolved["buttonToken"])
        self.assertEqual(
            "205",
            probe.extract_branch_page_identifier(
                resolved["pageUrl"]
            ),
        )

    def test_generic_ancestor_with_multiple_branch_buttons_is_ineligible(self):
        generic_ancestor = self.candidate(
            label="Nowy Sącz",
            page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-nowy%20sacz/205"
            ),
            card_text=(
                "Białystok Wybierz oddział "
                "Nowy Sącz Wybierz oddział"
            ),
            branch_page_urls=[
                (
                    "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                    "hurtownia-elektryczna-bialystok/216"
                ),
                (
                    "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                    "hurtownia-elektryczna-nowy%20sacz/205"
                ),
            ],
            select_button_count=2,
            button_token="first-generic-button",
        )

        self.assertIsNone(
            probe.resolve_unique_branch_candidate(
                [generic_ancestor],
                "Nowy Sącz",
            )
        )

    def test_duplicate_candidates_same_branch_identity_resolve_but_missing_label_does_not(self):
        first = self.candidate(
            label="Nowy Sącz",
            page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-nowy%20sacz/205"
            ),
            card_text="Nowy Sącz Wybierz oddział",
            button_token="first",
        )
        duplicate = dict(first)
        duplicate["linkIndex"] = 1
        duplicate["buttonToken"] = "second"

        resolved = probe.resolve_unique_branch_candidate(
            [first, duplicate],
            "Nowy Sącz",
        )

        self.assertIsNotNone(resolved)
        self.assertEqual("first", resolved["buttonToken"])
        self.assertIsNone(
            probe.resolve_unique_branch_candidate(
                [first],
                "Kraków",
            )
        )

    def test_three_raw_links_to_same_nowy_sacz_url_are_one_unique_identity(self):
        url = (
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-nowy%20sacz/205"
        )
        candidate = self.candidate(
            label="Nowy Sącz",
            page_url=url,
            card_text="Nowy Sącz Wybierz oddział",
            branch_page_urls=[url, f"{url}/", url],
            select_button_count=2,
        )

        self.assertEqual(
            [url],
            probe.unique_branch_page_urls(
                candidate["branchPageUrls"],
            ),
        )
        self.assertTrue(
            probe.branch_target_candidate_valid(
                candidate,
                "Nowy Sącz",
            )
        )

    def test_two_matching_buttons_keep_candidate_valid_and_first_visible_enabled_is_selected(self):
        url = (
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-nowy%20sacz/205"
        )
        first = _FakeBranchButton("first")
        second = _FakeBranchButton("second")
        page, _ = _fake_branch_page(
            hrefs=[url, url, url],
            buttons=[first, second],
        )

        candidates = probe.enumerate_branch_candidates(
            page,
            "Nowy Sącz",
        )
        target = probe.resolve_unique_branch_candidate(
            candidates,
            "Nowy Sącz",
        )
        selected = probe.validated_branch_button(
            page,
            candidate=target,
            branch_label="Nowy Sącz",
        )

        self.assertIsNotNone(target)
        self.assertEqual(2, target["selectButtonCount"])
        self.assertIs(first, selected)

    def test_hidden_or_disabled_first_button_falls_through_to_visible_enabled_second(self):
        url = (
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-nowy%20sacz/205"
        )
        hidden = _FakeBranchButton(
            "hidden",
            visible=False,
            enabled=True,
        )
        disabled = _FakeBranchButton(
            "disabled",
            visible=True,
            enabled=False,
        )
        usable = _FakeBranchButton(
            "usable",
            visible=True,
            enabled=True,
        )
        page, _ = _fake_branch_page(
            hrefs=[url, url],
            buttons=[hidden, disabled, usable],
        )

        target = probe.resolve_unique_branch_candidate(
            probe.enumerate_branch_candidates(
                page,
                "Nowy Sącz",
            ),
            "Nowy Sącz",
        )
        selected = probe.validated_branch_button(
            page,
            candidate=target,
            branch_label="Nowy Sącz",
        )

        self.assertIs(usable, selected)

    def test_multiple_raw_links_to_different_branch_urls_are_rejected(self):
        nowy_sacz = (
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-nowy%20sacz/205"
        )
        bialystok = (
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-bialystok/216"
        )
        candidate = self.candidate(
            label="Nowy Sącz",
            page_url=nowy_sacz,
            card_text=(
                "Białystok Wybierz oddział "
                "Nowy Sącz Wybierz oddział"
            ),
            branch_page_urls=[
                nowy_sacz,
                bialystok,
                nowy_sacz,
            ],
            select_button_count=3,
        )

        self.assertFalse(
            probe.branch_target_candidate_valid(
                candidate,
                "Nowy Sącz",
            )
        )
        self.assertIsNone(
            probe.resolve_unique_branch_candidate(
                [candidate],
                "Nowy Sącz",
            )
        )

    def test_generic_multi_branch_ancestor_with_multiple_buttons_never_selects(self):
        nowy_sacz = (
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-nowy%20sacz/205"
        )
        bialystok = (
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-bialystok/216"
        )
        page, _ = _fake_branch_page(
            hrefs=[bialystok, nowy_sacz],
            buttons=[
                _FakeBranchButton("first"),
                _FakeBranchButton("second"),
            ],
            card_text=(
                "Białystok Wybierz oddział "
                "Nowy Sącz Wybierz oddział"
            ),
        )

        self.assertEqual(
            [],
            probe.enumerate_branch_candidates(
                page,
                "Nowy Sącz",
            ),
        )

    def test_no_visible_enabled_button_returns_none_without_clicking(self):
        url = (
            "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
            "hurtownia-elektryczna-nowy%20sacz/205"
        )
        hidden = _FakeBranchButton(
            "hidden",
            visible=False,
            enabled=True,
        )
        disabled = _FakeBranchButton(
            "disabled",
            visible=True,
            enabled=False,
        )
        page, _ = _fake_branch_page(
            hrefs=[url, url],
            buttons=[hidden, disabled],
        )

        target = probe.resolve_unique_branch_candidate(
            probe.enumerate_branch_candidates(
                page,
                "Nowy Sącz",
            ),
            "Nowy Sącz",
        )
        selected = probe.validated_branch_button(
            page,
            candidate=target,
            branch_label="Nowy Sącz",
        )

        self.assertIsNotNone(target)
        self.assertIsNone(selected)
        self.assertFalse(hidden.clicked)
        self.assertFalse(disabled.clicked)

    def test_branch_card_text_can_resolve_when_href_slug_omits_label(self):
        numeric_href = self.candidate(
            label="Szczegóły oddziału",
            page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna/312"
            ),
            card_text="Tarnów Wybierz oddział",
        )

        resolved = probe.resolve_unique_branch_candidate(
            [numeric_href],
            "Tarnów",
        )

        self.assertIsNotNone(resolved)
        self.assertEqual(
            "312",
            probe.extract_branch_page_identifier(
                resolved["pageUrl"]
            ),
        )

    def test_branch_resolution_uses_label_and_link_not_hard_coded_205(self):
        tarnow = self.candidate(
            label="Tarnów",
            page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-tarnow/312"
            ),
            card_text="Tarnów Wybierz oddział",
        )

        resolved = probe.resolve_unique_branch_candidate(
            [tarnow],
            "Tarnów",
        )

        self.assertIsNotNone(resolved)
        self.assertEqual(
            "312",
            probe.extract_branch_page_identifier(
                resolved["pageUrl"]
            ),
        )


class BranchEvidenceTest(unittest.TestCase):
    def test_page_identifier_is_not_automatically_backend_identifier(self):
        summary = probe.build_safe_summary(
            requested_branch_label="Nowy Sącz",
            branch_page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-nowy-sacz/205"
            ),
            network=[],
        )

        self.assertEqual(
            False,
            summary["branch"]["targetBranchResolved"],
        )
        self.assertEqual(
            (
                "/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-nowy-sacz/205"
            ),
            summary["branch"]["targetBranchPagePath"],
        )
        self.assertEqual(
            "205",
            summary["branch"]["branchPageIdentifier"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["backendBranchIdentifier"],
        )

    def test_safe_summary_reports_uniquely_resolved_target_branch(self):
        summary = probe.build_safe_summary(
            requested_branch_label="Nowy Sącz",
            branch_page_url=(
                "https://kwant.net.pl/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-nowy%20sacz/205"
            ),
            target_branch_resolved=True,
            backend_branch_identifier=probe.UNKNOWN,
        )

        self.assertTrue(
            summary["branch"]["targetBranchResolved"]
        )
        self.assertEqual(
            (
                "/lista-hurtowni-elektrycznych/"
                "hurtownia-elektryczna-nowy%20sacz/205"
            ),
            summary["branch"]["targetBranchPagePath"],
        )
        self.assertEqual(
            "205",
            summary["branch"]["branchPageIdentifier"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["backendBranchIdentifier"],
        )

    def test_generic_branch_list_label_is_not_selection_evidence(self):
        self.assertFalse(
            probe.branch_marker(
                _GenericBranchListPage(),
                "Nowy Sącz",
            )
        )

        observed, persisted = probe.derive_branch_selection_status(
            branch_click_ok=True,
            marker_after_select=False,
            marker_after_reload=False,
            changed={
                "cookies": [],
                "localStorage": [],
                "sessionStorage": [],
            },
            persisted={
                "cookies": [],
                "localStorage": [],
                "sessionStorage": [],
            },
            network=[],
        )

        self.assertIsNone(observed)
        self.assertIsNone(persisted)
        summary = probe.build_safe_summary(
            requested_branch_label="Nowy Sącz",
            selection_observed=observed,
            selection_persisted=persisted,
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["selectionObserved"],
        )
        self.assertEqual(
            probe.UNKNOWN,
            summary["branch"]["selectionPersisted"],
        )

    def test_explicit_selected_branch_ui_is_valid_selection_evidence(self):
        self.assertTrue(
            probe.branch_marker(
                _SelectedBranchPage(),
                "Nowy Sącz",
            )
        )

    def test_branch_related_state_change_and_reload_can_prove_selection(self):
        observed, persisted = probe.derive_branch_selection_status(
            branch_click_ok=True,
            marker_after_select=False,
            marker_after_reload=False,
            changed={
                "cookies": ["kwant.net.pl|/|selectedBranch"],
                "localStorage": [],
                "sessionStorage": [],
            },
            persisted={
                "cookies": ["kwant.net.pl|/|selectedBranch"],
                "localStorage": [],
                "sessionStorage": [],
            },
            network=[],
        )

        self.assertTrue(observed)
        self.assertTrue(persisted)

    def test_generic_branch_page_request_is_not_selection_request_evidence(self):
        network = [
            {
                "action": "branch:select",
                "path": "/store/nowy-sacz",
                "query": {"safeValues": {}},
                "body": None,
            }
        ]

        self.assertFalse(
            probe.branch_selection_request_observed(network)
        )

    def test_explicit_branch_change_request_is_selection_evidence(self):
        network = [
            {
                "action": "branch:select",
                "path": "/api/store/change",
                "query": {"safeValues": {}},
                "body": None,
            }
        ]

        self.assertTrue(
            probe.branch_selection_request_observed(network)
        )

    def test_backend_identifier_requires_observed_selection_request_field(self):
        network = [
            {
                "action": "branch:select",
                "query": {
                    "safeValues": {
                        "warehouseId": "NS-12",
                    }
                },
                "body": None,
            }
        ]

        self.assertEqual(
            "NS-12",
            probe.infer_backend_branch_identifier(network),
        )


class SearchEvidenceTest(unittest.TestCase):
    def test_unrelated_product_links_do_not_prove_search_support(self):
        status = probe.safe_search_status(
            interaction_ok=True,
            target_found=False,
            query_specific_evidence=False,
            any_products=True,
            body_text=(
                "Polecane produkty "
                "/produkt/unrelated-one /produkt/unrelated-two"
            ),
        )

        self.assertEqual(probe.UNKNOWN, status)

    def test_query_specific_success_with_products_is_supported(self):
        status = probe.safe_search_status(
            interaction_ok=True,
            target_found=False,
            query_specific_evidence=True,
            any_products=True,
            body_text="Wyniki wyszukiwania dla MBN116E",
        )

        self.assertEqual("SUPPORTED", status)

    def test_explicit_no_results_remains_unsupported_even_with_unrelated_products(self):
        status = probe.safe_search_status(
            interaction_ok=True,
            target_found=False,
            query_specific_evidence=True,
            any_products=True,
            body_text=(
                "Brak wyników dla podanego zapytania. "
                "Polecane produkty poniżej."
            ),
        )

        self.assertEqual("UNSUPPORTED", status)

    def test_search_request_requires_submitted_query_value(self):
        network = [
            {
                "action": "search:article",
                "path": "/search",
                "query": {
                    "safeValues": {
                        "query": "MBN116E",
                    }
                },
                "body": None,
            }
        ]

        self.assertTrue(
            probe.search_request_observed(
                network,
                action="search:article",
                query="MBN116E",
            )
        )
        self.assertFalse(
            probe.search_request_observed(
                network,
                action="search:article",
                query="6743009",
            )
        )

    def test_matching_product_url_does_not_fall_back_to_unrelated_link(self):
        result = {
            "_links": [
                {
                    "url": (
                        "https://kwant.net.pl/produkt/"
                        "unrelated-product-580"
                    ),
                    "text": "Polecany inny produkt",
                }
            ]
        }

        self.assertEqual(
            "",
            probe.matching_product_url(result, "MBN116E"),
        )


class StatePersistenceTest(unittest.TestCase):
    def test_changed_and_persisted_state_reports_key_names_only(self):
        before_cookies = {"kwant.net.pl|/|branch": "old"}
        after_cookies = {"kwant.net.pl|/|branch": "new"}
        reload_cookies = {"kwant.net.pl|/|branch": "new"}
        before_storage = {
            "localStorage": {},
            "sessionStorage": {},
        }
        after_storage = {
            "localStorage": {"selectedBranch": "205"},
            "sessionStorage": {},
        }
        reload_storage = {
            "localStorage": {"selectedBranch": "205"},
            "sessionStorage": {},
        }

        changed = probe.state_changed_keys(
            before_cookies,
            after_cookies,
            before_storage,
            after_storage,
        )
        persisted = probe.persisted_state_keys(
            after_cookies,
            reload_cookies,
            after_storage,
            reload_storage,
            changed,
        )

        self.assertEqual(
            ["kwant.net.pl|/|branch"],
            changed["cookies"],
        )
        self.assertEqual(
            ["selectedBranch"],
            changed["localStorage"],
        )
        self.assertEqual(changed, persisted)


class ReportWriterTest(unittest.TestCase):
    def test_report_writer_uses_unknown_and_safe_network_shapes(self):
        summary = probe.build_safe_summary(
            requested_branch_label="Nowy Sącz",
            selection_observed=True,
            selection_persisted=None,
            network=[
                {
                    "action": "search:article",
                    "method": "GET",
                    "path": "/search",
                    "query": {
                        "names": ["query"],
                        "safeValues": {"query": "MBN116E"},
                    },
                    "body": None,
                    "status": 200,
                    "contentType": "text/html",
                }
            ],
        )

        out_dir = Path(self._testMethodName)
        try:
            probe.write_safe_summary(summary, out_dir)
            text = (out_dir / "summary.txt").read_text(
                encoding="utf-8"
            )
            parsed = json.loads(
                (out_dir / "summary.json").read_text(
                    encoding="utf-8"
                )
            )
        finally:
            for path in out_dir.glob("*"):
                path.unlink()
            out_dir.rmdir()

        self.assertIn("selectionObserved=true", text)
        self.assertIn("selectionPersisted=UNKNOWN", text)
        self.assertIn("articleCode=UNKNOWN", text)
        self.assertEqual(
            probe.UNKNOWN,
            parsed["product"]["selectedBranchStock"],
        )


if __name__ == "__main__":
    unittest.main()
