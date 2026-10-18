package pl.lukaszpeciak.towarownik

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import pl.lukaszpeciak.towarownik.diagnostics.*

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
}
