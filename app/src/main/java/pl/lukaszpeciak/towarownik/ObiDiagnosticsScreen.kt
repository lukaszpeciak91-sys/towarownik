package pl.lukaszpeciak.towarownik

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnostics

@Composable
internal fun ObiDiagnosticsScreen(
    onBack: () -> Unit,
) {
    val recorder = ObiDiagnostics.recorder
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(recorder.isEnabled()) }
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
