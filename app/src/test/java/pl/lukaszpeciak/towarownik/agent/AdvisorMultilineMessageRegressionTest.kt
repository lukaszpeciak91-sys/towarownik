package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.attachment.AdvisorAttachment
import pl.lukaszpeciak.towarownik.attachment.AttachmentType
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult

/** R3 test-only RED: actual Controller START/MESSAGE dispatch, no live LLM/HTTP. */
class AdvisorMultilineMessageRegressionTest {
    private val offer = """
w ofercie wpisałem poniższe czy się zgadza?
60MGP-1zp — 50 szt.
60MGPB-1zp — 8 szt.
60MWP-1 — 6 szt.
60MWP-2 — 2 szt.
60MWP-3 — 6 szt.
60MWP-6 — 1 szt.
60MWP-4 — 1 szt.
60MRTO-3 — 11 szt.
60MRTO-2 — 18 szt.
60MRTO-1 — 13 szt.""".trimIndent()

    private fun answer(id: String = "answer") =
        AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer(id, "OK", emptyList()))

    private fun controller(
        start: suspend (String) -> AdvisorProxyCallResult = { answer() },
        message: suspend (String, String) -> AdvisorProxyCallResult = { _, _ -> answer() },
        attachmentStart: suspend (String, List<AdvisorAttachment>) -> AdvisorProxyCallResult =
            { _, _ -> answer() },
        attachmentMessage: suspend (String, String, List<AdvisorAttachment>) -> AdvisorProxyCallResult =
            { _, _, _ -> answer() },
    ) = AdvisorController(
        isConfigured = { true },
        startAgent = { userText, _, _ -> start(userText) },
        messageAgent = { previousResponseId, userText, _, _ -> message(previousResponseId, userText) },
        continueAgent = { _, _, _, _, _, _ -> error("No continuation expected") },
        executeObiTool = { error("No local tool expected") },
        executeProviderTool = { error("No local tool expected") },
        branchDirectory = { ProviderBranchResult.Available(emptyList()) },
        startAgentWithAttachments = { userText, _, _, parts -> attachmentStart(userText, parts) },
        messageAgentWithAttachments = { responseId, userText, _, _, parts ->
            attachmentMessage(responseId, userText, parts)
        },
    )

    @Test fun r3ExactKarlikOfferElevenLogicalLinesReachModelFacingStartUnchanged() = runBlocking {
        var delivered = ""
        val advisor = controller(start = { text -> delivered = text; answer("first") })
        val result = advisor.runTurn(offer, null, "205", "kwant-pl") {}
        assertTrue(result is AdvisorUiState.Success)
        assertEquals(11, offer.lines().size)
        assertEquals(offer, delivered)
        assertEquals(offer.lines(), delivered.lines())
    }

    @Test fun r3NormalizeCrlfAndCrToLfWithoutFlattening() = runBlocking {
        val expected = "Kod\tIlość\nMBN116E\t3 szt.\n\nUwagi: 30×70 cm"
        for (variant in listOf(expected, expected.replace("\n", "\r\n"), expected.replace("\n", "\r"))) {
            var delivered = ""
            controller(start = { delivered = it; answer() })
                .runTurn(variant, null, "205", "kwant-pl") {}
            assertEquals(variant.replace("\r\n", "\n").replace('\r', '\n'), delivered)
        }
    }

    @Test fun r3UnicodeIndentationBulletsBlankLinesAndFinalNewlineRetained() = runBlocking {
        val structured = "Zażółć gęślą jaźń: 30×70 cm\n  • 0001234567890\t— 2 szt.\n\n\t- Śruby × 4\n"
        var delivered = ""
        controller(start = { delivered = it; answer() })
            .runTurn(structured, null, "205", "kwant-pl") {}
        assertEquals(structured, delivered)
    }

    @Test fun r3PreviousResponseMessagePreservesNewStructureWithoutDuplicatingRows() = runBlocking {
        var observedPrevious = ""
        var observedText = ""
        val advisor = controller(
            start = { answer("previous") },
            message = { previous, text ->
                observedPrevious = previous
                observedText = text
                answer("after")
            },
        )
        val first = advisor.runTurn("Pierwszy kontakt", null, "205", "kwant-pl") {} as AdvisorUiState.Success
        val second = advisor.runTurn(offer, first.responseId, "205", "kwant-pl") {} as AdvisorUiState.Success
        assertEquals("previous", observedPrevious)
        assertEquals("after", second.responseId)
        assertEquals(offer, observedText)
    }

    @Test fun r3V5StartAndMessageCarryStructuredUserTextIndependentlyOfAttachments() = runBlocking {
        val part = AdvisorAttachment(
            type = AttachmentType.PDF, mimeType = "application/pdf",
            displayName = "oferta.pdf", localId = "a".repeat(32),
            byteSize = 5, createdAt = 0,
        )
        val sent = mutableListOf<String>()
        val advisor = controller(
            attachmentStart = { text, parts ->
                assertEquals(listOf(part), parts)
                sent += text
                answer("with-attachment")
            },
            attachmentMessage = { previous, text, parts ->
                assertEquals("with-attachment", previous)
                assertEquals(listOf(part), parts)
                sent += text
                answer("after-attachment")
            },
        )
        val first = advisor.runTurn(offer, null, "205", "kwant-pl", attachments = listOf(part)) {}
            as AdvisorUiState.Success
        advisor.runTurn(offer, first.responseId, "205", "kwant-pl", attachments = listOf(part)) {}
        assertEquals(listOf(offer, offer), sent)
    }

    @Test fun r3ExistingSingleLineAndAttachmentOnlyInputsRemainValid() = runBlocking {
        var message = ""
        val advisor = controller(start = { message = it; answer() })
        advisor.runTurn("Sprawdź MBN116E w Tarnowie", null, "205", "kwant-pl") {}
        assertEquals("Sprawdź MBN116E w Tarnowie", message)
        val attachment = AdvisorAttachment(
            type = AttachmentType.PDF, mimeType = "application/pdf", displayName = "lista.pdf",
            localId = "b".repeat(32), byteSize = 5, createdAt = 0,
        )
        val valid = advisor.runTurn("", null, "205", "kwant-pl", attachments = listOf(attachment)) {}
        assertTrue(valid is AdvisorUiState.Success)
    }
}
