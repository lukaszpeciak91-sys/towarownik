package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.LocalProduct
import pl.lukaszpeciak.towarownik.product.TechnicalFact
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.ProductRef
import pl.lukaszpeciak.towarownik.product.provider.ProviderId
import pl.lukaszpeciak.towarownik.product.provider.ProviderPriceScope
import pl.lukaszpeciak.towarownik.product.provider.ProviderProduct

class AdvisorProductShapingTest {
    @Test
    fun `oversized provider facts are bounded for advisor contract`() {
        val shaped = oversizedProduct().toAdvisorVerifiedProduct("MBN116E")

        assertEquals("580", shaped.productId)
        assertEquals("580", shaped.obik)
        assertEquals("MBN116E/HAG", shaped.articleNumber)
        assertEquals("Wyłącznik nadprądowy B16", shaped.name)
        assertEquals("Hager", shaped.brand)
        assertEquals(362, shaped.stock)
        assertEquals(10113, shaped.centralStock)
        assertEquals("m", shaped.stockUnit)
        assertEquals(BigDecimal("14.55"), shaped.price)
        assertEquals("online", shaped.priceScope)

        assertTrue(
            shaped.shortDescription!!.length <=
                ADVISOR_PRODUCT_DESCRIPTION_MAX_CHARS,
        )
        assertTrue(!shaped.shortDescription.contains("  "))
        assertEquals(
            ADVISOR_PRODUCT_TECHNICAL_FACTS_MAX,
            shaped.technicalFacts.size,
        )
        shaped.technicalFacts.forEach { fact ->
            assertTrue(
                fact.label.length <=
                    ADVISOR_PRODUCT_FACT_LABEL_MAX_CHARS,
            )
            assertTrue(
                fact.value.length <=
                    ADVISOR_PRODUCT_FACT_VALUE_MAX_CHARS,
            )
            assertTrue(fact.label.isNotBlank())
            assertTrue(fact.value.isNotBlank())
            assertTrue(!fact.label.contains("  "))
            assertTrue(!fact.value.contains("  "))
        }

        assertTrue(
            shaped.technicalFacts[0].label.startsWith(
                "Fakt 1 bardzo długa etykieta",
            ),
        )
        assertEquals("Fakt 2", shaped.technicalFacts[1].label)
    }

    @Test
    fun `already bounded provider facts keep content after whitespace normalization`() {
        val source = baseProduct(
            shortDescription = "Opis produktu z jedną linią.",
            technicalFacts = listOf(
                TechnicalFact("Napięcie", "230 V"),
                TechnicalFact("Prąd", "16 A"),
            ),
        )

        val shaped = source.toAdvisorVerifiedProduct("potrzebuję 16 A")

        assertEquals(source.shortDescription, shaped.shortDescription)
        assertEquals(
            listOf(
                AdvisorTechnicalFact("Napięcie", "230 V"),
                AdvisorTechnicalFact("Prąd", "16 A"),
            ),
            shaped.technicalFacts,
        )
        assertEquals(source.stock, shaped.stock)
        assertEquals(source.centralStock, shaped.centralStock)
        assertEquals(source.stockUnit, shaped.stockUnit)
        assertEquals(source.grossPrice, shaped.price)
    }

    @Test
    fun `more than six facts without relevance keeps first six in source order`() {
        val facts = (1..8).map {
            TechnicalFact("Parametr $it", "wartość $it")
        }

        val shaped = baseProduct(
            shortDescription = null,
            technicalFacts = facts,
        ).toAdvisorVerifiedProduct("szukam dobrego produktu Hager")

        assertEquals(
            (1..6).map { AdvisorTechnicalFact("Parametr $it", "wartość $it") },
            shaped.technicalFacts,
        )
    }

    @Test
    fun `late exact current fact is rescued with minimal displacement`() {
        val shaped = baseProduct(
            shortDescription = null,
            technicalFacts = baselineFacts() +
                TechnicalFact("Prąd znamionowy", "16 A"),
        ).toAdvisorVerifiedProduct("potrzebuję wyłącznik 16 A")

        assertEquals(
            listOf(
                "Parametr 1",
                "Parametr 2",
                "Parametr 3",
                "Parametr 4",
                "Parametr 5",
                "Prąd znamionowy",
            ),
            shaped.technicalFacts.map { it.label },
        )
        assertEquals("16 A", shaped.technicalFacts.last().value)
    }

    @Test
    fun `strong technical values rescue late facts conservatively`() {
        val cases = listOf(
            Triple("szukam 16 A", "Prąd znamionowy", "16 A"),
            Triple("potrzebuję 400 V", "Napięcie znamionowe", "400 V"),
            Triple("oprawa IP65", "Stopień ochrony", "IP65"),
            Triple("przewód 2,5 mm2", "Przekrój żyły", "2,5 mm2"),
            Triple("urządzenie 3 fazy", "Liczba faz", "3"),
        )

        cases.forEach { (query, label, value) ->
            val shaped = baseProduct(
                shortDescription = null,
                technicalFacts = baselineFacts() + TechnicalFact(label, value),
            ).toAdvisorVerifiedProduct(query)

            assertEquals(query, label, shaped.technicalFacts.last().label)
            assertEquals(query, value, shaped.technicalFacts.last().value)
            assertEquals(
                query,
                baselineFacts().take(5).map { it.label },
                shaped.technicalFacts.take(5).map { it.label },
            )
        }
    }

    @Test
    fun `vague lexical overlap does not reshuffle facts`() {
        val facts = baselineFacts() +
            TechnicalFact("Producent", "Hager")

        val shaped = baseProduct(
            shortDescription = null,
            technicalFacts = facts,
        ).toAdvisorVerifiedProduct("dobry wyłącznik Hager")

        assertEquals(
            baselineFacts().map {
                AdvisorTechnicalFact(it.label, it.value)
            },
            shaped.technicalFacts,
        )
    }

    @Test
    fun `OBI and provider products use the same relevance rescue policy`() {
        val facts = baselineFacts() +
            TechnicalFact("Stopień ochrony", "IP65")
        val provider = baseProduct(
            shortDescription = "  ten   sam opis  ",
            technicalFacts = facts,
            stockUnit = "m²",
        ).toAdvisorVerifiedProduct("potrzebuję IP65")
        val obi = LocalProduct(
            obik = "1234567",
            name = "Oprawa",
            stock = 4,
            grossPrice = BigDecimal("19.99"),
            productUrl = "https://www.obi.pl/p/1234567/test",
            ean = "5900000000000",
            storeNumber = "075",
            brand = "Marka",
            shortDescription = "  ten   sam opis  ",
            technicalFacts = facts,
        ).toAdvisorVerifiedProduct("potrzebuję IP65")

        assertEquals(provider.shortDescription, obi.shortDescription)
        assertEquals(provider.technicalFacts, obi.technicalFacts)
        assertEquals("580", provider.productId)
        assertEquals("MBN116E/HAG", provider.articleNumber)
        assertEquals(362, provider.stock)
        assertEquals(10113, provider.centralStock)
        assertEquals(BigDecimal("14.55"), provider.price)
        assertEquals("online", provider.priceScope)
        assertEquals("1234567", obi.obik)
        assertEquals(4, obi.stock)
        assertEquals("m²", obi.stockUnit)
        assertEquals(BigDecimal("19.99"), obi.price)
    }

    @Test
    fun `shaped oversized provider product serializes as valid v3 continuation`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """
                            {
                              "type":"answer",
                              "responseId":"resp_final",
                              "text":"OK",
                              "productRefs":[],
                              "webSearchCalls":0
                            }
                            """.trimIndent(),
                        ),
                )
                val client = AdvisorProxyClient(
                    appToken = "test-token",
                    baseUrl = server.url("/"),
                )
                val shaped = oversizedProduct().toAdvisorVerifiedProduct("MBN116E")

                val result = client.continueTurn(
                    responseId = "resp_tool",
                    callId = "call_1",
                    providerId = "kwant-pl",
                    branchId = "205",
                    continuation = AdvisorToolContinuation.Verified(
                        AdvisorVerifiedToolResult(
                            providerId = "kwant-pl",
                            storeNumber = "205",
                            results = listOf(
                                AdvisorVerifiedQueryResult(
                                    query = "MBN116E",
                                    status = AdvisorQueryResultStatus.VERIFIED,
                                    products = listOf(shaped),
                                ),
                            ),
                        ),
                    ),
                )

                assertTrue(result is AdvisorProxyCallResult.Success)

                val request = server.takeRequest()
                assertEquals("/v1/agent/continue", request.path)
                val root = Json.parseToJsonElement(
                    request.body.readUtf8(),
                ).jsonObject
                assertEquals(
                    3,
                    root["protocolVersion"]!!.jsonPrimitive.content.toInt(),
                )
                val product = root["result"]!!
                    .jsonObject["results"]!!
                    .jsonArray.single()
                    .jsonObject["products"]!!
                    .jsonArray.single()
                    .jsonObject

                assertEquals("580", product["productId"]!!.jsonPrimitive.content)
                assertEquals("362", product["stock"]!!.jsonPrimitive.content)
                assertEquals("m", product["stockUnit"]!!.jsonPrimitive.content)
                assertEquals(
                    "10113",
                    product["centralStock"]!!.jsonPrimitive.content,
                )
                assertEquals(
                    "14.55",
                    product["price"]!!.jsonPrimitive.content,
                )

                val description =
                    product["shortDescription"]!!.jsonPrimitive.content
                assertTrue(
                    description.length <=
                        ADVISOR_PRODUCT_DESCRIPTION_MAX_CHARS,
                )
                val facts = product["technicalFacts"]!!.jsonArray
                assertTrue(
                    facts.size <= ADVISOR_PRODUCT_TECHNICAL_FACTS_MAX,
                )
                facts.forEach { element ->
                    val fact = element.jsonObject
                    assertTrue(
                        fact["label"]!!.jsonPrimitive.content.length <=
                            ADVISOR_PRODUCT_FACT_LABEL_MAX_CHARS,
                    )
                    assertTrue(
                        fact["value"]!!.jsonPrimitive.content.length <=
                            ADVISOR_PRODUCT_FACT_VALUE_MAX_CHARS,
                    )
                }
            }
        }

    private fun baselineFacts(): List<TechnicalFact> =
        (1..6).map {
            TechnicalFact("Parametr $it", "wartość $it")
        }

    private fun oversizedProduct(): ProviderProduct =
        baseProduct(
            shortDescription =
                "  Bardzo   długi opis produktu " +
                    "z wieloma szczegółami technicznymi i użytkowymi. ".repeat(8),
            technicalFacts = buildList {
                add(
                    TechnicalFact(
                        "Fakt 1 bardzo długa etykieta " +
                            "uzupełniona o dodatkowy tekst ".repeat(3),
                        "Wartość pierwszego faktu z dużą ilością szczegółów " +
                            "technicznych i dodatkowych parametrów ".repeat(3),
                    ),
                )
                add(TechnicalFact("   Fakt    2   ", "   230    V   "))
                add(TechnicalFact("   ", "wartość do odrzucenia"))
                add(TechnicalFact("Fakt 3", "wartość 3"))
                add(TechnicalFact("Fakt 4", "wartość 4"))
                add(TechnicalFact("Fakt 5", "wartość 5"))
                add(TechnicalFact("Fakt 6", "wartość 6"))
                add(TechnicalFact("Fakt 7", "wartość 7"))
                add(TechnicalFact("Fakt 8", "wartość 8"))
            },
        )

    private fun baseProduct(
        shortDescription: String?,
        technicalFacts: List<TechnicalFact>,
    ): ProviderProduct =
        ProviderProduct(
            ref = ProductRef(
                providerId = ProviderId("kwant-pl"),
                productId = "580",
            ),
            branchId = BranchId("205"),
            name = "Wyłącznik nadprądowy B16",
            stock = 362,
            centralStock = 10113,
            stockUnit = "m",
            grossPrice = BigDecimal("14.55"),
            priceScope = ProviderPriceScope.ONLINE,
            productUrl = "https://kwant.net.pl/produkt/test-580",
            ean = "3250614312762",
            articleNumber = "MBN116E/HAG",
            brand = "Hager",
            shortDescription = shortDescription,
            technicalFacts = technicalFacts,
        )
}
