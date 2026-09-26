package pl.lukaszpeciak.towarownik

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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

private enum class AppMode {
    SEARCH,
    ADVISOR,
}

@Composable
private fun TowarownikApp() {
    val searchController = remember { ProductSearchController() }
    val advisorController = remember { AdvisorController.production() }
    val scope = rememberCoroutineScope()

    var diagnosticsOpen by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(AppMode.SEARCH) }

    var query by rememberSaveable { mutableStateOf("") }
    var searchState by remember {
        mutableStateOf<ProductSearchUiState>(ProductSearchUiState.Idle)
    }
    var lookupJob by remember { mutableStateOf<Job?>(null) }

    var advisorInput by rememberSaveable { mutableStateOf("") }
    var advisorState by remember {
        mutableStateOf<AdvisorUiState>(AdvisorUiState.Idle)
    }
    var advisorJob by remember { mutableStateOf<Job?>(null) }

    fun resetLookup() {
        lookupJob?.cancel()
        lookupJob = null
        query = ""
        searchState = ProductSearchUiState.Idle
    }

    fun submitLookup() {
        val submission = prepareSearchSubmission(query)
        lookupJob?.cancel()
        query = submission.nextVisibleQuery
        lookupJob = scope.launch {
            searchController.submit(submission.submittedQuery) { state ->
                searchState = state
            }
        }
    }

    fun selectResult(item: SearchResultItem) {
        lookupJob?.cancel()
        lookupJob = scope.launch {
            searchController.select(item) { state ->
                searchState = state
            }
        }
    }

    fun resetAdvisorCase() {
        advisorJob?.cancel()
        advisorJob = null
        advisorInput = ""
        advisorState = AdvisorUiState.Idle
    }

    fun submitAdvisorCase() {
        if (advisorJob?.isActive == true) return
        val submittedCase = advisorInput
        advisorJob = scope.launch {
            advisorController.runCase(submittedCase) { state ->
                advisorState = state
            }
        }
    }

    fun openSearch() {
        advisorJob?.cancel()
        advisorJob = null
        if (advisorState.isRunning()) {
            advisorState = AdvisorUiState.Idle
        }
        mode = AppMode.SEARCH
    }

    fun openAdvisor() {
        val wasRunning = lookupJob?.isActive == true
        lookupJob?.cancel()
        lookupJob = null
        if (wasRunning) {
            searchState = ProductSearchUiState.Idle
        }
        mode = AppMode.ADVISOR
    }

    if (diagnosticsOpen) {
        ObiDiagnosticsScreen(
            onBack = { diagnosticsOpen = false },
        )
        return
    }

    when (mode) {
        AppMode.SEARCH -> SearchScreen(
            query = query,
            state = searchState,
            onQueryChange = { value ->
                query = value
                if (lookupJob?.isActive != true) {
                    searchState = ProductSearchUiState.Idle
                }
            },
            onSearch = ::submitLookup,
            onSelectResult = ::selectResult,
            onClear = ::resetLookup,
            onOpenDiagnostics = { diagnosticsOpen = true },
            onOpenAdvisor = ::openAdvisor,
        )

        AppMode.ADVISOR -> AdvisorScreen(
            input = advisorInput,
            state = advisorState,
            onInputChange = { value ->
                advisorInput = value
                if (!advisorState.isRunning()) {
                    advisorState = AdvisorUiState.Idle
                }
            },
            onSubmit = ::submitAdvisorCase,
            onNewCase = ::resetAdvisorCase,
            onOpenSearch = ::openSearch,
            onOpenDiagnostics = {
                advisorJob?.cancel()
                advisorJob = null
                if (advisorState.isRunning()) {
                    advisorState = AdvisorUiState.Idle
                }
                diagnosticsOpen = true
            },
        )
    }
}

@Composable
private fun SearchScreen(
    query: String,
    state: ProductSearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectResult: (SearchResultItem) -> Unit,
    onClear: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAdvisor: () -> Unit,
) {
    val isLoading = state is ProductSearchUiState.Loading

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        ScreenColumn(innerPadding = innerPadding) {
            AppTitle(onOpenDiagnostics = onOpenDiagnostics)
            ModeSelector(
                current = AppMode.SEARCH,
                onSearch = {},
                onAdvisor = onOpenAdvisor,
            )

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
                    onSearch = { if (!isLoading) onSearch() },
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
                ProductSearchUiState.Idle -> Unit

                ProductSearchUiState.Loading -> ProgressRow("Szukam produktu…")

                is ProductSearchUiState.SearchResults -> SearchResults(
                    state = state,
                    onSelectResult = onSelectResult,
                )

                is ProductSearchUiState.Success -> ProductResult(state)

                is ProductSearchUiState.Error -> ErrorText(state.message)
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AdvisorScreen(
    input: String,
    state: AdvisorUiState,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onNewCase: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val isRunning = state.isRunning()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        ScreenColumn(innerPadding = innerPadding) {
            AppTitle(onOpenDiagnostics = onOpenDiagnostics)
            ModeSelector(
                current = AppMode.ADVISOR,
                onSearch = onOpenSearch,
                onAdvisor = {},
            )

            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Opisz czego potrzebuje klient") },
                minLines = 4,
                maxLines = 7,
                enabled = !isRunning,
            )

            Button(
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRunning && input.isNotBlank(),
            ) {
                Text("Zapytaj")
            }

            when (state) {
                AdvisorUiState.Idle -> Unit
                AdvisorUiState.LoadingProxy -> ProgressRow("Łączę z doradcą…")
                AdvisorUiState.RunningLocalTool -> ProgressRow("Sprawdzam OBI…")
                AdvisorUiState.WaitingForFinalAnswer ->
                    ProgressRow("Przygotowuję odpowiedź…")

                is AdvisorUiState.Success -> Text(
                    text = state.text,
                    style = MaterialTheme.typography.bodyLarge,
                )

                is AdvisorUiState.Error -> ErrorText(state.message)
            }

            if (state !is AdvisorUiState.Idle) {
                OutlinedButton(
                    onClick = onNewCase,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning,
                ) {
                    Text("Nowa sprawa")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ScreenColumn(
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun AppTitle(
    onOpenDiagnostics: () -> Unit,
) {
    Text(
        text = "Towarownik",
        modifier = Modifier.pointerInput(onOpenDiagnostics) {
            detectTapGestures(
                onLongPress = { onOpenDiagnostics() },
            )
        },
        style = MaterialTheme.typography.headlineMedium,
    )
}

@Composable
private fun ModeSelector(
    current: AppMode,
    onSearch: () -> Unit,
    onAdvisor: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (current == AppMode.SEARCH) {
            Button(onClick = {}) {
                Text("WYSZUKIWARKA")
            }
        } else {
            OutlinedButton(onClick = onSearch) {
                Text("WYSZUKIWARKA")
            }
        }

        if (current == AppMode.ADVISOR) {
            Button(onClick = {}) {
                Text("DORADCA")
            }
        } else {
            OutlinedButton(onClick = onAdvisor) {
                Text("DORADCA")
            }
        }
    }
}

@Composable
private fun ProgressRow(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(text)
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

@Composable
private fun SearchResults(
    state: ProductSearchUiState.SearchResults,
    onSelectResult: (SearchResultItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Wyniki",
            style = MaterialTheme.typography.titleMedium,
        )
        state.items.forEach { item ->
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
    }
}

@Composable
private fun ProductResult(state: ProductSearchUiState.Success) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = state.name,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = state.stock?.let { "Stan Nowy Sącz: $it szt." }
                ?: "Stan Nowy Sącz: brak danych",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = state.grossPrice?.let { "Cena Nowy Sącz: ${it.toPlainString()} zł" }
                ?: "Cena Nowy Sącz: brak danych",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private fun AdvisorUiState.isRunning(): Boolean =
    this is AdvisorUiState.LoadingProxy ||
        this is AdvisorUiState.RunningLocalTool ||
        this is AdvisorUiState.WaitingForFinalAnswer

@Preview(showBackground = true)
@Composable
private fun SearchScreenPreview() {
    TowarownikTheme {
        SearchScreen(
            query = "",
            state = ProductSearchUiState.Idle,
            onQueryChange = {},
            onSearch = {},
            onSelectResult = {},
            onClear = {},
            onOpenDiagnostics = {},
            onOpenAdvisor = {},
        )
    }
}
