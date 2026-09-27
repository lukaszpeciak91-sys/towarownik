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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.lukaszpeciak.towarownik.diagnostics.OBI_PROBE_CANONICAL_PRODUCT_URL
import pl.lukaszpeciak.towarownik.diagnostics.OBI_PROBE_SEARCH_URL
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnostics
import pl.lukaszpeciak.towarownik.diagnostics.ObiLiveProbeRunner

private sealed interface ProbeUiStatus {
    data object Success : ProbeUiStatus
    data class Failure(val reason: String) : ProbeUiStatus
}

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
    var probeStatus by remember { mutableStateOf<ProbeUiStatus?>(null) }
    var reportRevision by remember { mutableIntStateOf(0) }
    val report = remember(enabled, reportRevision) { recorder.report() }
    val clipboardLabel = stringResource(R.string.diagnostics_clipboard_label)
    val shareSubject = stringResource(R.string.diagnostics_share_subject)
    val shareChooserTitle = stringResource(R.string.diagnostics_share_chooser)

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
                        text = stringResource(R.string.diagnostics_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = stringResource(
                            if (enabled) {
                                R.string.diagnostics_mode_on
                            } else {
                                R.string.diagnostics_mode_off
                            },
                        ),
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
                text = stringResource(R.string.diagnostics_retention_notice),
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
                                probeStatus = ProbeUiStatus.Success
                            }
                            .onFailure { error ->
                                probeStatus = ProbeUiStatus.Failure(error.javaClass.simpleName)
                            }
                        probeRunning = false
                        reportRevision += 1
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !probeRunning,
            ) {
                Text(
                    stringResource(
                        if (probeRunning) {
                            R.string.diagnostics_probe_running
                        } else {
                            R.string.diagnostics_run_probe
                        },
                    ),
                )
            }

            probeStatus?.let { status ->
                Text(
                    text = when (status) {
                        ProbeUiStatus.Success ->
                            stringResource(R.string.diagnostics_probe_success)
                        is ProbeUiStatus.Failure ->
                            stringResource(
                                R.string.diagnostics_probe_failure,
                                status.reason,
                            )
                    },
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
                Text(stringResource(R.string.diagnostics_open_dedra))
            }

            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(OBI_PROBE_CANONICAL_PRODUCT_URL)),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.diagnostics_open_product))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText(clipboardLabel, recorder.report()),
                        )
                        reportRevision += 1
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.diagnostics_copy_report))
                }

                Button(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, shareSubject)
                            putExtra(Intent.EXTRA_TEXT, recorder.report())
                        }
                        context.startActivity(
                            Intent.createChooser(shareIntent, shareChooserTitle),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.diagnostics_share_report))
                }
            }

            OutlinedButton(
                onClick = {
                    recorder.clear()
                    reportRevision += 1
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.diagnostics_clear_report))
            }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.back))
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
