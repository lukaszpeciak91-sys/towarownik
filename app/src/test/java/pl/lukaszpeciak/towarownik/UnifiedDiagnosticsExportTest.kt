package pl.lukaszpeciak.towarownik

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import pl.lukaszpeciak.towarownik.diagnostics.*
import pl.lukaszpeciak.towarownik.conversation.PersistedMessage

class UnifiedDiagnosticsExportTest {
    @Test fun boundedEventsDoNotLeakCallerStringsOrSecrets() {
        var time = 1000L
        val recorder = SafeDiagnosticRecorder(maxEvents = 2, clock = { time++ })
        recorder.record(SafeDiagnosticEvent(
            DiagnosticSource.ADVISOR, DiagnosticStage.TURN_START,
            providerId = "obi-pl", branchId = "075",
            turnId = "11111111-1111-4111-8111-111111111111",
        ))
        recorder.record(SafeDiagnosticEvent(
            DiagnosticSource.PROXY, DiagnosticStage.PROXY_FAILURE,
            providerId = "SECRET_PROMPT_CANARY", branchId = "TOKEN_COOKIE_CANARY",
            traceId = "secret-token", endpoint = "https://private.invalid/api?token=SECRET",
            failureKind = "SECRET_EXCEPTION_CANARY", failureClass = "SECRET_STACKTRACE",
            tool = "SECRET_TOOL", outcome = "SECRET_PRODUCT_CANARY",
            httpStatus = 502, elapsedMillis = 123,
        ))
        val output = recorder.report()
        assertTrue(output.contains("source=PROXY stage=PROXY_FAILURE"))
        assertTrue(output.contains("http=502"))
        assertTrue(output.contains("durationMs=123"))
        assertFalse(output.contains("SECRET"))
        assertFalse(output.contains("private.invalid"))
        assertFalse(output.contains("secret-token"))
        recorder.record(SafeDiagnosticEvent(DiagnosticSource.ADVISOR, DiagnosticStage.TURN_SUCCESS))
        assertFalse(recorder.report().contains("11111111-1111-4111-8111-111111111111"))
        assertTrue(recorder.report().contains("retention=last 2/2"))
        recorder.clear()
        assertFalse(recorder.hasEvents())
        assertTrue(recorder.report().contains("No captured events"))
    }

    @Test fun validProviderToolAndTraceArePreservedWithoutProductIdentifiers() {
        val recorder = SafeDiagnosticRecorder(clock = { 100L })
        recorder.record(SafeDiagnosticEvent(
            DiagnosticSource.ADVISOR, DiagnosticStage.TOOL_RESULT,
            providerId = "kwant-pl", branchId = "205",
            tool = "find_product_locations", outcome = "unavailable",
            traceId = "11111111-1111-4111-8111-111111111111",
        ))
        val report = recorder.report()
        assertTrue(report.contains("provider=kwant-pl branch=205"))
        assertTrue(report.contains("tool=find_product_locations outcome=unavailable"))
        assertTrue(report.contains("trace=11111111-1111-4111-8111-111111111111"))
        assertFalse(report.contains("product_id="))
        assertFalse(report.contains("attachment="))
    }

    @Test fun reportIncludesSafeSessionEvidenceOnlyWhenExplicitlyEnabled() {
        val diagnostic = "SAFE_EVENT_CANARY"
        val metadata = ProblemReportTechnicalMetadata(
            1L, "2026-10-18T21:00:00+02:00", "Taksula", "0.1.28", 29,
            "13", 33, "Synthetic", "Device", "pl",
        )
        val req = ProblemReportRequest(
            ProblemReportType.GENERAL, ProblemReportCategory.APP_PROBLEM,
            "Test report", false, true,
        )
        val evidence = ProblemReportEvidence(null, null, emptyList())
        val included = ProblemReportFormatter.format(req, evidence, metadata, null, diagnostic)
        assertTrue(included.contains("APPLICATION DIAGNOSTICS"))
        assertTrue(included.contains(diagnostic))
        val excluded = ProblemReportFormatter.format(req.copy(includeObiDiagnostics = false), evidence, metadata, null, diagnostic)
        assertFalse(excluded.contains(diagnostic))
        assertTrue(excluded.contains("Diagnostics: not included"))
        assertFalse(REPORT_INCLUDE_CONVERSATION_DEFAULT)
        assertTrue(REPORT_INCLUDE_OBI_DIAGNOSTICS_DEFAULT)
    }


    @Test fun preparedSafReportMustSurviveAnotherExportAndSameSecondFilename() {
        val root = Files.createTempDirectory("taksula-pending-recreate-red").toFile()
        try {
            val store = ProblemReportFileStore(root)
            val first = store.write("PENDING ORIGINAL – zażółć", 1_000L)
            val pendingNameFromSavedUiState = first.name
            val unrelated = store.write("NEW REPORT", 1_000L)

            // Simulates a recreated screen referring back to the pending file by name.
            assertTrue("Prepared report deleted before SAF callback", first.isFile)
            assertTrue("Same-second exports must not overwrite the pending report", unrelated.name != pendingNameFromSavedUiState)
            assertEquals("PENDING ORIGINAL – zażółć", first.readText())
            assertEquals("NEW REPORT", unrelated.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun savedSafBasenameRestoresPrivateSnapshotAfterScreenRecreation() {
        val root = Files.createTempDirectory("taksula-saf-recreated").toFile()
        try {
            val originalStore = ProblemReportFileStore(root)
            val pending = originalStore.write(
                "OBI and KWANT safe evidence – UTF-8", 1_000L,
                prefix = "taksula-diagnostics",
            )
            val savedUiStateBasename = pending.name // rememberSaveable holds only this.
            // After Activity recreation we only have the saved basename, not File or text.
            val recreatedStore = ProblemReportFileStore(root)
            val restored = recreatedStore.pending(savedUiStateBasename)
            assertNotNull(restored)
            val destination = ByteArrayOutputStream()
            copyProblemReportToStream(requireNotNull(restored), destination)
            assertArrayEquals(pending.readBytes(), destination.toByteArray())
            assertTrue(pending.name.startsWith("taksula-diagnostics-"))
            assertNull(recreatedStore.pending("../${pending.name}"))
            assertNull(recreatedStore.pending("/tmp/${pending.name}"))
            assertNull(recreatedStore.pending("taksula-report-untrusted.txt"))
            restored.delete() // SAF completion/cancellation clears only this snapshot.
            assertNull(recreatedStore.pending(savedUiStateBasename))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun exportedFileUsesIdenticalUtf8BytesAsSharedReport() {
        val root = Files.createTempDirectory("taksula-save-test").toFile()
        try {
            val file = ProblemReportFileStore(root).write("Taksula — zażółć gęślą jaźń\nKWANT 205", 1L)
            val destination = ByteArrayOutputStream()
            copyProblemReportToStream(file, destination)
            assertArrayEquals(file.readBytes(), destination.toByteArray())
        } finally {
            root.deleteRecursively()
        }
    }
    @Test fun targetedReportsNeverMixOtherConversationLaterResponseOrFailedRetry() {
        val r = SafeDiagnosticRecorder()
        val a = "11111111-1111-4111-8111-111111111111"
        val b = "22222222-2222-4222-8222-222222222222"
        val c = "33333333-3333-4333-8333-333333333333"
        val d = "44444444-4444-4444-8444-444444444444"
        r.record(SafeDiagnosticEvent(DiagnosticSource.ADVISOR, DiagnosticStage.TURN_START,
            providerId="obi-pl",branchId="003",turnId=a,conversationId=10))
        r.record(SafeDiagnosticEvent(DiagnosticSource.PROXY, DiagnosticStage.PROXY_RESPONSE,
            endpoint="start",traceId=a,turnId=a,conversationId=10,httpStatus=200))
        r.linkAssistantMessage(10,a,100)
        r.record(SafeDiagnosticEvent(DiagnosticSource.ADVISOR, DiagnosticStage.TURN_START,
            providerId="kwant-pl",branchId="205",turnId=b,conversationId=20))
        r.record(SafeDiagnosticEvent(DiagnosticSource.PROXY, DiagnosticStage.PROXY_FAILURE,
            traceId=b,turnId=b,conversationId=20,endpoint="message",httpStatus=502))
        r.record(SafeDiagnosticEvent(DiagnosticSource.ADVISOR, DiagnosticStage.TURN_SUCCESS,
            providerId="obi-pl",branchId="075",turnId=c,conversationId=10))
        r.linkAssistantMessage(10,c,101)
        r.record(SafeDiagnosticEvent(DiagnosticSource.ADVISOR, DiagnosticStage.TURN_FAILURE,
            providerId="kwant-pl",branchId="204",turnId=d,conversationId=20))
        val older = r.reportForMessage(10,100)
        assertTrue(older.contains("branch=003"))
        for (secret in listOf(b,c,d)) assertFalse(older.contains(secret))
        assertFalse(older.contains("branch=205"))
        assertFalse(older.contains("http=502"))
        val failed = r.reportForTurn(20,b)
        assertTrue(failed.contains("http=502"))
        assertFalse(failed.contains(a))
        assertFalse(failed.contains(d))
        val later = r.reportForMessage(10,101)
        assertTrue(later.contains("branch=075"))
        assertFalse(later.contains("branch=003"))
        r.clearConversation(10)
        assertTrue(r.reportForMessage(10,100).contains("unavailable"))
        assertTrue(r.reportForTurn(20,b).contains("http=502"))
        assertTrue(r.report().contains("branch=205"))
    }

    @Test fun missingAndEvictedScopedEvidenceNeverBorrowCurrentGlobalHistory() {
        val r = SafeDiagnosticRecorder(maxEvents=2)
        val a="11111111-1111-4111-8111-111111111111"
        val b="22222222-2222-4222-8222-222222222222"
        r.record(SafeDiagnosticEvent(DiagnosticSource.ADVISOR,DiagnosticStage.TURN_SUCCESS,turnId=a,conversationId=10))
        r.linkAssistantMessage(10,a,100)
        r.record(SafeDiagnosticEvent(DiagnosticSource.ADVISOR,DiagnosticStage.TURN_FAILURE,turnId=b,conversationId=20))
        r.record(SafeDiagnosticEvent(DiagnosticSource.ADVISOR,DiagnosticStage.TURN_FAILURE,turnId=b,conversationId=20))
        assertTrue(r.reportForMessage(10,100).contains("unavailable"))
        assertFalse(r.reportForMessage(10,100).contains(b))
        assertTrue(r.reportForTurn(20,b).contains(b))
        assertTrue(r.reportForTurn(20,a).contains("unavailable"))
    }

    @Test fun startupCleanupOnlyExpiresOrphansAndPreservesActivePendingReports() {
        val root=Files.createTempDirectory("taksula-cleanup")
        try {
            val store=ProblemReportFileStore(root.toFile())
            val pending=store.write("ACTIVE",1000L)
            val sibling=store.write("OTHER ACTIVE",1000L)
            val unrelated=root.resolve("reports/unrelated.txt").toFile().apply { writeText("DO NOT DELETE") }
            val old=store.write("ORPHAN",1000L)
            val now=System.currentTimeMillis()
            assertTrue(old.setLastModified(now-10L*24*60*60*1000))
            val count=store.cleanupExpired(now, setOf(pending.name))
            assertEquals(1,count)
            assertFalse(old.exists())
            assertTrue(pending.exists())
            assertTrue(sibling.exists())
            assertTrue(unrelated.exists())
            assertEquals("ACTIVE",store.pending(pending.name)?.readText())
        } finally {root.toFile().deleteRecursively()}
    }

    @Test fun actualReportSelectionUsesPersistedAssistantOrFailedTurnNeverGlobalByDefault() {
        val r = SafeDiagnosticRecorder()
        val first = "11111111-1111-4111-8111-111111111111"
        val unrelated = "22222222-2222-4222-8222-222222222222"
        r.record(SafeDiagnosticEvent(DiagnosticSource.ADVISOR,DiagnosticStage.TURN_SUCCESS,
            conversationId=10,turnId=first,providerId="obi-pl",branchId="003"))
        r.linkAssistantMessage(10,first,100)
        r.record(SafeDiagnosticEvent(DiagnosticSource.ADVISOR,DiagnosticStage.TURN_FAILURE,
            conversationId=20,turnId=unrelated,providerId="kwant-pl",branchId="205"))
        val response=ProblemReportRequest(
            ProblemReportType.ASSISTANT_RESPONSE,ProblemReportCategory.INCORRECT_FABRICATED,
            "reported answer",false,true,conversationId=10,reportedMessageId=100,
        )
        val evidence=ProblemReportEvidence(10,PersistedMessage(100,"ASSISTANT","answer",1L,emptyList()),emptyList())
        val scoped=requireNotNull(safeDiagnosticReportForRequest(response,evidence,r))
        assertTrue(scoped.contains("branch=003"))
        assertFalse(scoped.contains("branch=205"))
        assertFalse(scoped.contains(unrelated))
        val failed=ProblemReportRequest(
            ProblemReportType.ADVISOR_FAILURE,ProblemReportCategory.ADVISOR_REQUEST_FAILED,
            "",false,true,conversationId=20,failedTurnId=unrelated,
        )
        val failedText=requireNotNull(safeDiagnosticReportForRequest(failed,
            ProblemReportEvidence(20,null,emptyList()),r))
        assertTrue(failedText.contains("branch=205"))
        assertFalse(failedText.contains("branch=003"))
        val general=ProblemReportRequest(
            ProblemReportType.GENERAL,ProblemReportCategory.APP_PROBLEM,"issue",false,true,
        )
        val session=requireNotNull(safeDiagnosticReportForRequest(general,
            ProblemReportEvidence(null,null,emptyList()),r))
        assertTrue(session.contains("session-wide"))
        assertTrue(session.contains("branch=003"))
        assertTrue(session.contains("branch=205"))
        assertNull(safeDiagnosticReportForRequest(response.copy(includeObiDiagnostics=false),evidence,r))
        r.clearConversation(10)
        assertTrue(safeDiagnosticReportForRequest(response,evidence,r)!!.contains("unavailable"))
    }


}
