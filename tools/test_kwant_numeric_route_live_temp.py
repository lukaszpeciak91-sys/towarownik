import re
import unittest
import urllib.request

class KwantNumericRouteLiveProbe(unittest.TestCase):
    def test_numeric_product_route_resolves_to_product_580(self):
        request = urllib.request.Request(
            "https://kwant.net.pl/produkt/580",
            headers={
                "User-Agent": (
                    "Mozilla/5.0 (Linux; Android 13; Mobile) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/140.0.0.0 Mobile Safari/537.36"
                ),
                "Accept": "text/html,application/xhtml+xml",
                "Accept-Language": "pl-PL,pl;q=0.9",
            },
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            body = response.read().decode("utf-8", errors="replace")
            final_url = response.geturl()

        self.assertRegex(
            final_url,
            re.compile(r"^https://kwant\.net\.pl/produkt/.+-580/?$"),
        )
        self.assertIn('"id":580', body)
        self.assertIn('"slug":"', body)

if __name__ == "__main__":
    unittest.main()
