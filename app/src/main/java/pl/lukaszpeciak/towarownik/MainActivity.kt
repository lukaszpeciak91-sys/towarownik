package pl.lukaszpeciak.towarownik

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pl.lukaszpeciak.towarownik.ui.theme.TowarownikTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TowarownikTheme {
                TowarownikApp()
            }
        }
    }
}

@Composable
private fun TowarownikApp() {
    val controller = remember { ObikLookupController() }
    val scope = rememberCoroutineScope()
    var obik by rememberSaveable { mutableStateOf("") }
    var uiState by remember { mutableStateOf<ObikLookupUiState>(ObikLookupUiState.Idle) }
    var lookupJob by remember { mutableStateOf<Job?>(null) }

    fun resetLookup() {
        lookupJob?.cancel()
        lookupJob = null
        obik = ""
        uiState = ObikLookupUiState.Idle
    }

    fun submitLookup() {
        lookupJob?.cancel()
        lookupJob = scope.launch {
            controller.submit(obik) { state ->
                uiState = state
            }
        }
    }

    TowarownikScreen(
        obik = obik,
        state = uiState,
        onObikChange = { value ->
            lookupJob?.cancel()
            lookupJob = null
            obik = value
            uiState = ObikLookupUiState.Idle
        },
        onSearch = ::submitLookup,
        onClear = ::resetLookup,
    )
}

@Composable
private fun TowarownikScreen(
    obik: String,
    state: ObikLookupUiState,
    onObikChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    val isLoading = state is ObikLookupUiState.Loading

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
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
            ) {
                Text(
                    text = "Towarownik",
                    style = MaterialTheme.typography.headlineMedium,
                )

                OutlinedTextField(
                    value = obik,
                    onValueChange = onObikChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("OBIK") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = { if (!isLoading) onSearch() },
                    ),
                    trailingIcon = if (obik.isNotEmpty()) {
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
                    ObikLookupUiState.Idle -> Unit

                    ObikLookupUiState.Loading -> Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text("Szukam produktu…")
                    }

                    is ObikLookupUiState.Success -> ProductResult(state)

                    is ObikLookupUiState.Error -> Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ProductResult(state: ObikLookupUiState.Success) {
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

@Preview(showBackground = true)
@Composable
private fun TowarownikScreenPreview() {
    TowarownikTheme {
        TowarownikScreen(
            obik = "",
            state = ObikLookupUiState.Idle,
            onObikChange = {},
            onSearch = {},
            onClear = {},
        )
    }
}
