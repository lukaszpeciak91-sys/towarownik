package pl.lukaszpeciak.towarownik.aiusage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.lukaszpeciak.towarownik.convertUsdToPln

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NbpUsdPlnRateProviderTest {
    @Test
    fun `valid NBP payload parses and converts cost exactly`() {
        val parsed = parseNbpUsdPln(
            """
            {
              "table":"A",
              "currency":"dolar amerykański",
              "code":"USD",
              "rates":[
                {
                  "no":"185/A/NBP/2026",
                  "effectiveDate":"2026-09-25",
                  "mid":3.6154
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(BigDecimal("3.6154"), parsed?.rate)
        assertEquals("2026-09-25", parsed?.effectiveDate)
        assertEquals(
            BigDecimal("0.0036154"),
            convertUsdToPln(
                BigDecimal("0.001"),
                BigDecimal("3.6154"),
            ),
        )
    }

    @Test
    fun `fresh cached rate is reused without another fetch`() = runBlocking {
        val context =
            ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(
            "nbp-cache-test-" + System.nanoTime(),
            Context.MODE_PRIVATE,
        )
        var now = 1_000_000L
        var fetches = 0
        val provider = NbpUsdPlnRateProvider(
            preferences = prefs,
            now = { now },
            fetchFreshRate = {
                fetches += 1
                FreshNbpRate(
                    rate = BigDecimal("3.60"),
                    effectiveDate = "2026-09-25",
                )
            },
        )

        val first = provider.loadRate()
        now += NBP_USD_PLN_CACHE_MILLIS / 2
        val second = provider.loadRate()

        assertEquals(1, fetches)
        assertEquals(first?.rate, second?.rate)
        assertFalse(second?.isStale ?: true)
    }

    @Test
    fun `unavailable NBP keeps identifiable stale cached rate`() = runBlocking {
        val context =
            ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(
            "nbp-stale-test-" + System.nanoTime(),
            Context.MODE_PRIVATE,
        )
        var now = 1_000_000L
        var freshAvailable = true
        val provider = NbpUsdPlnRateProvider(
            preferences = prefs,
            now = { now },
            fetchFreshRate = {
                if (freshAvailable) {
                    FreshNbpRate(
                        rate = BigDecimal("3.70"),
                        effectiveDate = "2026-09-24",
                    )
                } else {
                    null
                }
            },
        )

        provider.loadRate()
        freshAvailable = false
        now += NBP_USD_PLN_CACHE_MILLIS + 1
        val stale = provider.loadRate()

        assertEquals(BigDecimal("3.70"), stale?.rate)
        assertEquals("2026-09-24", stale?.effectiveDate)
        assertTrue(stale?.isStale == true)
        assertEquals(1_000_000L, stale?.lastSuccessfulRefresh)
    }

    @Test
    fun `no cached rate and unavailable NBP returns null`() = runBlocking {
        val context =
            ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(
            "nbp-empty-test-" + System.nanoTime(),
            Context.MODE_PRIVATE,
        )
        val provider = NbpUsdPlnRateProvider(
            preferences = prefs,
            now = { 10L },
            fetchFreshRate = { null },
        )

        assertNull(provider.loadRate())
    }
}
