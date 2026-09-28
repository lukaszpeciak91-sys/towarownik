import json
import unittest

import obi_cross_market_probe as probe


class AvailabilityParsingTest(unittest.TestCase):
    def test_verified_zero_stock_is_distinct_and_preserves_price_currency(self):
        body = json.dumps(
            [
                {
                    "articleNumber": "3496072",
                    "storeSpecificArticle": {
                        "price": 12.99,
                        "stock": 0,
                    },
                    "currency": {
                        "isoCode": "PLN",
                    },
                },
            ],
        ).encode()

        result = probe.parse_availability(
            body,
            "3496072",
        )

        self.assertEqual(
            "verified_stock",
            result["semantic"],
        )
        self.assertEqual(0, result["stock"])
        self.assertEqual("12.99", result["price"])
        self.assertEqual("PLN", result["currency"])

    def test_missing_article_is_not_converted_to_zero_stock(self):
        result = probe.parse_availability(
            b"[]",
            "3496072",
        )

        self.assertEqual(
            "article_missing",
            result["semantic"],
        )
        self.assertIsNone(result["stock"])

    def test_missing_stock_is_distinct_from_zero(self):
        body = json.dumps(
            [
                {
                    "articleNumber": "3496072",
                    "storeSpecificArticle": {
                        "price": 10,
                    },
                    "currency": {
                        "isoCode": "PLN",
                    },
                },
            ],
        ).encode()

        result = probe.parse_availability(
            body,
            "3496072",
        )

        self.assertEqual(
            "missing_stock",
            result["semantic"],
        )
        self.assertIsNone(result["stock"])

    def test_unexpected_json_is_explicit(self):
        result = probe.parse_availability(
            b'{"articleNumber":"3496072"}',
            "3496072",
        )

        self.assertEqual(
            "unexpected_json",
            result["semantic"],
        )


class StoreDirectoryTest(unittest.TestCase):
    def test_directory_reports_allowlist_differences_without_mutating_it(self):
        body = json.dumps(
            {
                "stores": [
                    {
                        "storeNumber": "075",
                        "name": "A",
                        "city": "X",
                        "isActive": True,
                    },
                    {
                        "storeNumber": "099",
                        "name": "B",
                        "address": {
                            "city": "Y",
                        },
                        "isActive": True,
                    },
                ],
            },
        ).encode()

        result = probe.parse_store_directory(
            body,
            {"075", "074"},
        )

        self.assertEqual(
            ["099"],
            result["activeNotInAllowlist"],
        )
        self.assertEqual(
            ["074"],
            result["allowlistNotActive"],
        )


class TransportProfileTest(unittest.TestCase):
    def test_profile_match_requires_current_android_compatibility_values(self):
        source = (
            probe.USER_AGENT
            + probe.HTML_ACCEPT
            + probe.ACCEPT_LANGUAGE
        )

        self.assertTrue(
            probe.transport_profile_matches_kotlin(
                source,
            ),
        )


class FullPageCrossCheckTest(unittest.TestCase):
    def test_extracts_same_store_zero_stock_and_price_semantics(self):
        root = {
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
            + json.dumps(root)
            + "</script>"
        )

        result = probe.full_page_fact(
            html,
            "3496072",
            "075",
        )

        self.assertEqual(
            "verified_product_page",
            result["semantic"],
        )
        self.assertEqual(0, result["stock"])
        self.assertEqual(
            "12.99",
            result["price"],
        )


class UiEndpointDiscoveryTest(unittest.TestCase):
    def test_only_safe_relevant_api_paths_are_retained(self):
        text = (
            '"/api/disc/store/locator/country/PL" '
            '"/api/disc/article-service-proxy/store-specific-articles/v2/PL/pl/" '
            '"/api/disc/unrelated/foo" '
            '"/private/not-api"'
        )

        self.assertEqual(
            [
                "/api/disc/article-service-proxy/store-specific-articles/v2/PL/pl/",
                "/api/disc/store/locator/country/PL",
            ],
            probe.extract_api_paths(text),
        )


if __name__ == "__main__":
    unittest.main()
