package pl.lukaszpeciak.towarownik

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pl.lukaszpeciak.towarownik.agent.AdvisorController
import pl.lukaszpeciak.towarownik.agent.AdvisorUiState
import pl.lukaszpeciak.towarownik.diagnostics.DiagnosticDeviceContext
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnostics
import pl.lukaszpeciak.towarownik.ui.theme.TowarownikTheme

class MainActivity : ComponentActivity() {
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

private enum class AppSurface {
    ADVISOR,
    MANUAL_SEARCH,
}

@Composable
private fun TowarownikApp() {
    val advisorController = remember { AdvisorController.production() }
    val manualSearchController = remember { ManualSearchController() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
    var surfaceName by rememberSaveable {
        mutableStateOf(AppSurface.ADVISOR.name)
    }
    val surface = runCatching { AppSurface.valueOf(surfaceName) }
        .getOrDefault(AppSurface.ADVISOR)

    var advisorCase by rememberSaveable(
        stateSaver = AdvisorCaseUiStateSaver,
    ) {
        mutableStateOf(AdvisorCaseUiState())
    }
    var advisorState by remember {
        mutableStateOf<AdvisorUiState>(AdvisorUiState.Idle)
    }
    var advisorJob by remember { mutableStateOf<Job?>(null) }

    var manualQuery by rememberSaveable { mutableStateOf("") }
    var manualState by rememberSaveable(
        stateSaver = ManualSearchUiStateSaver,
    ) {
        mutableStateOf<ManualSearchUiState>(ManualSearchUiState.Idle)
    }
    var manualJob by remember { mutableStateOf<Job?>(null) }
    var drawerQuery by rememberSaveable { mutableStateOf("") }

    fun newAdvisorCase() {
        advisorJob?.cancel()
        advisorJob = null
        advisorState = AdvisorUiState.Idle
        advisorCase = advisorCase.newCase()
    }

    fun submitAdvisorCase() {
        if (
            advisorJob?.isActive == true ||
            advisorCase.messages.isNotEmpty()
        ) {
            return
        }

        val submitted = advisorCase.draft.trim()
        if (submitted.isBlank()) return

        advisorCase = advisorCase
            .withDraft("")
            .withMessage(
                AdvisorChatMessage(
                    role = ChatMessageRole.USER,
                    text = submitted,
                    createdAt = System.currentTimeMillis(),
                ),
            )

        advisorJob = scope.launch {
            advisorController.runCase(submitted) { state ->
                advisorState = state
                if (state is AdvisorUiState.Success) {
                    val displayText = normalizeAdvisorDisplayText(state.text)
                    advisorCase = advisorCase.withMessage(
                        AdvisorChatMessage(
                            role = ChatMessageRole.ASSISTANT,
                            text = displayText,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    fun submitManualSearch() {
        val submission = prepareSearchSubmission(manualQuery)
        manualJob?.cancel()
        manualQuery = submission.nextVisibleQuery
        manualJob = scope.launch {
            manualSearchController.submit(submission.submittedQuery) { state ->
                manualState = state
            }
        }
    }

    fun selectManualResult(item: ManualSearchResultItem) {
        manualJob?.cancel()
        manualJob = scope.launch {
            manualSearchController.select(item) { state ->
                manualState = state
            }
        }
    }

    fun openManualSearch() {
        surfaceName = AppSurface.MANUAL_SEARCH.name
    }

    fun closeManualSearch() {
        surfaceName = AppSurface.ADVISOR.name
    }

    if (diagnosticsOpen) {
        ObiDiagnosticsScreen(
            onBack = { diagnosticsOpen = false },
        )
        return
    }

    when (surface) {
        AppSurface.ADVISOR -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ConversationDrawer(
                        query = drawerQuery,
                        onQueryChange = { drawerQuery = it },
                        onNewConversation = {
                            newAdvisorCase()
                            scope.launch { drawerState.close() }
                        },
                    )
                },
            ) {
                AdvisorChatScreen(
                    advisorCase = advisorCase,
                    state = advisorState,
                    onDraftChange = {
                        if (advisorCase.messages.isEmpty()) {
                            advisorCase = advisorCase.withDraft(it)
                        }
                    },
                    onSubmit = ::submitAdvisorCase,
                    onOpenDrawer = {
                        scope.launch { drawerState.open() }
                    },
                    onNewCase = ::newAdvisorCase,
                    onOpenSearch = ::openManualSearch,
                    onOpenDiagnostics = {
                        diagnosticsOpen = true
                    },
                )
            }
        }

        AppSurface.MANUAL_SEARCH -> {
            BackHandler(onBack = ::closeManualSearch)
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
                onShowMore = {
                    val current = manualState
                    if (current is ManualSearchUiState.SearchResults) {
                        manualState = current.showMore()
                    }
                },
                onBack = ::closeManualSearch,
            )
        }
    }
}

@Composable
private fun ConversationDrawer(
    query: String,
    onQueryChange: (String) -> Unit,
    onNewConversation: () -> Unit,
) {
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Towarownik",
                style = MaterialTheme.typography.titleLarge,
            )

            Button(
                onClick = onNewConversation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("+ Nowa rozmowa")
            }

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Przeszukaj rozmowy...") },
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.TopStart,
            ) {
                Text(
                    text = if (query.isBlank()) {
                        "Brak zapisanych rozmów."
                    } else {
                        "Historia rozmów nie jest jeszcze dostępna."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AdvisorChatScreen(
    advisorCase: AdvisorCaseUiState,
    state: AdvisorUiState,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNewCase: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val isRunning = state.isRunning()
    val composerEnabled = advisorCase.messages.isEmpty() && !isRunning

    Scaffold(
        topBar = {
            AdvisorTopBar(
                onOpenDrawer = onOpenDrawer,
                onNewCase = onNewCase,
                onOpenSearch = onOpenSearch,
                onOpenDiagnostics = onOpenDiagnostics,
            )
        },
        bottomBar = {
            AdvisorComposer(
                value = advisorCase.draft,
                enabled = composerEnabled,
                hasSubmittedMessage = advisorCase.messages.isNotEmpty(),
                onValueChange = onDraftChange,
                onSend = onSubmit,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp),
                contentPadding = PaddingValues(
                    horizontal = 16.dp,
                    vertical = 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (advisorCase.messages.isEmpty() && state is AdvisorUiState.Idle) {
                    item {
                        EmptyAdvisorState()
                    }
                }

                items(advisorCase.messages) { message ->
                    AdvisorMessageBubble(message)
                }

                when (state) {
                    AdvisorUiState.Idle,
                    is AdvisorUiState.Success -> Unit

                    AdvisorUiState.LoadingProxy -> item {
                        AdvisorProgressBubble("Łączę z doradcą…")
                    }

                    AdvisorUiState.RunningLocalTool -> item {
                        AdvisorProgressBubble("Sprawdzam OBI…")
                    }

                    AdvisorUiState.WaitingForFinalAnswer -> item {
                        AdvisorProgressBubble("Przygotowuję odpowiedź…")
                    }

                    is AdvisorUiState.Error -> item {
                        AdvisorErrorBubble(state.message)
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
    onOpenDiagnostics: () -> Unit,
) {
    Surface(shadowElevation = 2.dp) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Text(
                    text = "☰",
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Text(
                text = "Towarownik",
                modifier = Modifier
                    .align(Alignment.Center)
                    .pointerInput(onOpenDiagnostics) {
                        detectTapGestures(
                            onLongPress = { onOpenDiagnostics() },
                        )
                    },
                style = MaterialTheme.typography.titleLarge,
            )

            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                IconButton(onClick = onNewCase) {
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                IconButton(onClick = onOpenSearch) {
                    Text(
                        text = "⌕",
                        style = MaterialTheme.typography.headlineSmall,
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
    hasSubmittedMessage: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(shadowElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
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
                placeholder = {
                    Text(
                        if (hasSubmittedMessage) {
                            "Nowa rozmowa, aby zadać kolejne pytanie"
                        } else {
                            "Opisz czego potrzebuje klient…"
                        },
                    )
                },
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

            IconButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
            ) {
                Text(
                    text = "➤",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}

@Composable
private fun EmptyAdvisorState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Doradca",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Opisz, czego potrzebuje klient.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AdvisorMessageBubble(message: AdvisorChatMessage) {
    val isUser = message.role == ChatMessageRole.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) {
            Alignment.End
        } else {
            Alignment.Start
        },
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 10.dp,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            text = formatLocalTime(message.createdAt),
            modifier = Modifier.padding(
                horizontal = 6.dp,
                vertical = 2.dp,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AdvisorProgressBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 10.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(text)
            }
        }
    }
}

@Composable
private fun AdvisorErrorBubble(message: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 10.dp,
            ),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
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
    onBack: () -> Unit,
) {
    val isLoading = state is ManualSearchUiState.Loading

    Scaffold(
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
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("OBIK / EAN / nazwa") },
                    singleLine = true,
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
                            IconButton(onClick = onClear) {
                                Text(
                                    text = "×",
                                    style = MaterialTheme.typography.titleLarge,
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
                ) {
                    Text("Szukaj")
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
                        ErrorText(state.message)
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
    Surface(shadowElevation = 2.dp) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Text(
                    text = "←",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Text(
                text = "Wyszukiwarka OBI",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun ManualSearchProgress() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text("Szukam produktu…")
    }
}

@Composable
private fun ManualSearchResults(
    state: ManualSearchUiState.SearchResults,
    onSelectResult: (ManualSearchResultItem) -> Unit,
    onShowMore: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Wyniki 1–${state.visibleItems.size}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "OBI raportuje: ${state.reportedTotalCount} wyników",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.visibleItems.forEach { item ->
            OutlinedButton(
                onClick = { onSelectResult(item) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start,
                ) {
                    item.name?.let { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Text(
                        text = "OBIK: ${item.obik}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (state.canShowMore) {
            OutlinedButton(
                onClick = onShowMore,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Pokaż więcej")
            }
        }
    }
}

@Composable
internal fun VerifiedProductCard(
    product: VerifiedProductUiModel,
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = product.name,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "OBIK: ${product.obik}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = formatStore075Price(product.grossPrice),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = formatStore075Stock(product.stock),
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(product.productUrl),
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Otwórz w OBI")
            }
        }
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyLarge,
    )
}

private fun formatLocalTime(createdAt: Long): String =
    Instant.ofEpochMilli(createdAt)
        .atZone(ZoneId.systemDefault())
        .format(CHAT_TIME_FORMATTER)

private fun AdvisorUiState.isRunning(): Boolean =
    this is AdvisorUiState.LoadingProxy ||
        this is AdvisorUiState.RunningLocalTool ||
        this is AdvisorUiState.WaitingForFinalAnswer

private val CHAT_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

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
            onOpenDiagnostics = {},
        )
    }
}
