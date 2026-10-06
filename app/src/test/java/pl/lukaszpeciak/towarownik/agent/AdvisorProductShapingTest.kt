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
import pl.lukaszpeciak.towarownik.product.TechnicalFact
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.ProductRef
import pl.lukaszpeciak.towarownik.product.provider.ProviderId
import pl.lukaszpeciak.towarownik.product.provider.ProviderPriceScope
import pl.lukaszpeciak.towarownik.product.provider.ProviderProduct

class AdvisorProductShapingTest {
    @Test
    fun `oversized provider facts are bounded for advisor contract`() {
        val shaped = oversizedProduct().toAdvisorVerifiedProduct()

        assertEquals("580", shaped.productId)
        assertEquals("580", shaped.obik)
        assertEquals("MBN116E/HAG", shaped.articleNumber)
        assertEquals("Wyłącznik nadprądowy B16", shaped.name)
        assertEquals("Hager", shaped.brand)
        assertEquals(362, shaped.stock)
        assertEquals(10113, shaped.centralStock)
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

        assertTrue(\n            shaped.technicalFacts[0].label.startsWith(\n                "Fakt 1 bardzo długa etykieta",\n            ),\n        )
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

        val shaped = source.toAdvisorVerifiedProduct()

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
        assertEquals(source.grossPrice, shaped.price)
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
                val shaped = oversizedProduct().toAdvisorVerifiedProduct()

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
