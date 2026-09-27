package pl.lukaszpeciak.towarownik

import java.math.BigDecimal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.conversation.MESSAGE_ROLE_ASSISTANT
import pl.lukaszpeciak.towarownik.conversation.MESSAGE_ROLE_USER
import pl.lukaszpeciak.towarownik.conversation.PersistedMessage
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot

class ProblemReportFormatterTest {
    private val metadata = ProblemReportTechnicalMetadata(
        createdAtMillis = 1_000L,
        createdAtWithOffset = "2026-09-27T12:00:00+02:00",
        appName = "Taksula",
        versionName = "0.1.10",
        versionCode = 11,
        androidVersion = "13",
        apiLevel = 33,
        manufacturer = "Synthetic",
        deviceModel = "Device",
        uiLanguageTag = "pl",
    )

    @Test
    fun `assistant report without conversation contains only reported response context`() {
        val reported = assistant(
            id = 2,
            text = "REPORTED ANSWER",
            products = listOf(
                product(
                    obik = "1234567",
                    stock = 0,
                    price = BigDecimal("12.30"),
                ),
                product(
                    obik = "7654321",
                    stock = null,
                    price = null,
                ),
            ),
        )
        val request = assistantRequest(includeConversation = false)

        val report = ProblemReportFormatter.format(
            request = request,
            evidence = ProblemReportEvidence(
                conversationId = 44,
                reportedMessage = reported,
                conversationMessages = emptyList(),
            ),
            metadata = metadata,
            safeObiDiagnostics = null,
        )

        assertTrue(report.contains("REPORTED ANSWER"))
        assertFalse(report.contains("EARLIER USER"))
        assertFalse(report.contains("LATER USER"))
        assertTrue(report.contains("storeNumber=075\n  stock=0"))
        assertTrue(report.contains("storeNumber=075\n  stock=UNKNOWN"))
        assertTrue(report.contains("grossPrice=UNKNOWN"))
        assertTrue(report.contains("productUrl=https://example.invalid/p/1234567"))
        assertTrue(report.contains("verifiedAt=1970-01-01T00:00:02Z"))
        assertTrue(report.contains("OBI diagnostics: not included"))
    }

    @Test
    fun `assistant report with conversation stops exactly at reported response`() {
        val first = user(1, "FIRST USER")
        val earlierAssistant = assistant(
            id = 2,
            text = "EARLIER ASSISTANT",
            products = listOf(product(obik = "1111111", stock = 5)),
        )
        val second = user(3, "SECOND USER")
        val reported = assistant(4, "TARGET ANSWER")
        val request = assistantRequest(includeConversation = true)

        val report = ProblemReportFormatter.format(
            request = request,
            evidence = ProblemReportEvidence(
                conversationId = 9,
                reportedMessage = reported,
                conversationMessages = listOf(
                    first,
                    earlierAssistant,
                    second,
                    reported,
                ),
            ),
            metadata = metadata,
            safeObiDiagnostics = null,
        )

        assertTrue(report.contains("FIRST USER"))
        assertTrue(report.contains("EARLIER ASSISTANT"))
        assertTrue(report.contains("SECOND USER"))
        assertTrue(report.contains("TARGET ANSWER"))
        assertTrue(report.contains("obik=1111111"))
        assertFalse(report.contains("MUST NOT LEAK"))
    }

    @Test
    fun `general report without conversation contains no transcript`() {
        val request = ProblemReportRequest(
            type = ProblemReportType.GENERAL,
            category = ProblemReportCategory.APP_PROBLEM,
            description = "Button failed",
            includeConversation = false,
            includeObiDiagnostics = false,
        )
        val report = ProblemReportFormatter.format(
            request = request,
            evidence = ProblemReportEvidence(
                conversationId = null,
                reportedMessage = null,
                conversationMessages = emptyList(),
            ),
            metadata = metadata,
            safeObiDiagnostics = null,
        )

        assertTrue(report.contains("Button failed"))
        assertFalse(report.contains("CONVERSATION CONTEXT"))
        assertTrue(report.contains("Report type: GENERAL"))
    }

    @Test
    fun `general report with conversation includes persisted messages but no draft field`() {
        val request = ProblemReportRequest(
            type = ProblemReportType.GENERAL,
            category = ProblemReportCategory.GENERAL_OTHER,
            description = "General issue",
            includeConversation = true,
            includeObiDiagnostics = true,
            conversationId = 4,
        )
        val report = ProblemReportFormatter.format(
            request = request,
            evidence = ProblemReportEvidence(
                conversationId = 4,
                reportedMessage = null,
                conversationMessages = listOf(
                    user(1, "PERSISTED USER"),
                    assistant(2, "PERSISTED ASSISTANT"),
                ),
            ),
            metadata = metadata,
            safeObiDiagnostics = "SAFE DIAGNOSTICS",
        )

        assertTrue(report.contains("PERSISTED USER"))
        assertTrue(report.contains("PERSISTED ASSISTANT"))
        assertTrue(report.contains("SAFE DIAGNOSTICS"))
        assertFalse(report.contains("draft="))
    }

    @Test
    fun `formatter ignores diagnostics unless request explicitly opts in`() {
        val report = ProblemReportFormatter.format(
            request = ProblemReportRequest(
                type = ProblemReportType.GENERAL,
                category = ProblemReportCategory.APP_PROBLEM,
                description = "Problem",
                includeConversation = false,
                includeObiDiagnostics = false,
            ),
            evidence = ProblemReportEvidence(
                conversationId = null,
                reportedMessage = null,
                conversationMessages = emptyList(),
            ),
            metadata = metadata,
            safeObiDiagnostics = "SAFE DIAGNOSTICS MUST NOT LEAK",
        )

        assertFalse(report.contains("SAFE DIAGNOSTICS MUST NOT LEAK"))
        assertTrue(report.contains("OBI diagnostics: not included"))
    }

    @Test
    fun `technical metadata is present while forbidden internal fields are absent`() {
        val report = ProblemReportFormatter.format(
            request = assistantRequest(includeConversation = false),
            evidence = ProblemReportEvidence(
                conversationId = 55,
                reportedMessage = assistant(77, "ANSWER"),
                conversationMessages = emptyList(),
            ),
            metadata = metadata,
            safeObiDiagnostics = null,
        )

        assertTrue(report.startsWith("TAKSULA PROBLEM REPORT"))
        assertTrue(report.contains("App: Taksula"))
        assertTrue(report.contains("Created: 2026-09-27T12:00:00+02:00"))
        assertTrue(report.contains("Version: 0.1.10 (11)"))
        assertTrue(report.contains("Android: 13 API 33"))
        assertTrue(report.contains("Device: Synthetic Device"))
        assertTrue(report.contains("UI language: pl"))
        assertTrue(report.contains("Conversation ID: 55"))
        assertTrue(report.contains("Reported message ID: 77"))

        listOf(
            "lastResponseId",
            "previous_response_id",
            "tool_call",
            "TOWAROWNIK_APP_TOKEN",
            "Authorization:",
            "OPENAI_API_KEY",
            "reasoning",
            "__NUXT_DATA__",
        ).forEach { forbidden ->
            assertFalse(report.contains(forbidden))
        }
    }

    private fun assistantRequest(
        includeConversation: Boolean,
    ) = ProblemReportRequest(
        type = ProblemReportType.ASSISTANT_RESPONSE,
        category = ProblemReportCategory.INCORRECT_FABRICATED,
        description = "",
        includeConversation = includeConversation,
        includeObiDiagnostics = false,
        conversationId = 44,
        reportedMessageId = 2,
    )

    private fun user(
        id: Long,
        text: String,
    ) = PersistedMessage(
        id = id,
        role = MESSAGE_ROLE_USER,
        text = text,
        createdAt = id * 1_000,
        products = emptyList(),
    )

    private fun assistant(
        id: Long,
        text: String,
        products: List<VerifiedProductSnapshot> = emptyList(),
    ) = PersistedMessage(
        id = id,
        role = MESSAGE_ROLE_ASSISTANT,
        text = text,
        createdAt = id * 1_000,
        products = products,
    )

    private fun product(
        obik: String,
        stock: Int?,
        price: BigDecimal? = BigDecimal("9.99"),
    ) = VerifiedProductSnapshot(
        obik = obik,
        name = "Product $obik",
        stock = stock,
        grossPrice = price,
        productUrl = "https://example.invalid/p/$obik",
        verifiedAt = 2_000L,
    )
}
