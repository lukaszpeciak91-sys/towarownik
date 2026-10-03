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

    def test_explicit_no_results_remains_unsupported(self):
        status = probe.safe_search_status(
            interaction_ok=True,
            target_found=False,
            query_specific_evidence=True,
            any_products=False,
            body_text="Brak wyników dla podanego zapytania",
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
