package pl.lukaszpeciak.towarownik

import android.os.Build
import android.os.Bundle
import android.net.Uri
import pl.lukaszpeciak.towarownik.attachment.AttachmentStorage
import pl.lukaszpeciak.towarownik.attachment.AdvisorAttachment
import pl.lukaszpeciak.towarownik.attachment.AttachmentImportError
import pl.lukaszpeciak.towarownik.attachment.AttachmentImportResult
import pl.lukaszpeciak.towarownik.attachment.AttachmentRenderKind
import pl.lukaszpeciak.towarownik.attachment.AttachmentImporter
import pl.lukaszpeciak.towarownik.attachment.AttachmentImportGuard
import pl.lukaszpeciak.towarownik.attachment.AttachmentType
import pl.lukaszpeciak.towarownik.attachment.CameraCapture
import pl.lukaszpeciak.towarownik.attachment.MultiPendingAttachmentOwnership
import pl.lukaszpeciak.towarownik.attachment.PendingAttachmentsSaver
import pl.lukaszpeciak.towarownik.attachment.MAX_ADVISOR_ATTACHMENTS
import pl.lukaszpeciak.towarownik.attachment.MAX_ADVISOR_ATTACHMENT_TOTAL_BYTES
import pl.lukaszpeciak.towarownik.attachment.appendOrReplaceAttachment
import pl.lukaszpeciak.towarownik.attachment.canSendAdvisorComposer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.lukaszpeciak.towarownik.agent.AdvisorController
import pl.lukaszpeciak.towarownik.agent.AdvisorError
import pl.lukaszpeciak.towarownik.agent.AdvisorUiState
import pl.lukaszpeciak.towarownik.aiusage.AiUsageRepository
import pl.lukaszpeciak.towarownik.aiusage.NbpUsdPlnRateProvider
import pl.lukaszpeciak.towarownik.conversation.ConversationDatabase
import pl.lukaszpeciak.towarownik.conversation.ConversationRepository
import pl.lukaszpeciak.towarownik.conversation.ConversationSummary
import pl.lukaszpeciak.towarownik.conversation.MESSAGE_ROLE_ASSISTANT
import pl.lukaszpeciak.towarownik.conversation.MESSAGE_ROLE_USER
import pl.lukaszpeciak.towarownik.conversation.PersistedConversation
import pl.lukaszpeciak.towarownik.conversation.PersistedSearchAction
import pl.lukaszpeciak.towarownik.conversation.PersistedWebSource
import pl.lukaszpeciak.towarownik.diagnostics.DiagnosticDeviceContext
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnostics
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.DEFAULT_WORKING_PROFILE
import pl.lukaszpeciak.towarownik.product.provider.KWANT_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ProductProviderRegistry
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult
import pl.lukaszpeciak.towarownik.product.provider.ProviderId
import pl.lukaszpeciak.towarownik.product.provider.WorkingProfile
import pl.lukaszpeciak.towarownik.product.provider.WorkingProfileRepository
import pl.lukaszpeciak.towarownik.ui.theme.TowarownikTheme
import pl.lukaszpeciak.towarownik.ui.theme.towarownikColors

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureDiagnosticsContext()
        setContent {
            TowarownikTheme {
                TowarownikApp()
            }
        }
    }

    private fun configureDiagnosticsContext() {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        ObiDiagnostics.recorder.configureDeviceContext(
            DiagnosticDeviceContext(
                versionName = packageInfo.versionName ?: "unknown",
                versionCode = versionCode,
                androidVersion = Build.VERSION.RELEASE,
                apiLevel = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
            ),
        )
    }
}

internal data class ManualSearchOpenRequest(
    val query: String,
    val storeNumber: String,
)

internal fun advisorSearchActionOpenRequest(
    action: PersistedSearchAction,
): ManualSearchOpenRequest =
    ManualSearchOpenRequest(
        query = action.query,
        storeNumber = action.storeNumber,
    )

internal enum class AppSurface {
    ADVISOR,
    MANUAL_SEARCH,
    SETTINGS,
    AI_USAGE,
    DIAGNOSTICS,
    REPORT,
}

internal fun backSurface(surface: AppSurface): AppSurface =
    when (surface) {
        AppSurface.ADVISOR -> AppSurface.ADVISOR
        AppSurface.MANUAL_SEARCH -> AppSurface.ADVISOR
        AppSurface.SETTINGS -> AppSurface.ADVISOR
        AppSurface.AI_USAGE -> AppSurface.SETTINGS
        AppSurface.DIAGNOSTICS -> AppSurface.SETTINGS
        AppSurface.REPORT -> AppSurface.ADVISOR
    }

internal fun freshAdvisorCaseAfterDelete(
    deletedConversationId: Long,
    activeConversationId: Long?,
): AdvisorCaseUiState? =
    if (deletedConversationId == activeConversationId) {
        AdvisorCaseUiState()
    } else {
        null
    }

@Composable
private fun TowarownikApp() {
    val uiContext = LocalContext.current
    val context = uiContext.applicationContext
    val advisorController = remember { AdvisorController.production(context) }
    val providerRegistry = remember { ProductProviderRegistry.production() }
    val manualSearchController = remember {
        ManualSearchController(providers = providerRegistry)
    }
    val workingProfileRepository = remember {
        WorkingProfileRepository.production(context)
    }
    val attachmentStorage = remember { AttachmentStorage(context) }
    val pendingAttachmentOwnership = remember {
        MultiPendingAttachmentOwnership(context, attachmentStorage)
    }
    val conversationRepository = remember {
        ConversationRepository(
            ConversationDatabase.get(context).conversationDao(),
            attachmentStorage = attachmentStorage,
        )
    }
    val aiUsageRepository = remember {
        AiUsageRepository.production(context)
    }
    val nbpUsdPlnRateProvider = remember {
        NbpUsdPlnRateProvider.production(context)
    }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var surfaceName by rememberSaveable {
        mutableStateOf(AppSurface.ADVISOR.name)
    }
    val surface = runCatching { AppSurface.valueOf(surfaceName) }
        .getOrDefault(AppSurface.ADVISOR)

    var reportTypeName by rememberSaveable {
        mutableStateOf(ProblemReportType.GENERAL.name)
    }
    var reportOriginName by rememberSaveable {
        mutableStateOf(ProblemReportOrigin.SETTINGS.name)
    }
    var reportConversationId by rememberSaveable {
        mutableStateOf<Long?>(null)
    }
    var reportMessageId by rememberSaveable {
        mutableStateOf<Long?>(null)
    }
    val reportType = runCatching {
        ProblemReportType.valueOf(reportTypeName)
    }.getOrDefault(ProblemReportType.GENERAL)
    val reportOrigin = runCatching {
        ProblemReportOrigin.valueOf(reportOriginName)
    }.getOrDefault(ProblemReportOrigin.SETTINGS)

    var activeConversationId by rememberSaveable {
        mutableStateOf<Long?>(null)
    }
    var globalWorkingProfile by remember {
        mutableStateOf(workingProfileRepository.load())
    }
    var selectedWorkingProfile by remember {
        mutableStateOf(globalWorkingProfile)
    }
    var profileBranches by remember {
        mutableStateOf<List<ProviderBranch>>(emptyList())
    }
    var profileBranchesLoading by remember {
        mutableStateOf(false)
    }
    var freshCaseSelected by rememberSaveable {
        mutableStateOf(false)
    }
    var emptyPromptIndex by rememberSaveable {
        mutableStateOf(0)
    }
    var advisorCase by rememberSaveable(
        stateSaver = AdvisorCaseUiStateSaver,
    ) {
        mutableStateOf(AdvisorCaseUiState())
    }
    var advisorState by remember {
        mutableStateOf<AdvisorUiState>(AdvisorUiState.Idle)
    }
    var pendingAttachments by rememberSaveable(saver = PendingAttachmentsSaver) {
        mutableStateOf<List<AdvisorAttachment>>(emptyList())
    }
    var attachmentError by remember { mutableStateOf<AttachmentImportError?>(null) }
    var attachmentImporting by remember { mutableStateOf(false) }
    val attachmentImportGuard = remember { AttachmentImportGuard() }
    val attachmentImportMutex = remember { Mutex() }
    var profileMenuRequest by remember { mutableStateOf(0) }
    val attachmentImporter = remember {
        AttachmentImporter(
            resolver = context.contentResolver,
            storage = attachmentStorage,
            beforePublish = pendingAttachmentOwnership::stageImportedCandidate,
        )
    }
    val cameraCapture = remember { CameraCapture(context) }
    var advisorJob by remember { mutableStateOf<Job?>(null) }
    var showAiBudgetWarning by rememberSaveable {
        mutableStateOf(false)
    }
    var draftPersistJob by remember { mutableStateOf<Job?>(null) }
    val advisorRequestGuard = remember { AdvisorRequestGuard() }

    LaunchedEffect(Unit) {
        pendingAttachments = pendingAttachmentOwnership.reconcileAfterStartup(
            restored = pendingAttachments,
            isPersisted = conversationRepository::isAttachmentPersisted,
        )
    }

    fun invalidateAttachmentImport() {
        attachmentImportGuard.invalidate()
        attachmentImporting = false
    }

    // Invalidated when switching cases, before an in-flight picker can return.
    var pickerEpoch by remember { mutableStateOf(0L) }
    var pickerLaunchedAt by remember { mutableStateOf(0L) }
    var replacementTarget by remember { mutableStateOf<String?>(null) }

    fun importAttachments(
        uris: List<Uri>,
        suggestedName: String? = null,
        replaceLocalId: String? = null,
        afterImport: () -> Unit = {},
    ) {
        if (uris.isEmpty()) { afterImport(); return }
        if (uris.size > MAX_ADVISOR_ATTACHMENTS ||
            (replaceLocalId == null &&
                pendingAttachments.size + uris.size > MAX_ADVISOR_ATTACHMENTS) ||
            (replaceLocalId != null && uris.size != 1)
        ) {
            attachmentError = AttachmentImportError.TOO_MANY
            afterImport()
            return
        }
        val token = attachmentImportGuard.begin()
        attachmentImporting = true
        attachmentError = null
        scope.launch {
            try {
                attachmentImportMutex.withLock {
                    for ((index, uri) in uris.withIndex()) {
                        val result = withContext(Dispatchers.IO) {
                            try {
                                attachmentImporter.import(
                                    uri, if (uris.size == 1) suggestedName else null,
                                )
                            } catch (_: Exception) {
                                AttachmentImportResult.Failure(AttachmentImportError.CANNOT_OPEN)
                            }
                        }
                        if (!attachmentImportGuard.isCurrent(token)) {
                            if (result is AttachmentImportResult.Success) {
                                pendingAttachmentOwnership.discardImportedCandidate(result.attachment)
                            }
                            break
                        }
                        when (result) {
                            is AttachmentImportResult.Success -> {
                                val item = result.attachment
                                val next = appendOrReplaceAttachment(
                                    pendingAttachments, item,
                                    if (index == 0) replaceLocalId else null,
                                )
                                if (next == null) {
                                    attachmentError = if (
                                        replaceLocalId == null &&
                                        pendingAttachments.size >= MAX_ADVISOR_ATTACHMENTS
                                    ) AttachmentImportError.TOO_MANY
                                    else AttachmentImportError.TOTAL_TOO_LARGE
                                    pendingAttachmentOwnership.discardImportedCandidate(item)
                                    break
                                }
                                val replaced = pendingAttachments.filter { old ->
                                    next.none { it.localId == old.localId }
                                }
                                if (pendingAttachmentOwnership.publishSelection(next, replaced, item)) {
                                    pendingAttachments = next
                                    attachmentError = null
                                } else {
                                    pendingAttachmentOwnership.discardImportedCandidate(item)
                                    attachmentError = AttachmentImportError.CANNOT_OPEN
                                    break
                                }
                            }
                            is AttachmentImportResult.Failure -> {
                                attachmentError = result.error
                                break
                            }
                        }
                    }
                }
            } finally {
                if (attachmentImportGuard.isCurrent(token)) attachmentImporting = false
                afterImport()
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ADVISOR_ATTACHMENTS),
    ) { uris ->
        if (pickerEpoch == pickerLaunchedAt) importAttachments(uris)
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (pickerEpoch == pickerLaunchedAt) importAttachments(uris)
    }
    val replacePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (pickerEpoch == pickerLaunchedAt) {
            uri?.let { importAttachments(listOf(it), replaceLocalId = replacementTarget) }
        }
        replacementTarget = null
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { captured ->
        val captureFile = cameraCapture.file
        if (captured && captureFile != null) {
            if (pickerEpoch == pickerLaunchedAt) {
                importAttachments(
                    listOf(FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", captureFile,
                    )),
                    suggestedName = "photo.jpg",
                    afterImport = cameraCapture::cleanup,
                )
            } else cameraCapture.cleanup()
        } else if (!captured) {
            cameraCapture.cleanup()
        } else {
            attachmentError = AttachmentImportError.CAMERA_FAILED
        }
    }

    DisposableEffect(Unit) {
        onDispose { cameraCapture.cleanup() }
    }

    var manualQuery by rememberSaveable { mutableStateOf("") }
    var manualWorkingProfile by remember {
        mutableStateOf(globalWorkingProfile)
    }
    var manualBranchLabel by remember {
        mutableStateOf<String?>(null)
    }
    var manualState by rememberSaveable(
        stateSaver = ManualSearchUiStateSaver,
    ) {
        mutableStateOf<ManualSearchUiState>(ManualSearchUiState.Idle)
    }
    var manualJob by remember { mutableStateOf<Job?>(null) }
    val manualRequestGuard = remember { AdvisorRequestGuard() }
    var drawerQuery by rememberSaveable { mutableStateOf("") }

    val historyFlow = remember(drawerQuery) {
        conversationRepository.observeConversations(drawerQuery)
    }
    val conversationHistory by historyFlow.collectAsState(
        initial = emptyList(),
    )

    fun clearManualProfileContext() {
        manualRequestGuard.invalidate()
        manualJob?.cancel()
        manualJob = null
        manualState = ManualSearchUiState.Idle
    }

    fun clearPendingAttachment() {
        pickerEpoch++
        invalidateAttachmentImport()
        // Never carry old selection into a different conversation, even when
        // a preferences write fails; old durable markers remain for startup cleanup.
        pendingAttachmentOwnership.clearPending(pendingAttachments)
        pendingAttachments = emptyList()
        attachmentError = null
    }

    fun removePendingAttachment(localId: String) {
        invalidateAttachmentImport()
        val dropped = pendingAttachments.filter { it.localId == localId }
        if (dropped.isEmpty()) return
        val remaining = pendingAttachments.filterNot { it.localId == localId }
        if (pendingAttachmentOwnership.publishSelection(remaining, dropped)) {
            pendingAttachments = remaining
            attachmentError = null
        }
    }

    fun applyConversation(
        conversation: PersistedConversation?,
    ) {
        val nextConversationId = conversation?.id
        val nextProfile = conversation?.workingProfile ?: globalWorkingProfile
        if (
            activeConversationId != nextConversationId ||
            selectedWorkingProfile != nextProfile
        ) {
            clearManualProfileContext()
        }

        if (conversation == null) {
            activeConversationId = null
            selectedWorkingProfile = globalWorkingProfile
            advisorCase = AdvisorCaseUiState()
            return
        }

        activeConversationId = conversation.id
        selectedWorkingProfile = conversation.workingProfile
        advisorCase = conversation.toAdvisorCaseUiState()
    }

    suspend fun cancelAndRecoverActiveTurn(
        recoverInterrupted: Boolean = true,
    ) {
        advisorRequestGuard.invalidate()
        advisorJob?.cancelAndJoin()
        advisorJob = null
        draftPersistJob?.cancelAndJoin()
        draftPersistJob = null

        if (recoverInterrupted) {
            activeConversationId?.let { conversationId ->
                conversationRepository.recoverInterruptedTurn(conversationId)
            }
        }
    }

    fun newAdvisorCase() {
        scope.launch {
            clearPendingAttachment()
            cancelAndRecoverActiveTurn()
            clearManualProfileContext()
            freshCaseSelected = true
            emptyPromptIndex = (emptyPromptIndex + 1) % EMPTY_ADVISOR_PROMPTS.size
            activeConversationId = null
            selectedWorkingProfile = globalWorkingProfile
            advisorState = AdvisorUiState.Idle
            advisorCase = AdvisorCaseUiState()
        }
    }

    fun openConversation(conversationId: Long) {
        scope.launch {
            clearPendingAttachment()
            cancelAndRecoverActiveTurn()
            clearManualProfileContext()
            val loaded = conversationRepository
                .loadRecoveringInterrupted(conversationId)
            if (loaded != null) {
                freshCaseSelected = false
                advisorState = AdvisorUiState.Idle
                applyConversation(loaded)
            }
            drawerState.close()
        }
    }

    fun deleteConversation(conversationId: Long) {
        scope.launch {
            val freshCase = freshAdvisorCaseAfterDelete(
                deletedConversationId = conversationId,
                activeConversationId = activeConversationId,
            )
            if (freshCase != null) {
                clearPendingAttachment()
                cancelAndRecoverActiveTurn(
                    recoverInterrupted = false,
                )
            }

            conversationRepository.deleteConversation(conversationId)

            if (freshCase != null) {
                clearManualProfileContext()
                activeConversationId = null
                selectedWorkingProfile = globalWorkingProfile
                    freshCaseSelected = true
                emptyPromptIndex = (emptyPromptIndex + 1) % EMPTY_ADVISOR_PROMPTS.size
                advisorState = AdvisorUiState.Idle
                advisorCase = freshCase
                drawerState.close()
            }
        }
    }

    fun applyGlobalWorkingProfile(
        profile: WorkingProfile,
        branches: List<ProviderBranch>,
    ) {
        if (activeConversationId != null || advisorJob?.isActive == true) {
            return
        }
        workingProfileRepository.save(profile)
        globalWorkingProfile = profile
        selectedWorkingProfile = profile
        profileBranches = branches
        clearManualProfileContext()
    }

    fun selectProvider(providerId: ProviderId) {
        if (
            activeConversationId != null ||
            advisorJob?.isActive == true ||
            providerId == selectedWorkingProfile.providerId
        ) {
            return
        }
        scope.launch {
            profileBranchesLoading = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    providerRegistry.resolve(providerId).branches()
                }.getOrNull()
            }
            profileBranchesLoading = false
            val available = result as? ProviderBranchResult.Available
                ?: return@launch
            val branches = available.branches
            val branch = when (providerId) {
                OBI_PROVIDER_ID ->
                    branches.firstOrNull {
                        it.branchId == DEFAULT_WORKING_PROFILE.branchId
                    }
                else -> branches.sortedBy { it.name }.firstOrNull()
            } ?: return@launch
            applyGlobalWorkingProfile(
                WorkingProfile(providerId, branch.branchId),
                branches,
            )
        }
    }

    fun selectBranch(branch: ProviderBranch) {
        if (
            activeConversationId != null ||
            advisorJob?.isActive == true ||
            branch !in profileBranches
        ) {
            return
        }
        applyGlobalWorkingProfile(
            WorkingProfile(
                providerId = selectedWorkingProfile.providerId,
                branchId = branch.branchId,
            ),
            profileBranches,
        )
    }

    fun updateAdvisorDraft(value: String) {
        advisorCase = advisorCase.withDraft(value)
        val conversationId = activeConversationId ?: return
        draftPersistJob?.cancel()
        draftPersistJob = scope.launch {
            conversationRepository.updateDraft(
                conversationId = conversationId,
                draft = value,
            )
        }
    }

    fun submitAdvisorTurn() {
        if (advisorJob?.isActive == true || attachmentImporting) return

        val submitted = advisorCase.draft.trim()
        val submittedAttachments = pendingAttachments.toList()
        if (submitted.isBlank() && submittedAttachments.isEmpty()) return
        if (submittedAttachments.size > MAX_ADVISOR_ATTACHMENTS ||
            submittedAttachments.sumOf { it.byteSize } > MAX_ADVISOR_ATTACHMENT_TOTAL_BYTES
        ) {
            attachmentError = AttachmentImportError.TOTAL_TOO_LARGE
            return
        }
        val turnProfile = selectedWorkingProfile

        val generation = advisorRequestGuard.token()
        advisorJob = scope.launch {
            draftPersistJob?.cancelAndJoin()
            draftPersistJob = null

            val turn = conversationRepository.beginUserTurn(
                conversationId = activeConversationId,
                text = submitted,
                workingProfile = turnProfile,
                attachments = submittedAttachments,
            )
            pendingAttachmentOwnership.handoffToPersisted(submittedAttachments)
            pendingAttachments = emptyList()

            if (!advisorRequestGuard.isTokenCurrent(generation)) {
                conversationRepository.recoverInterruptedTurn(
                    turn.conversationId,
                )
                return@launch
            }

            activeConversationId = turn.conversationId
            freshCaseSelected = false
            val persistedTurnConversation = conversationRepository.load(turn.conversationId)
            persistedTurnConversation?.let(::applyConversation)

            var toolAssistedRecorded = false

            val finalState = runAdvisorForWorkingProfile(
                workingProfile = turn.workingProfile,
                onAdvisorStarted = {
                    runCatching {
                        aiUsageRepository.recordTurnStarted()
                    }
                },
            ) { providerId, branchId ->
                advisorController.runTurn(
                    input = submitted,
                    previousResponseId = turn.previousResponseId,
                    conversationStoreNumber = branchId,
                    conversationProviderId = providerId,
                    attachments = submittedAttachments,
                    historicalVerifiedProducts = persistedTurnConversation
                        ?.messages
                        ?.asReversed()
                        ?.filter { it.role == "ASSISTANT" }
                        ?.take(3)
                        ?.flatMap { it.products }
                        .orEmpty(),
                    onOpenAiResponse = { usage, webSearchCalls ->
                    runCatching {
                        aiUsageRepository.recordOpenAiResponse(
                            usage = usage,
                            webSearchCalls = webSearchCalls,
                        )
                    }
                },
                    onToolRequestObserved = {
                        if (!toolAssistedRecorded) {
                            toolAssistedRecorded = true
                            runCatching {
                                aiUsageRepository.recordToolAssistedTurn()
                            }
                        }
                    },
                ) { state ->
                    if (
                        advisorRequestGuard.isCurrent(
                            token = generation,
                            expectedConversationId = turn.conversationId,
                            activeConversationId = activeConversationId,
                        )
                    ) {
                        advisorState = state
                    }
                }
            }.also { state ->
                if (
                    state == AdvisorUiState.Error(
                        AdvisorError.UNSUPPORTED_PROVIDER,
                    ) &&
                    advisorRequestGuard.isCurrent(
                        token = generation,
                        expectedConversationId = turn.conversationId,
                        activeConversationId = activeConversationId,
                    )
                ) {
                    advisorState = state
                }
            }

            if (
                !advisorRequestGuard.isCurrent(
                    token = generation,
                    expectedConversationId = turn.conversationId,
                    activeConversationId = activeConversationId,
                )
            ) {
                return@launch
            }

            when (finalState) {
                is AdvisorUiState.Success -> {
                    val display = normalizeAdvisorDisplay(
                        raw = finalState.text,
                        sources = finalState.sources,
                    )
                    conversationRepository.completeAssistantTurn(
                        conversationId = turn.conversationId,
                        text = display.text,
                        finalResponseId = finalState.responseId,
                        products = finalState.products,
                        sources = display.sources,
                        advisorTraceId = finalState.traceId,
                        searchActions = finalState.searchActions.map { action ->
                            PersistedSearchAction(
                                query = action.query,
                                storeNumber = action.storeNumber,
                                reportedTotalCount =
                                    action.reportedTotalCount,
                            )
                        },
                    )
                    if (
                        advisorRequestGuard.isCurrent(
                            token = generation,
                            expectedConversationId = turn.conversationId,
                            activeConversationId = activeConversationId,
                        )
                    ) {
                        conversationRepository.load(turn.conversationId)
                            ?.let(::applyConversation)
                        advisorState = finalState.copy(text = display.text)
                    }
                }

                is AdvisorUiState.Error -> {
                    val recovery = conversationRepository
                        .recoverFailedAdvisorTurn(
                            conversationId = turn.conversationId,
                            claimPendingAttachment = { item ->
                                pendingAttachmentOwnership.claimRecovered(listOf(item))
                            },
                            claimPendingAttachments =
                                pendingAttachmentOwnership::claimRecovered,
                        )
                    if (
                        advisorRequestGuard.isCurrent(
                            token = generation,
                            expectedConversationId = turn.conversationId,
                            activeConversationId = activeConversationId,
                        )
                    ) {
                        pendingAttachments = recovery.pendingAttachments
                        applyConversation(recovery.conversation)
                        advisorState = finalState
                    }
                }

                AdvisorUiState.Idle,
                AdvisorUiState.LoadingProxy,
                AdvisorUiState.RunningLocalTool,
                AdvisorUiState.WaitingForFinalAnswer -> Unit
            }

            if (
                runCatching {
                    aiUsageRepository.consumePendingBudgetWarning()
                }.getOrDefault(false)
            ) {
                showAiBudgetWarning = true
            }
        }
    }

    fun submitManualSearch() {
        val profile = manualWorkingProfile
        val branchLabel = manualBranchLabel
        val submission = prepareSearchSubmission(manualQuery)
        manualRequestGuard.invalidate()
        val generation = manualRequestGuard.token()
        manualJob?.cancel()
        manualQuery = submission.nextVisibleQuery
        manualJob = scope.launch {
            manualSearchController.submit(
                input = submission.submittedQuery,
                workingProfile = profile,
                branchLabel = branchLabel,
            ) { state ->
                if (
                    manualRequestGuard.isTokenCurrent(generation) &&
                    manualWorkingProfile == profile
                ) {
                    manualState = state
                }
            }
        }
    }

    fun selectManualResult(item: ManualSearchResultItem) {
        val profile = manualWorkingProfile
        manualRequestGuard.invalidate()
        val generation = manualRequestGuard.token()
        manualJob?.cancel()
        manualJob = scope.launch {
            manualSearchController.select(
                item = item,
                workingProfile = profile,
            ) { state ->
                if (
                    manualRequestGuard.isTokenCurrent(generation) &&
                    manualWorkingProfile == profile
                ) {
                    manualState = state
                }
            }
        }
    }

    fun showMoreManualResults() {
        val current = manualState as?
            ManualSearchUiState.SearchResults ?: return
        if (!current.canShowMore) return
        val profile = manualWorkingProfile
        manualRequestGuard.invalidate()
        val generation = manualRequestGuard.token()
        manualJob?.cancel()
        manualJob = scope.launch {
            manualSearchController.showMore(
                current = current,
                workingProfile = profile,
            ) { state ->
                if (
                    manualRequestGuard.isTokenCurrent(generation) &&
                    manualWorkingProfile == profile
                ) {
                    manualState = state
                }
            }
        }
    }

    fun openManualSearch() {
        if (manualWorkingProfile != globalWorkingProfile) {
            clearManualProfileContext()
        }
        manualWorkingProfile = globalWorkingProfile
        manualBranchLabel = profileBranches
            .firstOrNull { it.branchId == globalWorkingProfile.branchId }
            ?.name
        surfaceName = AppSurface.MANUAL_SEARCH.name
    }

    fun openAdvisorSearchAction(
        action: PersistedSearchAction,
    ) {
        val request = advisorSearchActionOpenRequest(action)
        manualRequestGuard.invalidate()
        val generation = manualRequestGuard.token()
        manualJob?.cancel()
        manualQuery = request.query
        manualWorkingProfile = WorkingProfile(
            providerId = OBI_PROVIDER_ID,
            branchId = BranchId(request.storeNumber),
        )
        manualBranchLabel = null
        manualState = ManualSearchUiState.Idle
        surfaceName = AppSurface.MANUAL_SEARCH.name
        manualJob = scope.launch {
            manualSearchController.submit(
                input = request.query,
                workingProfile = manualWorkingProfile,
                branchLabel = manualBranchLabel,
            ) { state ->
                if (
                    manualRequestGuard.isTokenCurrent(generation) &&
                    manualWorkingProfile.providerId == OBI_PROVIDER_ID &&
                    manualWorkingProfile.branchId.value == request.storeNumber
                ) {
                    manualState = state
                }
            }
        }
    }

    fun openSettings() {
        surfaceName = AppSurface.SETTINGS.name
        scope.launch {
            drawerState.close()
        }
    }

    fun openAiUsage() {
        surfaceName = AppSurface.AI_USAGE.name
    }

    fun openDiagnostics() {
        surfaceName = AppSurface.DIAGNOSTICS.name
    }

    fun openGeneralReport() {
        reportTypeName = ProblemReportType.GENERAL.name
        reportOriginName = ProblemReportOrigin.SETTINGS.name
        reportConversationId = activeConversationId
        reportMessageId = null
        surfaceName = AppSurface.REPORT.name
    }

    fun openAssistantReport(messageId: Long) {
        val conversationId = activeConversationId ?: return
        reportTypeName = ProblemReportType.ASSISTANT_RESPONSE.name
        reportOriginName = ProblemReportOrigin.ADVISOR.name
        reportConversationId = conversationId
        reportMessageId = messageId
        surfaceName = AppSurface.REPORT.name
    }

    fun closeReport() {
        surfaceName = reportBackSurface(reportOrigin).name
    }

    fun navigateBackFrom(currentSurface: AppSurface) {
        surfaceName = backSurface(currentSurface).name
    }

    LaunchedEffect(Unit) {
        profileBranchesLoading = true
        profileBranches = withContext(Dispatchers.IO) {
            when (
                val result = runCatching {
                    providerRegistry.resolve(
                        globalWorkingProfile.providerId,
                    ).branches()
                }.getOrNull()
            ) {
                is ProviderBranchResult.Available -> result.branches
                else -> emptyList()
            }
        }
        profileBranchesLoading = false
        conversationRepository.cleanupExpiredConversations()
        if (
            runCatching {
                aiUsageRepository.consumePendingBudgetWarning()
            }.getOrDefault(false)
        ) {
            showAiBudgetWarning = true
        }

        val savedConversationId = activeConversationId
        when {
            savedConversationId != null -> {
                val loaded = conversationRepository
                    .loadRecoveringInterrupted(savedConversationId)
                if (loaded != null) {
                    applyConversation(loaded)
                } else {
                    freshCaseSelected = true
                    advisorState = AdvisorUiState.Idle
                    applyConversation(null)
                }
            }

            !freshCaseSelected &&
                advisorCase.draft.isBlank() &&
                advisorCase.messages.isEmpty() -> {
                conversationRepository
                    .loadMostRecentRecoveringInterrupted()
                    ?.let {
                        freshCaseSelected = false
                        applyConversation(it)
                    }
            }
        }
    }

    when (surface) {
        AppSurface.ADVISOR -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ConversationDrawer(
                        query = drawerQuery,
                        conversations = conversationHistory,
                        onQueryChange = { drawerQuery = it },
                        onNewConversation = {
                            newAdvisorCase()
                            scope.launch { drawerState.close() }
                        },
                        onOpenConversation = ::openConversation,
                        onDeleteConversation = ::deleteConversation,
                        onOpenSettings = ::openSettings,
                    )
                },
            ) {
                AdvisorChatScreen(
                    advisorCase = advisorCase,
                    state = advisorState,
                    onDraftChange = ::updateAdvisorDraft,
                    onSubmit = ::submitAdvisorTurn,
                    pendingAttachments = pendingAttachments,
                    attachmentError = attachmentError,
                    attachmentImporting = attachmentImporting,
                    attachmentStorage = attachmentStorage,
                    onRemoveAttachment = ::removePendingAttachment,
                    onReplaceAttachment = { localId ->
                        replacementTarget = localId
                        pickerLaunchedAt = pickerEpoch
                        replacePicker.launch(arrayOf("application/pdf", "image/*"))
                    },
                    onOpenCamera = {
                        if (pendingAttachments.size >= MAX_ADVISOR_ATTACHMENTS) {
                            attachmentError = AttachmentImportError.TOO_MANY
                        } else {
                            pickerLaunchedAt = pickerEpoch
                            runCatching { cameraCapture.createUri() }
                            .onSuccess(cameraLauncher::launch)
                            .onFailure { attachmentError = AttachmentImportError.CAMERA_FAILED }
                        }
                    },
                    onOpenPhotos = {
                        if (pendingAttachments.size >= MAX_ADVISOR_ATTACHMENTS) {
                            attachmentError = AttachmentImportError.TOO_MANY
                        } else {
                            pickerLaunchedAt = pickerEpoch
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }
                    },
                    onOpenFile = {
                        if (pendingAttachments.size >= MAX_ADVISOR_ATTACHMENTS) {
                            attachmentError = AttachmentImportError.TOO_MANY
                        } else {
                            pickerLaunchedAt = pickerEpoch
                            filePicker.launch(arrayOf("application/pdf", "image/*"))
                        }
                    },
                    onOpenProfileSelector = { profileMenuRequest++ },
                    onOpenDrawer = {
                        scope.launch { drawerState.open() }
                    },
                    onNewCase = ::newAdvisorCase,
                    onOpenSearch = ::openManualSearch,
                    workingProfile = selectedWorkingProfile,
                    profileBranches =
                        if (
                            selectedWorkingProfile.providerId ==
                                globalWorkingProfile.providerId
                        ) {
                            profileBranches
                        } else {
                            emptyList()
                        },
                    profileSelectorEnabled =
                        activeConversationId == null &&
                            advisorJob?.isActive != true,
                    profileBranchesLoading = profileBranchesLoading,
                    profileMenuRequest = profileMenuRequest,
                    onProviderSelected = ::selectProvider,
                    onBranchSelected = ::selectBranch,
                    onReportAssistantMessage = ::openAssistantReport,
                    onOpenSearchAction = ::openAdvisorSearchAction,
                    emptyPromptIndex = emptyPromptIndex,
                )
            }
        }

        AppSurface.MANUAL_SEARCH -> {
            BackHandler(
                onBack = {
                    navigateBackFrom(AppSurface.MANUAL_SEARCH)
                },
            )
            ManualSearchScreen(
                query = manualQuery,
                state = manualState,
                onQueryChange = { value ->
                    manualQuery = value
                    if (manualJob?.isActive != true) {
                        manualState = ManualSearchUiState.Idle
                    }
                },
                onSearch = ::submitManualSearch,
                onClear = {
                    manualQuery = ""
                    if (manualJob?.isActive != true) {
                        manualState = ManualSearchUiState.Idle
                    }
                },
                onSelectResult = ::selectManualResult,
                onShowMore = ::showMoreManualResults,
                workingProfile = manualWorkingProfile,
                branchLabel = manualBranchLabel,
                onBack = {
                    navigateBackFrom(AppSurface.MANUAL_SEARCH)
                },
            )
        }

        AppSurface.SETTINGS -> {
            SettingsScreen(
                onBack = {
                    navigateBackFrom(AppSurface.SETTINGS)
                },
                onOpenAiUsage = ::openAiUsage,
                onOpenDiagnostics = ::openDiagnostics,
                onReportProblem = ::openGeneralReport,
            )
        }

        AppSurface.AI_USAGE -> {
            AiUsageScreen(
                repository = aiUsageRepository,
                rateProvider = nbpUsdPlnRateProvider,
                onBack = {
                    navigateBackFrom(AppSurface.AI_USAGE)
                },
                onBudgetChanged = {
                    if (advisorJob?.isActive != true) {
                        if (
                            runCatching {
                                aiUsageRepository
                                    .consumePendingBudgetWarning()
                            }.getOrDefault(false)
                        ) {
                            showAiBudgetWarning = true
                        }
                    }
                },
            )
        }

        AppSurface.DIAGNOSTICS -> {
            ObiDiagnosticsScreen(
                onBack = {
                    navigateBackFrom(AppSurface.DIAGNOSTICS)
                },
            )
        }

        AppSurface.REPORT -> {
            val subject = stringResource(
                if (reportType == ProblemReportType.ASSISTANT_RESPONSE) {
                    R.string.report_share_subject_assistant
                } else {
                    R.string.report_share_subject_general
                },
            )
            val body = stringResource(R.string.report_share_body)
            val chooserTitle = stringResource(R.string.report_share_chooser)

            ProblemReportScreen(
                type = reportType,
                canIncludeConversation =
                    reportConversationId != null,
                canIncludeObiDiagnostics =
                    hasExistingSafeObiDiagnostics(
                        ObiDiagnostics.recorder,
                    ),
                onBack = ::closeReport,
                onCreateAndShare = { submission ->
                    if (
                        reportType == ProblemReportType.GENERAL &&
                        submission.description.isBlank()
                    ) {
                        ProblemReportUiError.DESCRIPTION_REQUIRED
                    } else {
                        val request = ProblemReportRequest(
                            type = reportType,
                            category = submission.category,
                            description = submission.description,
                            includeConversation =
                                submission.includeConversation,
                            includeObiDiagnostics =
                                submission.includeObiDiagnostics,
                            conversationId = reportConversationId,
                            reportedMessageId = reportMessageId,
                            advisorFailureDiagnostic =
                                advisorFailureDiagnosticForReport(
                                    type = reportType,
                                    currentFailureDiagnostic =
                                        (advisorState as? AdvisorUiState.Error)
                                            ?.diagnostic
                                            ?.reportValue(),
                                ),
                        )
                        val result = createProblemReportSharePayload(
                            context = uiContext,
                            repository = conversationRepository,
                            request = request,
                            subject = subject,
                            body = body,
                            recorder = ObiDiagnostics.recorder,
                        )
                        val payload = result.getOrNull()
                        if (payload != null) {
                            launchProblemReportShare(
                                payload = payload,
                                chooserTitle = chooserTitle,
                                startActivity = { intent ->
                                    uiContext.startActivity(intent)
                                },
                            )
                        } else {
                            when (result.exceptionOrNull()) {
                                is ProblemReportTargetUnavailableException ->
                                    ProblemReportUiError.TARGET_UNAVAILABLE
                                else ->
                                    ProblemReportUiError.GENERATION_FAILED
                            }
                        }
                    }
                },
            )
        }
    }

    if (showAiBudgetWarning) {
        AlertDialog(
            onDismissRequest = {
                showAiBudgetWarning = false
            },
            title = {
                Text(
                    stringResource(
                        R.string.ai_budget_warning_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        R.string.ai_budget_warning_message,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAiBudgetWarning = false
                    },
                ) {
                    Text(stringResource(R.string.ai_budget_warning_ack))
                }
            },
        )
    }
}

internal const val CONVERSATION_DRAWER_MAX_WIDTH_DP = 320
internal const val CONVERSATION_DRAWER_MIN_REVEAL_DP = 56

internal fun conversationDrawerWidthDp(
    windowWidthDp: Int,
): Int =
    minOf(
        CONVERSATION_DRAWER_MAX_WIDTH_DP,
        (windowWidthDp - CONVERSATION_DRAWER_MIN_REVEAL_DP)
            .coerceAtLeast(0),
    )

@Composable
private fun ConversationDrawer(
    query: String,
    conversations: List<ConversationSummary>,
    onQueryChange: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var pendingDelete by remember {
        mutableStateOf<ConversationSummary?>(null)
    }
    val warmColors = MaterialTheme.towarownikColors
    val drawerWidth = conversationDrawerWidthDp(
        LocalConfiguration.current.screenWidthDp,
    ).dp

    ModalDrawerSheet(
        modifier = Modifier.width(drawerWidth),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedButton(
                onClick = onNewConversation,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = warmColors.surfaceRaised,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
                contentPadding = PaddingValues(
                    horizontal = 14.dp,
                    vertical = 10.dp,
                ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add_24),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.new_conversation))
            }

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                label = { Text(stringResource(R.string.search_conversations)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_search_24),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = warmColors.surfaceRaised,
                    unfocusedContainerColor = warmColors.surfaceRaised,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (conversations.isEmpty()) {
                    Text(
                        text = if (query.isBlank()) {
                            stringResource(R.string.no_saved_conversations)
                        } else {
                            stringResource(R.string.no_matching_conversations)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = conversations,
                            key = { conversation -> conversation.id },
                        ) { conversation ->
                            var menuExpanded by remember(conversation.id) {
                                mutableStateOf(false)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        onOpenConversation(conversation.id)
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                    border = null,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = warmColors.surfaceRaised,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    contentPadding = PaddingValues(
                                        horizontal = 12.dp,
                                        vertical = 10.dp,
                                    ),
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.Start,
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Text(
                                            text = conversation.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            text = formatHistoryTimestamp(
                                                conversation.updatedAt,
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                Box {
                                    IconButton(
                                        onClick = {
                                            menuExpanded = true
                                        },
                                    ) {
                                        Icon(
                                            painter = painterResource(
                                                R.drawable.ic_more_vert_24,
                                            ),
                                            contentDescription = stringResource(
                                                R.string.cd_conversation_options,
                                            ),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = {
                                            menuExpanded = false
                                        },
                                        containerColor = warmColors.surfaceRaised,
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = stringResource(
                                                        R.string.delete_conversation,
                                                    ),
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    painter = painterResource(
                                                        R.drawable.ic_delete_24,
                                                    ),
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                pendingDelete = conversation
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
            )

            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                border = null,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = warmColors.surfaceRaised,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                contentPadding = PaddingValues(
                    horizontal = 14.dp,
                    vertical = 10.dp,
                ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_24),
                    contentDescription = stringResource(R.string.cd_open_settings),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.settings),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
            }
        }
    }

    val conversationToDelete = pendingDelete
    if (conversationToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                pendingDelete = null
            },
            containerColor = warmColors.surfaceRaised,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = {
                Text(stringResource(R.string.delete_conversation_confirm))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteConversation(conversationToDelete.id)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun Modifier.bottomComposerSafeArea(): Modifier =
    windowInsetsPadding(
        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
    )

@Composable
private fun AdvisorChatScreen(
    advisorCase: AdvisorCaseUiState,
    state: AdvisorUiState,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    pendingAttachments: List<AdvisorAttachment>,
    attachmentError: AttachmentImportError?,
    attachmentImporting: Boolean,
    attachmentStorage: AttachmentStorage,
    onRemoveAttachment: (String) -> Unit,
    onReplaceAttachment: (String) -> Unit,
    onOpenCamera: () -> Unit,
    onOpenPhotos: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenProfileSelector: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNewCase: () -> Unit,
    onOpenSearch: () -> Unit,
    workingProfile: WorkingProfile,
    profileBranches: List<ProviderBranch>,
    profileSelectorEnabled: Boolean,
    profileBranchesLoading: Boolean,
    profileMenuRequest: Int,
    onProviderSelected: (ProviderId) -> Unit,
    onBranchSelected: (ProviderBranch) -> Unit,
    onReportAssistantMessage: (Long) -> Unit,
    onOpenSearchAction: (PersistedSearchAction) -> Unit,
    emptyPromptIndex: Int,
) {
    val isRunning = state.isRunning()
    val composerEnabled = isAdvisorComposerEnabled(state)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AdvisorTopBar(
                onOpenDrawer = onOpenDrawer,
                onNewCase = onNewCase,
                onOpenSearch = onOpenSearch,
                workingProfile = workingProfile,
                profileBranches = profileBranches,
                profileSelectorEnabled = profileSelectorEnabled,
                profileBranchesLoading = profileBranchesLoading,
                profileMenuRequest = profileMenuRequest,
                onProviderSelected = onProviderSelected,
                onBranchSelected = onBranchSelected,
            )
        },
        bottomBar = {
            AdvisorComposer(
                value = advisorCase.draft,
                enabled = composerEnabled,
                attachments = pendingAttachments,
                attachmentError = attachmentError,
                importInProgress = attachmentImporting,
                attachmentStorage = attachmentStorage,
                workingProfile = workingProfile,
                branchLabel = profileBranches.firstOrNull {
                    it.branchId == workingProfile.branchId
                }?.let {
                    "${it.branchId.value} • ${it.name}"
                } ?: workingProfile.branchId.value,
                profileSwitchEnabled = profileSelectorEnabled,
                onValueChange = onDraftChange,
                onSend = onSubmit,
                onRemoveAttachment = onRemoveAttachment,
                onReplaceAttachment = onReplaceAttachment,
                onOpenCamera = onOpenCamera,
                onOpenPhotos = onOpenPhotos,
                onOpenFile = onOpenFile,
                onOpenProfileSelector = onOpenProfileSelector,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            if (
                advisorCase.messages.isEmpty() &&
                state is AdvisorUiState.Idle
            ) {
                EmptyAdvisorState(
                    promptRes = EMPTY_ADVISOR_PROMPTS[
                        emptyPromptIndex % EMPTY_ADVISOR_PROMPTS.size
                    ],
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 720.dp)
                        .padding(horizontal = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 720.dp),
                    contentPadding = PaddingValues(
                        horizontal = 16.dp,
                        vertical = 20.dp,
                    ),
                    verticalArrangement =
                        Arrangement.spacedBy(12.dp),
                ) {
                    items(advisorCase.messages) { message ->
                        AdvisorMessageBubble(
                            message = message,
                            attachmentStorage = attachmentStorage,
                            onReportAssistantMessage =
                                onReportAssistantMessage,
                            onOpenSearchAction =
                                onOpenSearchAction,
                        )
                    }

                    when (state) {
                        AdvisorUiState.Idle,
                        is AdvisorUiState.Success -> Unit

                        AdvisorUiState.LoadingProxy -> item {
                            AdvisorProgressBubble(
                                stringResource(
                                    R.string.advisor_progress_connecting,
                                ),
                            )
                        }

                        AdvisorUiState.RunningLocalTool -> item {
                            AdvisorProgressBubble(
                                stringResource(
                                    advisorLocalToolProgressRes(
                                        workingProfile.providerId,
                                    ),
                                ),
                            )
                        }

                        AdvisorUiState.WaitingForFinalAnswer -> item {
                            AdvisorProgressBubble(
                                stringResource(
                                    R.string.advisor_progress_preparing,
                                ),
                            )
                        }

                        is AdvisorUiState.Error -> item {
                            AdvisorErrorBubble(
                                message = advisorErrorText(state.error),
                                diagnosticCode =
                                    state.diagnostic?.userCode(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvisorTopBar(
    onOpenDrawer: () -> Unit,
    onNewCase: () -> Unit,
    onOpenSearch: () -> Unit,
    workingProfile: WorkingProfile,
    profileBranches: List<ProviderBranch>,
    profileSelectorEnabled: Boolean,
    profileBranchesLoading: Boolean,
    profileMenuRequest: Int,
    onProviderSelected: (ProviderId) -> Unit,
    onBranchSelected: (ProviderBranch) -> Unit,
) {
    Surface(
        modifier = Modifier.topBarSafeArea(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
            ) {
                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_menu_24),
                        contentDescription = stringResource(
                            R.string.cd_open_navigation,
                        ),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    WorkingProfileSelector(
                        workingProfile = workingProfile,
                        branches = profileBranches,
                        enabled = profileSelectorEnabled,
                        loading = profileBranchesLoading,
                        openMenuRequest = profileMenuRequest,
                        onProviderSelected = onProviderSelected,
                        onBranchSelected = onBranchSelected,
                    )
                }

                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    IconButton(
                        onClick = onNewCase,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add_24),
                            contentDescription = stringResource(
                                R.string.cd_new_conversation,
                            ),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = onOpenSearch,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search_24),
                            contentDescription = stringResource(
                                R.string.cd_open_obi_search,
                            ),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun WorkingProfileSelector(
    workingProfile: WorkingProfile,
    branches: List<ProviderBranch>,
    enabled: Boolean,
    loading: Boolean,
    openMenuRequest: Int = 0,
    onProviderSelected: (ProviderId) -> Unit,
    onBranchSelected: (ProviderBranch) -> Unit,
) {
    var providerExpanded by remember { mutableStateOf(false) }
    var branchExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(openMenuRequest) {
        if (openMenuRequest > 0 && enabled) providerExpanded = true
    }
    val providerLabel = if (workingProfile.providerId == KWANT_PROVIDER_ID) {
        stringResource(R.string.provider_kwant)
    } else {
        stringResource(R.string.provider_obi)
    }
    val branch = branches.firstOrNull {
        it.branchId == workingProfile.branchId
    }
    val branchLabel = branch?.let {
        "${it.branchId.value} • ${it.name}"
    } ?: workingProfile.branchId.value

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(
                onClick = { providerExpanded = true },
                enabled = enabled && !loading,
                contentPadding = PaddingValues(
                    horizontal = 6.dp,
                    vertical = 0.dp,
                ),
            ) {
                Text(
                    text = providerLabel,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            DropdownMenu(
                expanded = providerExpanded,
                onDismissRequest = { providerExpanded = false },
            ) {
                listOf(
                    OBI_PROVIDER_ID to R.string.provider_obi,
                    KWANT_PROVIDER_ID to R.string.provider_kwant,
                ).forEach { (providerId, labelRes) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes)) },
                        onClick = {
                            providerExpanded = false
                            onProviderSelected(providerId)
                        },
                    )
                }
            }
        }

        Text(
            text = "•",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box {
            TextButton(
                onClick = { branchExpanded = true },
                enabled = enabled && !loading && branches.isNotEmpty(),
                contentPadding = PaddingValues(
                    horizontal = 6.dp,
                    vertical = 0.dp,
                ),
            ) {
                Text(
                    text = if (loading) {
                        stringResource(R.string.profile_loading_branches)
                    } else {
                        branchLabel
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
            DropdownMenu(
                expanded = branchExpanded,
                onDismissRequest = { branchExpanded = false },
            ) {
                branches.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    "${option.branchId.value} • ${option.name}",
                                )
                                option.address?.let { address ->
                                    Text(
                                        text = address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            branchExpanded = false
                            onBranchSelected(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvisorComposer(
    value: String,
    enabled: Boolean,
    attachments: List<AdvisorAttachment>,
    attachmentError: AttachmentImportError?,
    importInProgress: Boolean,
    attachmentStorage: AttachmentStorage,
    workingProfile: WorkingProfile,
    branchLabel: String,
    profileSwitchEnabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onReplaceAttachment: (String) -> Unit,
    onOpenCamera: () -> Unit,
    onOpenPhotos: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenProfileSelector: () -> Unit,
) {
    val warmColors = MaterialTheme.towarownikColors
    val context = LocalContext.current
    var actionsExpanded by remember { mutableStateOf(false) }
    val provider = if (workingProfile.providerId == KWANT_PROVIDER_ID) {
        stringResource(R.string.provider_kwant)
    } else {
        stringResource(R.string.provider_obi)
    }

    Surface(
        modifier = Modifier.bottomComposerSafeArea(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            attachmentError?.let {
                Text(
                    text = attachmentErrorText(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    color = warmColors.surfaceRaised,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column {
                        if (attachments.isNotEmpty() || importInProgress) {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(start = 10.dp, end = 4.dp, top = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                attachments.forEach { item ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (item.type == AttachmentType.IMAGE) {
                                            AsyncImage(
                                                model = attachmentStorage.contentUri(context, item.localId),
                                                contentDescription = stringResource(R.string.attachment_image_preview),
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.size(40.dp),
                                            )
                                        } else {
                                            PdfAttachmentBadge(modifier = Modifier.size(38.dp))
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.displayName,
                                                maxLines = 1,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Text(
                                                text = formatAttachmentByteSize(item.byteSize),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(onClick = { onReplaceAttachment(item.localId) },
                                            modifier = Modifier.size(36.dp)) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_document_24),
                                                contentDescription = stringResource(R.string.attachment_replace),
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                        IconButton(onClick = { onRemoveAttachment(item.localId) },
                                            modifier = Modifier.size(36.dp)) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_close_24),
                                                contentDescription = stringResource(R.string.attachment_remove),
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                                if (importInProgress) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(22.dp), strokeWidth = 2.dp,
                                        )
                                        Text(
                                            text = stringResource(R.string.attachment_importing),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = enabled,
                            minLines = 1,
                            maxLines = 4,
                            shape = RoundedCornerShape(18.dp),
                            placeholder = { Text(stringResource(R.string.advisor_composer_placeholder)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = warmColors.surfaceRaised,
                                unfocusedContainerColor = warmColors.surfaceRaised,
                                disabledContainerColor = warmColors.surfaceRaised,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = warmColors.surfaceRaised,
                                disabledBorderColor = warmColors.surfaceRaised,
                                cursorColor = MaterialTheme.colorScheme.primary,
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Default,
                            ),
                        )
                        Box(modifier = Modifier.padding(start = 4.dp, bottom = 3.dp)) {
                            IconButton(
                                onClick = { actionsExpanded = true },
                                enabled = enabled,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_add_24),
                                    contentDescription = stringResource(R.string.attachment_add),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            DropdownMenu(
                                expanded = actionsExpanded,
                                onDismissRequest = { actionsExpanded = false },
                            ) {
                                AttachmentMenuItem(R.drawable.ic_camera_24, R.string.attachment_camera) {
                                    actionsExpanded = false; onOpenCamera()
                                }
                                AttachmentMenuItem(R.drawable.ic_photo_24, R.string.attachment_photos) {
                                    actionsExpanded = false; onOpenPhotos()
                                }
                                AttachmentMenuItem(R.drawable.ic_document_24, R.string.attachment_file) {
                                    actionsExpanded = false; onOpenFile()
                                }
                                if (profileSwitchEnabled) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(
                                                painterResource(
                                                    R.drawable.ic_store_24,
                                                ),
                                                contentDescription = null,
                                            )
                                        },
                                        text = {
                                            Column {
                                                Text(
                                                    stringResource(
                                                        R.string.attachment_switch_store,
                                                    ),
                                                )
                                                Text(
                                                    "$provider • $branchLabel",
                                                    style =
                                                        MaterialTheme.typography.bodySmall,
                                                    color =
                                                        MaterialTheme.colorScheme
                                                            .onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = {
                                            actionsExpanded = false
                                            onOpenProfileSelector()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                FilledIconButton(
                    onClick = onSend,
                    enabled = canSendAdvisorComposer(
                        enabled = enabled,
                        importInProgress = importInProgress,
                        text = value,
                        attachments = attachments,
                    ),
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_send_24),
                        contentDescription = stringResource(R.string.cd_send_message),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentMenuItem(icon: Int, label: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
        text = { Text(stringResource(label)) },
        onClick = onClick,
    )
}

@Composable
private fun attachmentErrorText(error: AttachmentImportError): String = stringResource(
    when (error) {
        AttachmentImportError.UNSUPPORTED_TYPE -> R.string.attachment_error_unsupported
        AttachmentImportError.TOO_LARGE -> R.string.attachment_error_too_large
        AttachmentImportError.TOO_MANY -> R.string.attachment_error_too_many
        AttachmentImportError.TOTAL_TOO_LARGE -> R.string.attachment_error_total_too_large
        AttachmentImportError.IMAGE_UNREADABLE -> R.string.attachment_error_image
        AttachmentImportError.CANNOT_OPEN -> R.string.attachment_error_open
        AttachmentImportError.CAMERA_FAILED -> R.string.attachment_error_camera
    },
)

private val EMPTY_ADVISOR_PROMPTS = listOf(
    R.string.advisor_empty_prompt_sell,
    R.string.advisor_empty_prompt_customer,
    R.string.advisor_empty_prompt_handle,
    R.string.advisor_empty_prompt_help,
    R.string.advisor_empty_prompt_need,
)

@Composable
private fun EmptyAdvisorState(
    promptRes: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
        )
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(2.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(2.dp),
                ),
        )
        Text(
            text = stringResource(promptRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PdfAttachmentBadge(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(7.dp),
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "PDF",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

internal fun formatAttachmentByteSize(byteSize: Long): String {
    val value = when {
        byteSize < 1024L -> return "$byteSize B"
        byteSize < 1024L * 1024L -> byteSize / 1024.0
        else -> byteSize / (1024.0 * 1024.0)
    }
    val unit = if (byteSize < 1024L * 1024L) "KB" else "MB"
    val formatted = String.format(Locale.ROOT, "%.1f", value)
        .removeSuffix(".0")
    return "$formatted $unit"
}


@Composable
private fun UserMessageContent(
    message: AdvisorChatMessage,
    attachmentStorage: AttachmentStorage,
) {
    Column(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message.attachments.forEach { part ->
            UserAttachmentContent(part, attachmentStorage)
        }
        if (message.text.isNotBlank()) {
            Text(
                text = message.text,
                modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun UserAttachmentContent(
    item: AdvisorAttachment,
    attachmentStorage: AttachmentStorage,
) {
    val context = LocalContext.current
    var imageFailed by remember(item.localId) { mutableStateOf(false) }
    val renderKind = attachmentStorage.renderKind(item)
    when {
        renderKind == AttachmentRenderKind.UNAVAILABLE ||
            imageFailed -> {
            Row(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(
                        R.drawable.ic_document_24,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(
                        R.string.attachment_error_missing_persisted,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        renderKind == AttachmentRenderKind.IMAGE -> {
            AsyncImage(
                model = attachmentStorage.contentUri(
                    context,
                    item.localId,
                ),
                contentDescription = stringResource(
                    R.string.attachment_image_preview,
                ),
                contentScale = ContentScale.Crop,
                onError = { imageFailed = true },
                modifier = Modifier
                    .width(260.dp)
                    .heightIn(min = 140.dp, max = 220.dp),
            )
        }

        else -> {
            Surface(
                modifier = Modifier.widthIn(max = 320.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(
                    alpha = 0.45f,
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(
                        alpha = 0.5f,
                    ),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = 12.dp,
                        vertical = 10.dp,
                    ),
                    horizontalArrangement =
                        Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PdfAttachmentBadge(
                        modifier = Modifier.size(32.dp),
                    )
                    Column {
                        Text(
                            text = item.displayName,
                            maxLines = 2,
                            style =
                                MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = formatAttachmentByteSize(
                                item.byteSize,
                            ),
                            style =
                                MaterialTheme.typography.bodySmall,
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvisorMessageBubble(
    message: AdvisorChatMessage,
    attachmentStorage: AttachmentStorage,
    onReportAssistantMessage: (Long) -> Unit,
    onOpenSearchAction: (PersistedSearchAction) -> Unit,
) {
    val isUser = message.role == ChatMessageRole.USER
    val warmColors = MaterialTheme.towarownikColors

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) {
            Alignment.End
        } else {
            Alignment.Start
        },
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 600.dp),
            shape = RoundedCornerShape(18.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            } else {
                warmColors.surfaceRaised
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = if (isUser) {
                BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                )
            } else {
                null
            },
        ) {
            if (isUser) {
                UserMessageContent(
                    message = message,
                    attachmentStorage = attachmentStorage,
                )
            } else {
                AdvisorAnswerText(message)
            }
        }
        Text(
            text = formatLocalTime(message.createdAt),
            modifier = Modifier.padding(
                horizontal = 6.dp,
                vertical = 3.dp,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isUser) {
            message.products.forEach { product ->
                Spacer(modifier = Modifier.height(8.dp))
                AdvisorVerifiedProductCard(product)
            }

            if (message.searchActions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                AdvisorSearchActions(
                    actions = message.searchActions,
                    onOpenSearchAction = onOpenSearchAction,
                )
            }

            if (message.sources.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                AdvisorWebSources(message.sources)
            }

            message.persistedMessageId?.let { messageId ->
                TextButton(
                    onClick = {
                        onReportAssistantMessage(messageId)
                    },
                    contentPadding = PaddingValues(
                        horizontal = 6.dp,
                        vertical = 2.dp,
                    ),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        painter = painterResource(
                            R.drawable.ic_report_problem_24,
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.report_action),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvisorAnswerText(
    message: AdvisorChatMessage,
) {
    val mappedSources = message.sources.mapIndexedNotNull {
            index,
            source,
        ->
        val end = source.endIndex
        if (
            source.startIndex != null &&
            end != null &&
            end in 1..message.text.length
        ) {
            Triple(end, index + 1, source)
        } else {
            null
        }
    }.sortedWith(
        compareBy<Triple<Int, Int, PersistedWebSource>> {
            it.first
        }.thenBy {
            it.second
        },
    )

    if (mappedSources.isEmpty()) {
        Text(
            text = message.text,
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 10.dp,
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        return
    }

    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = buildAnnotatedString {
        var cursor = 0
        mappedSources
            .groupBy { it.first }
            .toSortedMap()
            .forEach { (end, entries) ->
                if (end > cursor) {
                    append(message.text.substring(cursor, end))
                    cursor = end
                }
                entries.forEach { (_, number, source) ->
                    pushStringAnnotation(
                        tag = "source_url",
                        annotation = source.url,
                    )
                    pushStyle(
                        SpanStyle(
                            color = linkColor,
                        ),
                    )
                    append(" [$number]")
                    pop()
                    pop()
                }
            }
        if (cursor < message.text.length) {
            append(message.text.substring(cursor))
        }
    }

    val uriHandler = LocalUriHandler.current
    ClickableText(
        text = annotated,
        modifier = Modifier.padding(
            horizontal = 14.dp,
            vertical = 10.dp,
        ),
        style = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        onClick = { offset ->
            annotated
                .getStringAnnotations(
                    tag = "source_url",
                    start = offset,
                    end = offset,
                )
                .firstOrNull()
                ?.item
                ?.let { url ->
                    runCatching {
                        uriHandler.openUri(url)
                    }
                }
        },
    )
}

@Composable
private fun AdvisorSearchActions(
    actions: List<PersistedSearchAction>,
    onOpenSearchAction: (PersistedSearchAction) -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        actions.forEach { action ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.towarownikColors.surfaceRaised,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = 12.dp,
                        vertical = 10.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (actions.size > 1) {
                        Text(
                            text = stringResource(
                                R.string.advisor_search_more_query,
                                action.query,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.advisor_search_more_reported,
                            action.reportedTotalCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            onOpenSearchAction(action)
                        },
                        contentPadding = PaddingValues(
                            horizontal = 0.dp,
                            vertical = 2.dp,
                        ),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.advisor_search_more_button,
                                action.reportedTotalCount,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvisorWebSources(
    sources: List<PersistedWebSource>,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.advisor_sources),
            modifier = Modifier.padding(horizontal = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        sources.forEachIndexed { index, source ->
            TextButton(
                onClick = {
                    runCatching {
                        uriHandler.openUri(source.url)
                    }
                },
                contentPadding = PaddingValues(
                    horizontal = 6.dp,
                    vertical = 1.dp,
                ),
            ) {
                Text(
                    text = (index + 1).toString() + ". " + source.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

internal fun advisorLocalToolProgressRes(
    providerId: ProviderId,
): Int =
    if (providerId == KWANT_PROVIDER_ID) {
        R.string.advisor_progress_checking_kwant
    } else {
        R.string.advisor_progress_checking_obi
    }

@Composable
private fun AdvisorProgressBubble(text: String) {
    val warmColors = MaterialTheme.towarownikColors

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = warmColors.surfaceRaised,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 9.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun AdvisorErrorBubble(
    message: String,
    diagnosticCode: String? = null,
) {
    val warmColors = MaterialTheme.towarownikColors

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = warmColors.errorMuted,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 10.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            diagnosticCode?.let { code ->
                Text(
                    text = stringResource(
                        R.string.advisor_error_diagnostic_code,
                        code,
                    ),
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun ManualSearchScreen(
    query: String,
    state: ManualSearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onSelectResult: (ManualSearchResultItem) -> Unit,
    onShowMore: () -> Unit,
    workingProfile: WorkingProfile,
    branchLabel: String?,
    onBack: () -> Unit,
) {
    val isLoading = state is ManualSearchUiState.Loading
    val warmColors = MaterialTheme.towarownikColors

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ManualSearchTopBar(onBack = onBack)
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = 20.dp,
                        vertical = 20.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.manual_search_profile,
                        if (workingProfile.providerId == KWANT_PROVIDER_ID) {
                            stringResource(R.string.provider_kwant)
                        } else {
                            stringResource(R.string.provider_obi)
                        },
                        branchLabel ?: workingProfile.branchId.value,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )

                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(stringResource(R.string.manual_search_input_label))
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_search_24),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = warmColors.surfaceRaised,
                        unfocusedContainerColor = warmColors.surfaceRaised,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (!isLoading) onSearch()
                        },
                    ),
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = onClear,
                            ) {
                                Icon(
                                    painter = painterResource(
                                        R.drawable.ic_close_24,
                                    ),
                                    contentDescription = stringResource(
                                        R.string.cd_clear_search,
                                    ),
                                )
                            }
                        }
                    } else {
                        null
                    },
                )

                Button(
                    onClick = onSearch,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search_24),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.search))
                }

                when (state) {
                    ManualSearchUiState.Idle -> Unit

                    ManualSearchUiState.Loading ->
                        ManualSearchProgress()

                    is ManualSearchUiState.SearchResults ->
                        ManualSearchResults(
                            state = state,
                            onSelectResult = onSelectResult,
                            onShowMore = onShowMore,
                        )

                    is ManualSearchUiState.Product ->
                        VerifiedProductCard(state.item)

                    is ManualSearchUiState.Error ->
                        ErrorText(searchErrorText(state.error))
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ManualSearchTopBar(
    onBack: () -> Unit,
) {
    Surface(
        modifier = Modifier.topBarSafeArea(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back_24),
                        contentDescription = stringResource(R.string.cd_back),
                    )
                }
                Text(
                    text = stringResource(R.string.manual_search_title),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun ManualSearchProgress() {
    val warmColors = MaterialTheme.towarownikColors

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = warmColors.surfaceRaised,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 9.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
            Text(
                text = stringResource(R.string.manual_search_progress),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ManualSearchResults(
    state: ManualSearchUiState.SearchResults,
    onSelectResult: (ManualSearchResultItem) -> Unit,
    onShowMore: () -> Unit,
) {
    val warmColors = MaterialTheme.towarownikColors

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(
                R.string.manual_search_results_range,
                state.visibleItems.size,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        state.reportedTotalCount?.let { total ->
            Text(
                text = stringResource(
                    R.string.manual_search_reported_total,
                    total,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.visibleItems.forEach { item ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = warmColors.surfaceRaised,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = 14.dp,
                        vertical = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    val enrichment = item.enrichment
                    if (enrichment is ManualResultEnrichment.Verified) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            VerifiedProductThumbnail(
                                product = enrichment.product,
                                modifier = Modifier.size(84.dp),
                            )
                            SelectionContainer(
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(
                                    verticalArrangement =
                                        Arrangement.spacedBy(4.dp),
                                ) {
                                    manualResultDisplayName(item)?.let { name ->
                                        Text(
                                            text = name,
                                            style =
                                                MaterialTheme.typography.titleMedium,
                                        )
                                    }
                                    Text(
                                        text = manualResultIdentifierText(item),
                                        style =
                                            MaterialTheme.typography.bodyMedium,
                                        color =
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = manualResultBranchText(item),
                                        style =
                                            MaterialTheme.typography.labelMedium,
                                        color =
                                            MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    } else {
                        SelectionContainer {
                            Column(
                                verticalArrangement =
                                    Arrangement.spacedBy(7.dp),
                            ) {
                                manualResultDisplayName(item)?.let { name ->
                                    Text(
                                        text = name,
                                        style =
                                            MaterialTheme.typography.titleMedium,
                                    )
                                }
                                Text(
                                    text = manualResultIdentifierText(item),
                                    style =
                                        MaterialTheme.typography.bodyMedium,
                                    color =
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = manualResultBranchText(item),
                                    style =
                                        MaterialTheme.typography.labelMedium,
                                    color =
                                        MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    when (enrichment) {
                        ManualResultEnrichment.Pending,
                        ManualResultEnrichment.Loading -> {
                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(8.dp),
                                verticalAlignment =
                                    Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.manual_search_enriching,
                                    ),
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        ManualResultEnrichment.Unavailable -> {
                            Text(
                                text = stringResource(
                                    R.string.manual_search_enrichment_failed,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = { onSelectResult(item) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(
                                        R.string.manual_search_retry_exact,
                                    ),
                                )
                            }
                        }

                        is ManualResultEnrichment.Verified -> {
                            val product = enrichment.product
                            Text(
                                text = formatStoreStock(product.stock),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = formatStorePrice(
                                    product.grossPrice,
                                ),
                                style =
                                    MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            ManualVerifiedProductLink(product)
                        }
                    }
                }
            }
        }

        if (state.canShowMore) {
            OutlinedButton(
                onClick = onShowMore,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(stringResource(R.string.show_more))
            }
        }
    }
}

@Composable
private fun manualResultIdentifierText(
    item: ManualSearchResultItem,
): String =
    if (item.ref.providerId == KWANT_PROVIDER_ID) {
        stringResource(
            R.string.product_article_number,
            item.articleNumber ?: item.ref.productId,
        )
    } else {
        stringResource(R.string.product_obik, item.ref.productId)
    }

@Composable
private fun manualResultBranchText(
    item: ManualSearchResultItem,
): String =
    if (item.ref.providerId == KWANT_PROVIDER_ID) {
        stringResource(
            R.string.product_branch_kwant_named,
            item.branchLabel ?: item.branchId.value,
        )
    } else {
        stringResource(R.string.product_store, item.branchId.value)
    }

@Composable
private fun ManualVerifiedProductLink(
    product: VerifiedProductUiModel,
) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(
                            verifiedProductOpenUrl(product),
                        ),
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            stringResource(
                if (product.providerId == KWANT_PROVIDER_ID.value) {
                    R.string.open_in_kwant
                } else {
                    R.string.open_in_obi
                },
            ),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            painter = painterResource(R.drawable.ic_open_in_new_24),
            contentDescription = null,
        )
    }
}

@Composable
private fun ErrorText(message: String) {
    val warmColors = MaterialTheme.towarownikColors

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = warmColors.errorMuted,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
        ),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun formatHistoryTimestamp(updatedAt: Long): String =
    Instant.ofEpochMilli(updatedAt)
        .atZone(ZoneId.systemDefault())
        .format(HISTORY_TIME_FORMATTER)

private fun formatLocalTime(createdAt: Long): String =
    Instant.ofEpochMilli(createdAt)
        .atZone(ZoneId.systemDefault())
        .format(CHAT_TIME_FORMATTER)

internal suspend fun runAdvisorForWorkingProfile(
    workingProfile: WorkingProfile,
    onAdvisorStarted: suspend () -> Unit = {},
    runAdvisor: suspend (String, String) -> AdvisorUiState,
): AdvisorUiState =
    when (workingProfile.providerId) {
        OBI_PROVIDER_ID,
        KWANT_PROVIDER_ID -> {
            onAdvisorStarted()
            runAdvisor(
                workingProfile.providerId.value,
                workingProfile.branchId.value,
            )
        }
        else -> AdvisorUiState.Error(AdvisorError.UNSUPPORTED_PROVIDER)
    }

@Composable
private fun advisorErrorText(error: AdvisorError): String =
    stringResource(
        when (error) {
            AdvisorError.NOT_CONFIGURED -> R.string.advisor_error_not_configured
            AdvisorError.AUTHENTICATION -> R.string.advisor_error_authentication
            AdvisorError.NETWORK -> R.string.advisor_error_network
            AdvisorError.SERVICE -> R.string.advisor_error_service
            AdvisorError.PROTOCOL -> R.string.advisor_error_protocol
            AdvisorError.OBI -> R.string.advisor_error_obi
            AdvisorError.INPUT -> R.string.advisor_error_input
            AdvisorError.UNSUPPORTED_PROVIDER ->
                R.string.advisor_error_unsupported_provider
            AdvisorError.PRODUCT_PROVIDER ->
                R.string.advisor_error_product_provider
        },
    )

@Composable
private fun searchErrorText(error: SearchUiError): String =
    stringResource(
        when (error) {
            SearchUiError.INVALID_INPUT -> R.string.search_error_invalid_input
            SearchUiError.NETWORK -> R.string.search_error_network
            SearchUiError.NOT_FOUND -> R.string.search_error_not_found
            SearchUiError.PRODUCT_DATA -> R.string.search_error_product_data
            SearchUiError.SEARCH_DATA -> R.string.search_error_search_data
            SearchUiError.LOOKUP -> R.string.search_error_lookup
        },
    )

private fun AdvisorUiState.isRunning(): Boolean =
    this is AdvisorUiState.LoadingProxy ||
        this is AdvisorUiState.RunningLocalTool ||
        this is AdvisorUiState.WaitingForFinalAnswer

private val CHAT_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
private val HISTORY_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM HH:mm")

@Preview(showBackground = true)
@Composable
private fun AdvisorChatPreview() {
    val context = LocalContext.current
    val previewAttachmentStorage = remember(context) {
        AttachmentStorage(context.applicationContext)
    }
    TowarownikTheme {
        AdvisorChatScreen(
            advisorCase = AdvisorCaseUiState(
                messages = listOf(
                    AdvisorChatMessage(
                        role = ChatMessageRole.USER,
                        text = "Szukam kleju do listew.",
                        createdAt = 0L,
                    ),
                    AdvisorChatMessage(
                        role = ChatMessageRole.ASSISTANT,
                        text = "Sprawdzę dostępne opcje.",
                        createdAt = 0L,
                    ),
                ),
            ),
            state = AdvisorUiState.Idle,
            onDraftChange = {},
            onSubmit = {},
            pendingAttachments = emptyList(),
            attachmentError = null,
            attachmentImporting = false,
            attachmentStorage = previewAttachmentStorage,
            onRemoveAttachment = {},
            onReplaceAttachment = {},
            onOpenCamera = {},
            onOpenPhotos = {},
            onOpenFile = {},
            onOpenProfileSelector = {},
            onOpenDrawer = {},
            onNewCase = {},
            onOpenSearch = {},
            workingProfile = DEFAULT_WORKING_PROFILE,
            profileBranches = emptyList(),
            profileSelectorEnabled = true,
            profileBranchesLoading = false,
            profileMenuRequest = 0,
            onProviderSelected = {},
            onBranchSelected = {},
            onReportAssistantMessage = {},
            onOpenSearchAction = {},
            emptyPromptIndex = 0,
        )
    }
}
