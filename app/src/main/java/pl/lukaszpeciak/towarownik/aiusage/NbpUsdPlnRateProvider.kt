package pl.lukaszpeciak.towarownik.aiusage

import android.content.Context
import android.content.SharedPreferences
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

internal const val NBP_USD_PLN_CACHE_MILLIS =
    24L * 60L * 60L * 1000L

internal data class UsdPlnRate(
    val rate: BigDecimal,
    val effectiveDate: String,
    val lastSuccessfulRefresh: Long,
    val isStale: Boolean,
)

internal class NbpUsdPlnRateProvider(
    private val preferences: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val fetchFreshRate: () -> FreshNbpRate?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun loadRate(): UsdPlnRate? =
        withContext(ioDispatcher) {
            val cached = readCached()
            val currentTime = now()
            if (
                cached != null &&
                currentTime - cached.lastSuccessfulRefresh in
                    0 until NBP_USD_PLN_CACHE_MILLIS
            ) {
                return@withContext cached.copy(isStale = false)
            }

            val fresh = runCatching {
                fetchFreshRate()
            }.getOrNull()
            if (fresh != null) {
                val updated = UsdPlnRate(
                    rate = fresh.rate,
                    effectiveDate = fresh.effectiveDate,
                    lastSuccessfulRefresh = currentTime,
                    isStale = false,
                )
                writeCached(updated)
                updated
            } else {
                cached?.copy(isStale = true)
            }
        }

    private fun readCached(): UsdPlnRate? {
        val rate = preferences.getString(KEY_RATE, null)
            ?.toBigDecimalOrNull()
            ?.takeIf { it.signum() > 0 }
            ?: return null
        val effectiveDate =
            preferences.getString(KEY_EFFECTIVE_DATE, null)
                ?.takeIf(EFFECTIVE_DATE_PATTERN::matches)
                ?: return null
        val refreshedAt =
            preferences.getLong(KEY_REFRESHED_AT, -1L)
                .takeIf { it >= 0 }
                ?: return null

        return UsdPlnRate(
            rate = rate,
            effectiveDate = effectiveDate,
            lastSuccessfulRefresh = refreshedAt,
            isStale = false,
        )
    }

    private fun writeCached(rate: UsdPlnRate) {
        preferences.edit()
            .putString(KEY_RATE, rate.rate.toPlainString())
            .putString(KEY_EFFECTIVE_DATE, rate.effectiveDate)
            .putLong(
                KEY_REFRESHED_AT,
                rate.lastSuccessfulRefresh,
            )
            .commit()
    }

    companion object {
        private const val PREFERENCES_NAME = "taksula-ai-fx-v1"
        private const val KEY_RATE = "usd_pln_rate"
        private const val KEY_EFFECTIVE_DATE = "effective_date"
        private const val KEY_REFRESHED_AT = "last_successful_refresh"
        private val EFFECTIVE_DATE_PATTERN =
            Regex("""\d{4}-\d{2}-\d{2}""")

        fun production(context: Context): NbpUsdPlnRateProvider {
            val client = OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .build()

            return NbpUsdPlnRateProvider(
                preferences = context.getSharedPreferences(
                    PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                ),
                fetchFreshRate = {
                    fetchNbpUsdPln(client)
                },
            )
        }
    }
}

internal data class FreshNbpRate(
    val rate: BigDecimal,
    val effectiveDate: String,
)

private fun fetchNbpUsdPln(
    client: OkHttpClient,
): FreshNbpRate? {
    val request = Request.Builder()
        .url(NBP_USD_PLN_URL)
        .get()
        .header("Accept", "application/json")
        .build()

    return client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: return null
        parseNbpUsdPln(body)
    }
}

internal fun parseNbpUsdPln(raw: String): FreshNbpRate? =
    runCatching {
        val root = Json.parseToJsonElement(raw) as JsonObject
        val rates = root["rates"] as? JsonArray
            ?: error("Missing rates")
        val latest = rates.lastOrNull() as? JsonObject
            ?: error("Missing rate")
        val rate = latest["mid"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toBigDecimalOrNull()
            ?.takeIf { it.signum() > 0 }
            ?: error("Invalid rate")
        val effectiveDate = latest["effectiveDate"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) }
            ?: error("Invalid effective date")

        FreshNbpRate(
            rate = rate,
            effectiveDate = effectiveDate,
        )
    }.getOrNull()

private const val NBP_USD_PLN_URL =
    "https://api.nbp.pl/api/exchangerates/rates/a/usd/?format=json"
