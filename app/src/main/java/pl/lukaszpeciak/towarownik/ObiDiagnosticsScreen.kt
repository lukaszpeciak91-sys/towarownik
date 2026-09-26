package pl.lukaszpeciak.towarownik

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.lukaszpeciak.towarownik.diagnostics.OBI_PROBE_CANONICAL_PRODUCT_URL
import pl.lukaszpeciak.towarownik.diagnostics.OBI_PROBE_SEARCH_URL
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnostics
import pl.lukaszpeciak.towarownik.diagnostics.ObiLiveProbeRunner

@Composable
internal fun ObiDiagnosticsScreen(
    onBack: () -> Unit,
) {
    val recorder = ObiDiagnostics.recorder
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packageVersion = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }
    val nativeUserAgent = remember(packageVersion) {
        "Towarownik/$packageVersion (Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT})"
    }
    val probeRunner = remember(nativeUserAgent) {
        ObiLiveProbeRunner(nativeUserAgent = nativeUserAgent)
    }
    var enabled by remember { mutableStateOf(recorder.isEnabled()) }
    var probeRunning by remember { mutableStateOf(false) }
    var probeStatus by remember { mutableStateOf<String?>(null) }
    var reportRevision by remember { mutableIntStateOf(0) }
    val report = remember(enabled, reportRevision) { recorder.report() }

    BackHandler(onBack = onBack)

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Diagnostyka OBI",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = if (enabled) "Tryb diagnostyczny: ON" else "Tryb diagnostyczny: OFF",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { value ->
                        recorder.setEnabled(value)
                        enabled = value
                        reportRevision += 1
                    },
                )
            }

            Text(
                text = "Raport przechowuje maksymalnie 10 ostatnich operacji tylko w tej sesji. " +
                    "Nie zapisuje wartości cookies ani treści odpowiedzi.",
                style = MaterialTheme.typography.bodySmall,
            )

            Button(
                onClick = {
                    probeRunning = true
                    probeStatus = null
                    scope.launch {
                        runCatching { probeRunner.run() }
                            .onSuccess { probe ->
                                recorder.setLiveProbeReport(probe)
                                probeStatus = "Test OBI zakończony."
                            }
                            .onFailure { error ->
                                probeStatus = "Test OBI nie powiódł się: ${error.javaClass.simpleName}"
                            }
                        probeRunning = false
                        reportRevision += 1
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !probeRunning,
            ) {
                Text(if (probeRunning) "Test OBI trwa…" else "Uruchom test OBI")
            }

            probeStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(OBI_PROBE_SEARCH_URL)),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Otwórz Dedra w przeglądarce")
            }

            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(OBI_PROBE_CANONICAL_PRODUCT_URL)),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Otwórz produkt 3496072 w przeglądarce")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText("Towarownik OBI diagnostics", recorder.report()),
                        )
                        reportRevision += 1
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Kopiuj raport")
                }

                Button(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Towarownik — diagnostyka OBI")
                            putExtra(Intent.EXTRA_TEXT, recorder.report())
                        }
                        context.startActivity(
                            Intent.createChooser(shareIntent, "Udostępnij raport"),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Udostępnij raport")
                }
            }

            OutlinedButton(
                onClick = {
                    recorder.clear()
                    reportRevision += 1
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Wyczyść raport")
            }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Powrót")
            }

            SelectionContainer {
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
