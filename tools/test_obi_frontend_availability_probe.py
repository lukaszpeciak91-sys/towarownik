import json
import unittest

import obi_frontend_availability_probe as probe


class SanitizationTest(unittest.TestCase):
    def test_query_keeps_only_contract_identifiers(self):
        items = probe.sanitize_query(
            "https://www.obi.pl/api/x?"
            "articleNumbers=3496072&storeNumber=075&"
            "postalCode=33-300&token=secret"
        )

        self.assertEqual(
            [
                {"name": "articleNumbers", "value": "3496072"},
                {"name": "storeNumber", "value": "075"},
                {"name": "postalCode", "value": "<redacted>"},
                {"name": "token", "value": "<redacted>"},
            ],
            items,
        )

    def test_headers_never_expose_cookie_auth_or_csrf_values(self):
        result = probe.sanitize_headers(
            {
                "Cookie": "session=secret",
                "Authorization": "Bearer secret",
                "X-CSRF-Token": "secret",
                "Accept": "application/json",
                "Referer": "https://www.obi.pl/p/3496072/foo?token=secret",
            },
        )

        encoded = json.dumps(result)
        self.assertTrue(result["hasCookie"])
        self.assertTrue(result["hasAuthorization"])
        self.assertNotIn("session=secret", encoded)
        self.assertNotIn("Bearer secret", encoded)
        self.assertNotIn("token=secret", encoded)
        self.assertEqual(
            "https://www.obi.pl/p/3496072/foo",
            result["safeHeaders"]["referer"],
        )

    def test_json_summary_keeps_stock_zero_but_redacts_unknown_strings(self):
        summary = probe.summarize_json(
            {
                "stock": 0,
                "price": 12.99,
                "storeNumber": "075",
                "customerLocation": "private place",
            },
        )
        fields = summary["fields"]

        self.assertEqual(0, fields["stock"])
        self.assertEqual(12.99, fields["price"])
        self.assertEqual("075", fields["storeNumber"])
        self.assertEqual(
            {"type": "string", "length": 13},
            fields["customerLocation"],
        )


class ContractClassificationTest(unittest.TestCase):
    def event(self, event_id, stores, path="/api/availability"):
        return {
            "id": event_id,
            "stage": "availability_open:3496072",
            "relevanceScore": 6,
            "query": [],
            "response": {
                "jsonShape": {"type": "object"},
                "storeNumbers": stores,
            },
            "path": path,
        }

    def test_classifies_one_request_multiple_stores_as_a(self):
        result = probe.classify_contract(
            [self.event(1, ["074", "075", "078"])],
        )

        self.assertEqual("A", result["type"])

    def test_classifies_single_store_json_requests_as_b(self):
        result = probe.classify_contract(
            [
                self.event(1, ["075"]),
                self.event(2, ["074"]),
            ],
        )

        self.assertEqual("B", result["type"])

    def test_does_not_invent_contract_without_json_evidence(self):
        result = probe.classify_contract(
            [
                {
                    "id": 1,
                    "stage": "store_select:3496072:075",
                    "relevanceScore": 8,
                    "query": [
                        {"name": "storeNumber", "value": "075"},
                    ],
                    "response": {
                        "jsonShape": None,
                        "storeNumbers": [],
                    },
                },
            ],
        )

        self.assertEqual("C", result["type"])
        self.assertEqual(
            "no_verified_availability_json_request",
            result["mechanism"],
        )


class RequestBodyTest(unittest.TestCase):
    def test_form_body_redacts_location_but_keeps_store_and_article(self):
        result = probe.summarize_post_data(
            "storeNumber=075&articleNumber=3496072&postalCode=33-300",
            "application/x-www-form-urlencoded",
        )

        self.assertEqual(
            [
                {"name": "storeNumber", "value": "075"},
                {"name": "articleNumber", "value": "3496072"},
                {"name": "postalCode", "value": "<redacted>"},
            ],
            result["fields"],
        )


class FullLookupSemanticsTest(unittest.TestCase):
    def test_zero_stock_remains_zero(self):
        payload = {
            "product": {
                "skuId": "3496072",
                "store": {
                    "information": {
                        "storeId": "075",
                    },
                    "articleData": {
                        "stock": 0,
                        "pricing": {
                            "grossPrice": 12.99,
                        },
                    },
                },
            },
        }
        html = (
            '<script id="__NUXT_DATA__">'
            + json.dumps(payload)
            + "</script>"
        )

        result = probe.full_lookup_fact(
            html,
            "3496072",
            "075",
        )

        self.assertEqual("verified_full_lookup", result["semantic"])
        self.assertEqual(0, result["stock"])
        self.assertEqual("12.99", result["price"])

    def test_missing_article_is_not_stock_zero(self):
        html = (
            '<script id="__NUXT_DATA__">'
            + json.dumps({"product": {"skuId": "1111111"}})
            + "</script>"
        )

        result = probe.full_lookup_fact(
            html,
            "3496072",
            "075",
        )

        self.assertEqual("product_or_store_missing", result["semantic"])
        self.assertIsNone(result["stock"])


if __name__ == "__main__":
    unittest.main()
