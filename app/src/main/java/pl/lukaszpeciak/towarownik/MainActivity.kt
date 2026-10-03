package pl.lukaszpeciak.towarownik

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import pl.lukaszpeciak.towarownik.product.SUPPORTED_OBI_STORE_NUMBERS
import pl.lukaszpeciak.towarownik.product.isSupportedObiStoreNumber
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
    val advisorController = remember { AdvisorController.production() }
    val providerRegistry = remember { ProductProviderRegistry.production() }
    val manualSearchController = remember {
        ManualSearchController(providers = providerRegistry)
    }
    val workingProfileRepository = remember {
        WorkingProfileRepository.production(context)
    }
    val conversationRepository = remember {
        ConversationRepository(
            ConversationDatabase.get(context).conversationDao(),
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
    var selectedStoreNumber by rememberSaveable {
        mutableStateOf(selectedWorkingProfile.branchId.value)
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
    var advisorJob by remember { mutableStateOf<Job?>(null) }
    var showAiBudgetWarning by rememberSaveable {
        mutableStateOf(false)
    }
    var draftPersistJob by remember { mutableStateOf<Job?>(null) }
    var storePersistJob by remember { mutableStateOf<Job?>(null) }
    val advisorRequestGuard = remember { AdvisorRequestGuard() }

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
            selectedStoreNumber = selectedWorkingProfile.branchId.value
            advisorCase = AdvisorCaseUiState()
            return
        }

        activeConversationId = conversation.id
        selectedWorkingProfile = conversation.workingProfile
        selectedStoreNumber = conversation.workingProfile.branchId.value
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
        storePersistJob?.join()
        storePersistJob = null

        if (recoverInterrupted) {
            activeConversationId?.let { conversationId ->
                conversationRepository.recoverInterruptedTurn(conversationId)
            }
        }
    }

    fun newAdvisorCase() {
        scope.launch {
            cancelAndRecoverActiveTurn()
            clearManualProfileContext()
            freshCaseSelected = true
            emptyPromptIndex = (emptyPromptIndex + 1) % EMPTY_ADVISOR_PROMPTS.size
            activeConversationId = null
            selectedWorkingProfile = globalWorkingProfile
            selectedStoreNumber = globalWorkingProfile.branchId.value
            advisorState = AdvisorUiState.Idle
            advisorCase = AdvisorCaseUiState()
        }
    }

    fun openConversation(conversationId: Long) {
        scope.launch {
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
                cancelAndRecoverActiveTurn(
                    recoverInterrupted = false,
                )
            }

            conversationRepository.deleteConversation(conversationId)

            if (freshCase != null) {
                clearManualProfileContext()
                activeConversationId = null
                selectedWorkingProfile = globalWorkingProfile
                selectedStoreNumber = globalWorkingProfile.branchId.value
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
        selectedStoreNumber = profile.branchId.value
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
        if (advisorJob?.isActive == true) return

        val submitted = advisorCase.draft.trim()
        if (submitted.isBlank()) return
        val turnProfile = selectedWorkingProfile

        val generation = advisorRequestGuard.token()
        advisorJob = scope.launch {
            draftPersistJob?.cancelAndJoin()
            draftPersistJob = null
            storePersistJob?.join()
            storePersistJob = null

            val turn = conversationRepository.beginUserTurn(
                conversationId = activeConversationId,
                text = submitted,
                workingProfile = turnProfile,
            )

            if (!advisorRequestGuard.isTokenCurrent(generation)) {
                conversationRepository.recoverInterruptedTurn(
                    turn.conversationId,
                )
                return@launch
            }

            activeConversationId = turn.conversationId
            freshCaseSelected = false
            conversationRepository.load(turn.conversationId)
                ?.let(::applyConversation)

            runCatching {
                aiUsageRepository.recordTurnStarted()
            }
            var toolAssistedRecorded = false

            val finalState = advisorController.runTurn(
                input = submitted,
                previousResponseId = turn.previousResponseId,
                conversationStoreNumber = turn.workingProfile.branchId.value,
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
                    val recovered = conversationRepository
                        .recoverInterruptedTurn(turn.conversationId)
                    if (
                        advisorRequestGuard.isCurrent(
                            token = generation,
                            expectedConversationId = turn.conversationId,
                            activeConversationId = activeConversationId,
                        )
                    ) {
                        applyConversation(recovered)
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
                    onOpenDrawer = {
                        scope.launch { drawerState.open() }
                    },
                    onNewCase = ::newAdvisorCase,
                    onOpenSearch = ::openManualSearch,
                    workingProfile = selectedWorkingProfile,
                    profileBranches = profileBranches,
                    profileSelectorEnabled =
                        activeConversationId == null &&
                            advisorJob?.isActive != true,
                    profileBranchesLoading = profileBranchesLoading,
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
            ManualObiSearchScreen(
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
                                (advisorState as? AdvisorUiState.Error)
                                    ?.diagnostic
                                    ?.reportValue(),
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
    onOpenDrawer: () -> Unit,
    onNewCase: () -> Unit,
    onOpenSearch: () -> Unit,
    workingProfile: WorkingProfile,
    profileBranches: List<ProviderBranch>,
    profileSelectorEnabled: Boolean,
    profileBranchesLoading: Boolean,
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
                onProviderSelected = onProviderSelected,
                onBranchSelected = onBranchSelected,
            )
        },
        bottomBar = {
            AdvisorComposer(
                value = advisorCase.draft,
                enabled = composerEnabled,
                onValueChange = onDraftChange,
                onSend = onSubmit,
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
                                    R.string.advisor_progress_checking_obi,
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
    onProviderSelected: (ProviderId) -> Unit,
    onBranchSelected: (ProviderBranch) -> Unit,
) {
    var providerExpanded by remember { mutableStateOf(false) }
    var branchExpanded by remember { mutableStateOf(false) }
    val providerLabel = if (workingProfile.providerId == KWANT_PROVIDER_ID) {
        stringResource(R.string.provider_kwant)
    } else {
        stringResource(R.string.provider_obi)
    }
    val branch = branches.firstOrNull {
        it.branchId == workingProfile.branchId
    }
    val branchLabel = branch?.name ?: workingProfile.branchId.value

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
                                Text(option.name)
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
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val warmColors = MaterialTheme.towarownikColors

    Surface(
        modifier = Modifier.bottomComposerSafeArea(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    minLines = 1,
                    maxLines = 4,
                    shape = RoundedCornerShape(18.dp),
                    placeholder = {
                        Text(
                            text = stringResource(
                                R.string.advisor_composer_placeholder,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = warmColors.surfaceRaised,
                        unfocusedContainerColor = warmColors.surfaceRaised,
                        disabledContainerColor = warmColors.surfaceRaised,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        disabledBorderColor = MaterialTheme.colorScheme.outline.copy(
                            alpha = 0.55f,
                        ),
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (enabled && value.isNotBlank()) {
                                onSend()
                            }
                        },
                    ),
                )

                FilledIconButton(
                    onClick = onSend,
                    enabled = enabled && value.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.outline.copy(
                            alpha = 0.45f,
                        ),
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_send_24),
                        contentDescription = stringResource(
                            R.string.cd_send_message,
                        ),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

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
private fun AdvisorMessageBubble(
    message: AdvisorChatMessage,
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
                Text(
                    text = message.text,
                    modifier = Modifier.padding(
                        horizontal = 14.dp,
                        vertical = 10.dp,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
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
private fun ManualObiSearchScreen(
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
        Text(stringResource(R.string.open_in_obi))
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
            onOpenDrawer = {},
            onNewCase = {},
            onOpenSearch = {},
            workingProfile = DEFAULT_WORKING_PROFILE,
            profileBranches = emptyList(),
            profileSelectorEnabled = true,
            profileBranchesLoading = false,
            onProviderSelected = {},
            onBranchSelected = {},
            onReportAssistantMessage = {},
            onOpenSearchAction = {},
            emptyPromptIndex = 0,
        )
    }
}
