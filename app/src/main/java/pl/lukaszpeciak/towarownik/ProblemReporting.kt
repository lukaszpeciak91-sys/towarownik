package pl.lukaszpeciak.towarownik

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lukaszpeciak.towarownik.conversation.ConversationRepository
import pl.lukaszpeciak.towarownik.conversation.MESSAGE_ROLE_ASSISTANT
import pl.lukaszpeciak.towarownik.conversation.PersistedMessage
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnosticRecorder
import pl.lukaszpeciak.towarownik.diagnostics.TaksulaDiagnostics

internal const val REPORT_RECIPIENT_EMAIL = "napahustudios@gmail.com"
internal const val REPORT_DESCRIPTION_MAX_CHARS = 2_000
internal const val REPORT_ADVISOR_FAILURE_DIAGNOSTIC_MAX_CHARS = 256
internal const val REPORT_CACHE_DIRECTORY = "reports"
internal const val REPORT_FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"
internal const val REPORT_INCLUDE_CONVERSATION_DEFAULT = false
internal const val REPORT_INCLUDE_OBI_DIAGNOSTICS_DEFAULT = true

internal enum class ProblemReportType {
    ASSISTANT_RESPONSE,
    ADVISOR_FAILURE,
    GENERAL,
}

internal enum class ProblemReportOrigin {
    ADVISOR,
    SETTINGS,
}

internal fun reportBackSurface(
    origin: ProblemReportOrigin,
): AppSurface =
    when (origin) {
        ProblemReportOrigin.ADVISOR -> AppSurface.ADVISOR
        ProblemReportOrigin.SETTINGS -> AppSurface.SETTINGS
    }

internal class ProblemReportTargetUnavailableException :
    IllegalStateException("report target unavailable")

internal enum class ProblemReportCategory(
    val reportType: ProblemReportType,
    val labelRes: Int,
) {
    INCORRECT_FABRICATED(
        ProblemReportType.ASSISTANT_RESPONSE,
        R.string.report_category_incorrect_fabricated,
    ),
    UNEXPECTED_BEHAVIOR(
        ProblemReportType.ASSISTANT_RESPONSE,
        R.string.report_category_unexpected_behavior,
    ),
    WRONG_PRODUCT_SELECTION(
        ProblemReportType.ASSISTANT_RESPONSE,
        R.string.report_category_wrong_product_selection,
    ),
    ASSISTANT_OTHER(
        ProblemReportType.ASSISTANT_RESPONSE,
        R.string.report_category_other,
    ),
    ADVISOR_REQUEST_FAILED(
        ProblemReportType.ADVISOR_FAILURE,
        R.string.report_category_app_problem,
    ),
    APP_PROBLEM(
        ProblemReportType.GENERAL,
        R.string.report_category_app_problem,
    ),
    OBI_SEARCH_PRODUCT_DATA(
        ProblemReportType.GENERAL,
        R.string.report_category_obi_search_product_data,
    ),
    GENERAL_OTHER(
        ProblemReportType.GENERAL,
        R.string.report_category_other,
    ),
}

internal fun reportCategories(
    type: ProblemReportType,
): List<ProblemReportCategory> =
    ProblemReportCategory.entries.filter { it.reportType == type }

internal fun advisorFailureDiagnosticForReport(
    type: ProblemReportType,
    currentFailureDiagnostic: String?,
): String? =
    when (type) {
        ProblemReportType.ASSISTANT_RESPONSE -> null
        ProblemReportType.ADVISOR_FAILURE -> currentFailureDiagnostic
        ProblemReportType.GENERAL -> currentFailureDiagnostic
    }

internal data class ProblemReportRequest(
    val type: ProblemReportType,
    val category: ProblemReportCategory,
    val description: String,
    val includeConversation: Boolean,
    val includeObiDiagnostics: Boolean,
    val conversationId: Long? = null,
    val reportedMessageId: Long? = null,
    val advisorFailureDiagnostic: String? = null,
    val failedTurnId: String? = null,
) {
    init {
        require(category.reportType == type)
        require(description.length <= REPORT_DESCRIPTION_MAX_CHARS)
        if (type == ProblemReportType.ADVISOR_FAILURE) {
            require(conversationId != null && conversationId > 0L)
            require(reportedMessageId == null)
            require(failedTurnId != null && Regex("^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$").matches(failedTurnId))
        } else {
            require(failedTurnId == null)
        }
        require(
            advisorFailureDiagnostic == null ||
                advisorFailureDiagnostic.length <=
                    REPORT_ADVISOR_FAILURE_DIAGNOSTIC_MAX_CHARS,
        )
    }
}

internal data class ProblemReportTechnicalMetadata(
    val createdAtMillis: Long,
    val createdAtWithOffset: String,
    val appName: String,
    val versionName: String,
    val versionCode: Int,
    val androidVersion: String,
    val apiLevel: Int,
    val manufacturer: String,
    val deviceModel: String,
    val uiLanguageTag: String,
)

internal data class ProblemReportEvidence(
    val conversationId: Long?,
    val reportedMessage: PersistedMessage?,
    val conversationMessages: List<PersistedMessage>,
    val kwantInventoryDiagnostics: String? = null,
)

internal enum class ProblemReportUiError {
    TARGET_UNAVAILABLE,
    DESCRIPTION_REQUIRED,
    GENERATION_FAILED,
    SHARE_UNAVAILABLE,
    SAVE_FAILED,
}

internal sealed interface ProblemReportResolution {
    data class Success(
        val evidence: ProblemReportEvidence,
    ) : ProblemReportResolution

    data object TargetUnavailable : ProblemReportResolution
}

internal class ProblemReportResolver(
    private val repository: ConversationRepository,
    private val responseEvidence: AdvisorResponseReportEvidenceStore? = null,
) {
    suspend fun resolve(
        request: ProblemReportRequest,
    ): ProblemReportResolution =
        when (request.type) {
            ProblemReportType.ASSISTANT_RESPONSE ->
                resolveAssistantResponse(request)

            ProblemReportType.GENERAL ->
                resolveGeneral(request)

            ProblemReportType.ADVISOR_FAILURE ->
                resolveFailedAdvisor(request)
        }

    private suspend fun resolveAssistantResponse(
        request: ProblemReportRequest,
    ): ProblemReportResolution {
        val conversationId =
            request.conversationId
                ?: return ProblemReportResolution.TargetUnavailable
        val messageId =
            request.reportedMessageId
                ?: return ProblemReportResolution.TargetUnavailable
        val conversation =
            repository.load(conversationId)
                ?: return ProblemReportResolution.TargetUnavailable
        val messageIndex = conversation.messages.indexOfFirst {
            it.id == messageId
        }
        if (messageIndex < 0) {
            return ProblemReportResolution.TargetUnavailable
        }

        val message = conversation.messages[messageIndex]
        if (message.role != MESSAGE_ROLE_ASSISTANT) {
            return ProblemReportResolution.TargetUnavailable
        }

        return ProblemReportResolution.Success(
            ProblemReportEvidence(
                conversationId = conversation.id,
                reportedMessage = message,
                kwantInventoryDiagnostics = responseEvidence?.get(conversation.id, message.id),
                conversationMessages = if (request.includeConversation) {
                    conversation.messages.take(messageIndex + 1)
                } else {
                    emptyList()
                },
            ),
        )
    }

    private suspend fun resolveFailedAdvisor(
        request: ProblemReportRequest,
    ): ProblemReportResolution {
        val id = request.conversationId
            ?: return ProblemReportResolution.TargetUnavailable
        val conversation = repository.load(id)
            ?: return ProblemReportResolution.TargetUnavailable
        return ProblemReportResolution.Success(
            ProblemReportEvidence(
                conversationId = conversation.id,
                reportedMessage = null,
                conversationMessages =
                    if (request.includeConversation) conversation.messages else emptyList(),
            ),
        )
    }

    private suspend fun resolveGeneral(
        request: ProblemReportRequest,
    ): ProblemReportResolution {
        if (!request.includeConversation) {
            return ProblemReportResolution.Success(
                ProblemReportEvidence(
                    conversationId = null,
                    reportedMessage = null,
                    conversationMessages = emptyList(),
                ),
            )
        }

        val conversationId =
            request.conversationId
                ?: return ProblemReportResolution.TargetUnavailable
        val conversation =
            repository.load(conversationId)
                ?: return ProblemReportResolution.TargetUnavailable

        return ProblemReportResolution.Success(
            ProblemReportEvidence(
                conversationId = conversation.id,
                reportedMessage = null,
                conversationMessages = conversation.messages,
            ),
        )
    }
}

internal fun createProblemReportMetadata(
    context: Context,
    createdAtMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): ProblemReportTechnicalMetadata {
    val language = appLanguageForTag(
        context.resources.configuration.locales[0]?.language,
    )
    val createdAt = OffsetDateTime.ofInstant(
        Instant.ofEpochMilli(createdAtMillis),
        zoneId,
    ).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    return ProblemReportTechnicalMetadata(
        createdAtMillis = createdAtMillis,
        createdAtWithOffset = createdAt,
        appName = context.getString(R.string.app_name),
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        androidVersion = Build.VERSION.RELEASE,
        apiLevel = Build.VERSION.SDK_INT,
        manufacturer = Build.MANUFACTURER,
        deviceModel = Build.MODEL,
        uiLanguageTag = language.languageTag,
    )
}

internal fun hasExistingSafeObiDiagnostics(
    recorder: ObiDiagnosticRecorder,
): Boolean =
    recorder.hasReportEvidence()

internal fun existingSafeObiDiagnostics(
    recorder: ObiDiagnosticRecorder,
    include: Boolean,
): String? =
    if (include && hasExistingSafeObiDiagnostics(recorder)) {
        recorder.report()
    } else {
        null
    }

internal object ProblemReportFormatter {
    fun format(
        request: ProblemReportRequest,
        evidence: ProblemReportEvidence,
        metadata: ProblemReportTechnicalMetadata,
        safeObiDiagnostics: String?,
        safeDiagnosticEvents: String? = null,
    ): String = buildString {
        appendLine("TAKSULA PROBLEM REPORT")
        appendLine()
        appendLine("Report type: ${request.type.name}")
        appendLine("Category: ${request.category.name}")
        appendLine("Created: ${metadata.createdAtWithOffset}")
        appendLine("App: ${metadata.appName}")
        appendLine("Version: ${metadata.versionName} (${metadata.versionCode})")
        appendLine("Android: ${metadata.androidVersion} API ${metadata.apiLevel}")
        appendLine("Device: ${metadata.manufacturer} ${metadata.deviceModel}")
        appendLine("UI language: ${metadata.uiLanguageTag}")
        request.advisorFailureDiagnostic?.let {
            appendLine("Advisor failure: $it")
        }

        if (request.type == ProblemReportType.ADVISOR_FAILURE) {
            appendLine("Failed conversation ID: ${request.conversationId}")
            appendLine("Failed turn ID: ${request.failedTurnId}")
            appendLine("Root cause: UNKNOWN (correlate safe trace with Worker logs)")
        }

        if (request.type == ProblemReportType.ASSISTANT_RESPONSE) {
            appendLine("Conversation ID: ${evidence.conversationId}")
            appendLine("Reported message ID: ${evidence.reportedMessage?.id}")
            appendLine(
                "Advisor trace: " +
                    (evidence.reportedMessage?.advisorTraceId
                        ?: "unavailable"),
            )
            appendLine(
                "Reported message timestamp: " +
                    evidence.reportedMessage
                        ?.createdAt
                        ?.let { Instant.ofEpochMilli(it).toString() },
            )
        }

        appendSection("USER DESCRIPTION") {
            appendLine(
                request.description
                    .takeIf { it.isNotBlank() }
                    ?: "(empty)",
            )
        }

        if (request.type == ProblemReportType.ASSISTANT_RESPONSE) {
            val reported = checkNotNull(evidence.reportedMessage)
            appendSection("REPORTED ASSISTANT RESPONSE") {
                appendLine(reported.text)
            }
            appendSection("VERIFIED PRODUCTS") {
                appendProducts(reported)
            }
            appendSection("KWANT INVENTORY DIAGNOSTICS") {
                appendLine(evidence.kwantInventoryDiagnostics ?: "unavailable (not captured in this app session or evicted)")
            }
        }

        if (request.includeConversation) {
            appendSection("CONVERSATION CONTEXT") {
                if (evidence.conversationMessages.isEmpty()) {
                    appendLine("(none)")
                } else {
                    evidence.conversationMessages.forEachIndexed { index, message ->
                        appendLine(
                            "--- message ${index + 1} " +
                                "${message.role} " +
                                "${Instant.ofEpochMilli(message.createdAt)} ---",
                        )
                        appendLine(message.text)
                        if (
                            message.role == MESSAGE_ROLE_ASSISTANT &&
                            message.products.isNotEmpty()
                        ) {
                            appendLine("Verified products:")
                            appendProducts(message, indent = "  ")
                        }
                        appendLine()
                    }
                }
            }
        }

        appendSection("APPLICATION DIAGNOSTICS") {
            appendLine(
                if (request.includeObiDiagnostics) {
                    safeDiagnosticEvents ?: "No captured safe application events in this session."
                } else "Diagnostics: not included"
            )
        }

        appendSection("OBI DIAGNOSTICS") {
            if (
                !request.includeObiDiagnostics ||
                safeObiDiagnostics == null
            ) {
                appendLine("OBI diagnostics: not included")
            } else {
                appendLine(safeObiDiagnostics.trimEnd())
            }
        }
    }

    private fun StringBuilder.appendSection(
        title: String,
        block: StringBuilder.() -> Unit,
    ) {
        appendLine()
        appendLine("--- $title ---")
        block()
    }

    private fun StringBuilder.appendProducts(
        message: PersistedMessage,
        indent: String = "",
    ) {
        if (message.products.isEmpty()) {
            appendLine("${indent}(none)")
            return
        }

        message.products.forEachIndexed { index, product ->
            appendLine("${indent}Product ${index + 1}:")
            appendLine("${indent}  name=${product.name}")
            appendLine("${indent}  obik=${product.obik}")
            appendLine("${indent}  storeNumber=${product.storeNumber}")
            appendLine(
                "${indent}  stock=" +
                    (product.stock?.toString() ?: "UNKNOWN"),
            )
            appendLine(
                "${indent}  grossPrice=" +
                    (product.grossPrice?.toPlainString() ?: "UNKNOWN"),
            )
            appendLine(
                "${indent}  verifiedAt=" +
                    Instant.ofEpochMilli(product.verifiedAt),
            )
            appendLine("${indent}  productUrl=${product.productUrl}")
        }
    }
}

internal class ProblemReportFileStore(
    private val cacheDir: File,
) {
    fun write(
        reportText: String,
        createdAtMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        prefix: String = "taksula-report",
    ): File {
        require(prefix in setOf("taksula-report", "taksula-diagnostics"))
        val directory = File(cacheDir, REPORT_CACHE_DIRECTORY)
        check(directory.exists() || directory.mkdirs()) {
            "Could not create report cache directory"
        }

        // The system SAF picker may recreate the Activity. Never delete a
        // freshly prepared report on another export (or overwrite same-second
        // reports). Prune only unrelated or expired private-cache files.
        val now = System.currentTimeMillis()
        directory.listFiles()?.forEach { file ->
            if (file.isFile && (!EXPORT_FILE_NAME.matches(file.name) ||
                    now - file.lastModified() > EXPORT_CACHE_MAX_AGE_MS)) {
                file.delete()
            }
        }
        val timestamp = Instant.ofEpochMilli(createdAtMillis)
            .atZone(zoneId)
            .format(FILE_NAME_TIMESTAMP)
        val reportFile = File.createTempFile("$prefix-$timestamp-", ".txt", directory)
        reportFile.writeText(text = reportText, charset = StandardCharsets.UTF_8)
        return reportFile
    }

    /** Restore only a generated file within the private reports directory.
     *  Persist the basename via rememberSaveable, never report contents or paths.
     */
    fun pending(name: String?): File? {
        if (name == null || !EXPORT_FILE_NAME.matches(name)) return null
        return File(File(cacheDir, REPORT_CACHE_DIRECTORY), name).takeIf { it.isFile }
    }

    private companion object {
        const val EXPORT_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
        val EXPORT_FILE_NAME = Regex(
            """^taksula-(?:report|diagnostics)-[0-9]{8}-[0-9]{6}-[A-Za-z0-9]+\.txt$""",
        )
        val FILE_NAME_TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

/** Android Storage Access Framework destination; no storage permission and no share chooser. */
internal fun saveProblemReportToUri(
    context: Context,
    destination: Uri,
    reportFile: File,
) {
    check(reportFile.isFile) { "Report file unavailable" }
    val output = context.contentResolver.openOutputStream(destination, "wt")
        ?: throw IOException("Cannot open selected destination")
    output.use { stream -> copyProblemReportToStream(reportFile, stream) }
}

/** Shared UTF-8 bytes for file export; independent of Android's chooser implementation. */
internal fun copyProblemReportToStream(file: File, output: OutputStream) {
    file.inputStream().use { it.copyTo(output) }
}

internal fun problemReportUri(
    context: Context,
    reportFile: File,
): Uri =
    FileProvider.getUriForFile(
        context,
        context.packageName + REPORT_FILE_PROVIDER_AUTHORITY_SUFFIX,
        reportFile,
    )

internal fun buildProblemReportSendIntent(
    reportUri: Uri,
    subject: String,
    body: String,
): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(
            Intent.EXTRA_EMAIL,
            arrayOf(REPORT_RECIPIENT_EMAIL),
        )
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
        putExtra(Intent.EXTRA_STREAM, reportUri)
        clipData = ClipData.newRawUri(
            "Taksula problem report",
            reportUri,
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

internal data class ProblemReportSharePayload(
    val intent: Intent,
    val reportFile: File,
)

internal suspend fun createProblemReportSharePayload(
    context: Context,
    repository: ConversationRepository,
    request: ProblemReportRequest,
    subject: String,
    body: String,
    recorder: ObiDiagnosticRecorder,
    responseEvidence: AdvisorResponseReportEvidenceStore? = null,
): Result<ProblemReportSharePayload> {
    if (
        request.type == ProblemReportType.GENERAL &&
        request.description.isBlank()
    ) {
        return Result.failure(
            IllegalArgumentException("general report description required"),
        )
    }

    val resolver = ProblemReportResolver(repository, responseEvidence)
    val resolution = resolver.resolve(request)
    if (resolution !is ProblemReportResolution.Success) {
        return Result.failure(
            ProblemReportTargetUnavailableException(),
        )
    }

    return runCatching {
        val metadata = createProblemReportMetadata(context)
        val reportText = ProblemReportFormatter.format(
            request = request,
            evidence = resolution.evidence,
            metadata = metadata,
            safeObiDiagnostics = existingSafeObiDiagnostics(
                recorder = recorder,
                include = request.includeObiDiagnostics,
            ),
            safeDiagnosticEvents = if (request.includeObiDiagnostics) {
                TaksulaDiagnostics.recorder.report()
            } else null,
        )
        val file = withContext(Dispatchers.IO) {
            ProblemReportFileStore(context.cacheDir).write(
                reportText = reportText,
                createdAtMillis = metadata.createdAtMillis,
            )
        }
        val uri = problemReportUri(context, file)
        ProblemReportSharePayload(
            intent = buildProblemReportSendIntent(
                reportUri = uri,
                subject = subject,
                body = body,
            ),
            reportFile = file,
        )
    }
}

internal fun launchProblemReportShare(
    payload: ProblemReportSharePayload,
    chooserTitle: String,
    startActivity: (Intent) -> Unit,
): ProblemReportUiError? =
    try {
        startActivity(
            Intent.createChooser(
                payload.intent,
                chooserTitle,
            ),
        )
        null
    } catch (_: ActivityNotFoundException) {
        payload.reportFile.delete()
        ProblemReportUiError.SHARE_UNAVAILABLE
    }
