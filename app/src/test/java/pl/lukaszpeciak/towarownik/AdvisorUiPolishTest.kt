package pl.lukaszpeciak.towarownik

import java.io.File
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import pl.lukaszpeciak.towarownik.conversation.PersistedWebSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import pl.lukaszpeciak.towarownik.product.provider.KWANT_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import org.junit.Test
import pl.lukaszpeciak.towarownik.attachment.AdvisorAttachment
import pl.lukaszpeciak.towarownik.attachment.AttachmentType

class AdvisorUiPolishTest {
    @Test
    fun chatStateRoundTripsThreeOrderedAttachments() {
        fun sample(id: String, name: String) = AdvisorAttachment(
            type = AttachmentType.PDF,
            displayName = name, mimeType = "application/pdf",
            localId = id.repeat(32), byteSize = 42,
            createdAt = 12,
        )
        val attachments = listOf(
            sample("a", "one.pdf"), sample("b", "two.pdf"), sample("c", "three.pdf"),
        )
        val state = AdvisorCaseUiState(messages = listOf(
            AdvisorChatMessage(
                role = ChatMessageRole.USER,
                text = "Compare",
                createdAt = 123,
                attachments = attachments,
            ),
            AdvisorChatMessage(
                role = ChatMessageRole.ASSISTANT, text = "Done", createdAt = 124,
            ),
        ))
        val restored = restoreAdvisorCase(saveAdvisorCase(state))
        assertEquals(
            attachments.map { it.localId },
            restored.messages.first().attachments.map { it.localId },
        )
    }

    @Test
    fun `local tool progress label follows active provider`() {
        assertEquals(
            R.string.advisor_progress_checking_obi,
            advisorLocalToolProgressRes(OBI_PROVIDER_ID),
        )
        assertEquals(
            R.string.advisor_progress_checking_kwant,
            advisorLocalToolProgressRes(KWANT_PROVIDER_ID),
        )
    }

    @Test
    fun `assistant messages use chat specific product cards only`() {
        val source = mainActivitySource()
        val bubble = source
            .substringAfter("private fun AdvisorMessageBubble(")
            .substringBefore("@Composable\nprivate fun AdvisorAnswerText(")

        assertTrue(
            bubble.contains("AdvisorVerifiedProductCard(product)"),
        )
        assertFalse(
            bubble.lineSequence().any {
                it.trim() == "VerifiedProductCard(product)"
            },
        )
    }

    @Test
    fun `manual search keeps approved product card and result thumbnail layout`() {
        val source = mainActivitySource()
        val manualScreen = source
            .substringAfter("private fun ManualObiSearchScreen(")
            .substringBefore("@Composable\nprivate fun ManualSearchTopBar(")
        val manualResults = source
            .substringAfter("private fun ManualSearchResults(")
            .substringBefore("@Composable\nprivate fun ManualVerifiedProductLink(")

        assertTrue(
            manualScreen.contains("VerifiedProductCard(state.item)"),
        )
        assertTrue(
            manualResults.contains("VerifiedProductThumbnail("),
        )
        assertTrue(
            manualResults.contains("modifier = Modifier.size(84.dp)"),
        )
        assertFalse(
            manualResults.contains("AdvisorVerifiedProductCard"),
        )
    }

    @Test
    fun `chat product card keeps title full width and thumbnail beside details`() {
        val source = verifiedProductCardSource()
        val chatCard = source
            .substringAfter("internal fun AdvisorVerifiedProductCard(")
            .substringBefore("@Composable\ninternal fun VerifiedProductCard(")

        val titleIndex = chatCard.indexOf("text = product.name")
        val rowIndex = chatCard.indexOf("Row(")

        assertTrue(titleIndex >= 0)
        assertTrue(rowIndex > titleIndex)
        assertTrue(chatCard.contains("modifier = Modifier.weight(1f)"))
        assertTrue(chatCard.contains("modifier = Modifier.size(108.dp)"))
        assertTrue(chatCard.contains("modifier = Modifier.fillMaxWidth()"))
        assertTrue(
            source
                .substringAfter("internal fun VerifiedProductThumbnail(")
                .contains("contentScale = ContentScale.Fit"),
        )
    }

    @Test
    fun `working profile selector shows branch id name and optional address`() {
        val source = mainActivitySource()
        val selector = source
            .substringAfter("private fun WorkingProfileSelector(")
            .substringBefore("@Composable\nprivate fun AdvisorComposer(")

        assertTrue(
            selector.contains(
                "\"\${it.branchId.value} • \${it.name}\"",
            ),
        )
        assertTrue(
            selector.contains(
                "\"\${option.branchId.value} • \${option.name}\"",
            ),
        )
        assertTrue(selector.contains("option.address?.let"))
        assertFalse(
            selector.contains(
                "val branchLabel = branch?.name ?: workingProfile.branchId.value",
            ),
        )
    }

    @Test
    fun `cited answer text explicitly uses normal assistant foreground`() {
        val source = mainActivitySource()
        val answer = source
            .substringAfter("private fun AdvisorAnswerText(")
            .substringBefore("@Composable\nprivate fun AdvisorSearchActions(")

        assertTrue(
            answer.contains(
                "MaterialTheme.typography.bodyLarge.copy(",
            ),
        )
        assertTrue(
            answer.contains(
                "color = MaterialTheme.colorScheme.onSurface",
            ),
        )
    }

    @Test
    fun `assistant answer citation links remain attached to selectable text`() {
        val text = "Cena 19 zł i ilość 3 szt."
        val annotated = advisorAnswerWithCitationLinks(
            text = text,
            sources = listOf(
                PersistedWebSource(
                    title = "Cennik", url = "https://example.org/price",
                    startIndex = 0, endIndex = 10,
                ),
                PersistedWebSource(
                    title = "Ilość", url = "https://example.org/quantity",
                    startIndex = 13, endIndex = text.length,
                ),
            ),
            linkColor = Color.Blue,
        )
        assertEquals("Cena 19 zł [1] i ilość 3 szt. [2]", annotated.text)
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(2, links.size)
        assertEquals(
            listOf("https://example.org/price", "https://example.org/quantity"),
            links.map { (it.item as LinkAnnotation.Url).url },
        )
        assertEquals(" [1]", annotated.text.substring(links[0].start, links[0].end))
        assertEquals(" [2]", annotated.text.substring(links[1].start, links[1].end))
    }

    @Test
    fun `uncited or unmappable assistant text stays intact without fake clickable links`() {
        val source = PersistedWebSource(
            title = "Unknown span", url = "https://example.org/unknown",
            startIndex = null, endIndex = null,
        )
        for (sources in listOf(emptyList(), listOf(source))) {
            val annotated = advisorAnswerWithCitationLinks(
                text = "Normal answer to copy", sources = sources, linkColor = Color.Blue,
            )
            assertEquals("Normal answer to copy", annotated.text)
            assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
        }
        val answerUi = mainActivitySource()
            .substringAfter("private fun AdvisorAnswerText(")
            .substringBefore("internal fun advisorAnswerWithCitationLinks(")
        assertTrue(answerUi.contains("SelectionContainer"))
        assertTrue(answerUi.contains("Text("))
        assertFalse(answerUi.contains("ClickableText("))
    }

    @Test
    fun `chat scroll follows only when the actual last row is near bottom`() {
        assertTrue(advisorListIsNearBottom(
            lastIndex = 9, totalItems = 10, lastItemBottom = 960,
            viewportEnd = 940, tolerance = 48,
        ))
        assertFalse(advisorListIsNearBottom(
            lastIndex = 4, totalItems = 10, lastItemBottom = 940,
            viewportEnd = 940, tolerance = 48,
        ))
        // Last row may be partially visible but very tall while reading its top.
        assertFalse(advisorListIsNearBottom(
            lastIndex = 9, totalItems = 10, lastItemBottom = 1800,
            viewportEnd = 940, tolerance = 48,
        ))
        assertFalse(advisorListIsNearBottom(
            lastIndex = -1, totalItems = 0, lastItemBottom = 0,
            viewportEnd = 0, tolerance = 48,
        ))
    }

    @Test
    fun `history reopen has a new scroll trigger and user scrolling disables tail following`() {
        val chat = mainActivitySource()
            .substringAfter("private fun AdvisorChatScreen(")
            .substringBefore("private fun AdvisorTopBar(")
        assertTrue(chat.contains("remember(conversationId, historyOpenSerial)"))
        assertTrue(chat.contains("snapshotFlow"))
        assertTrue(chat.contains("chatListState.isScrollInProgress"))
        assertTrue(chat.contains("if (nearBottom != null) followNewMessages = nearBottom"))
        assertTrue(chat.contains("if (visibleItemCount > 0 && followNewMessages)"))
        assertTrue(chat.contains("chatListState.scrollToItem(visibleItemCount - 1)"))
        val app = mainActivitySource()
        assertTrue(app.contains("historyOpenSerial++"))
        assertTrue(app.contains("historyOpenSerial = historyOpenSerial,"))
    }

    private fun mainActivitySource(): String =
        File(
            projectRoot(),
            "app/src/main/java/pl/lukaszpeciak/towarownik/MainActivity.kt",
        ).readText()

    private fun verifiedProductCardSource(): String =
        File(
            projectRoot(),
            "app/src/main/java/pl/lukaszpeciak/towarownik/VerifiedProductCard.kt",
        ).readText()

    private fun projectRoot(): File {
        var current = File(
            requireNotNull(System.getProperty("user.dir")),
        )
        repeat(3) {
            if (File(current, "app/src/main").isDirectory) {
                return current
            }
            current = current.parentFile ?: return@repeat
        }
        error("Project root not found")
    }
}
