package pl.lukaszpeciak.towarownik

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.lukaszpeciak.towarownik.ui.theme.towarownikColors

internal data class ProblemReportFormSubmission(
    val category: ProblemReportCategory,
    val description: String,
    val includeConversation: Boolean,
    val includeObiDiagnostics: Boolean,
)

@Composable
internal fun ProblemReportScreen(
    type: ProblemReportType,
    canIncludeConversation: Boolean,
    canIncludeObiDiagnostics: Boolean,
    onBack: () -> Unit,
    onCreateAndShare: suspend (
        ProblemReportFormSubmission,
    ) -> ProblemReportUiError?,
) {
    var selectedCategoryName by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var description by rememberSaveable {
        mutableStateOf("")
    }
    var includeConversation by rememberSaveable {
        mutableStateOf(REPORT_INCLUDE_CONVERSATION_DEFAULT)
    }
    var includeObiDiagnostics by rememberSaveable {
        mutableStateOf(REPORT_INCLUDE_OBI_DIAGNOSTICS_DEFAULT)
    }
    var error by rememberSaveable {
        mutableStateOf<ProblemReportUiError?>(null)
    }
    var creating by rememberSaveable {
        mutableStateOf(false)
    }
    val scope = rememberCoroutineScope()
    val warmColors = MaterialTheme.towarownikColors
    val selectedCategory = selectedCategoryName?.let { raw ->
        ProblemReportCategory.entries.firstOrNull {
            it.name == raw && it.reportType == type
        }
    }
    val descriptionRequired =
        type == ProblemReportType.GENERAL && description.isBlank()
    val canSubmit =
        selectedCategory != null &&
            !descriptionRequired &&
            !creating

    BackHandler(enabled = !creating, onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ProblemReportTopBar(
                onBack = onBack,
                enabled = !creating,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.report_category_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = warmColors.surfaceRaised,
            ) {
                Column {
                    reportCategories(type).forEachIndexed { index, category ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !creating) {
                                    selectedCategoryName = category.name
                                    error = null
                                }
                                .padding(
                                    horizontal = 10.dp,
                                    vertical = 6.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedCategory == category,
                                enabled = !creating,
                                onClick = {
                                    selectedCategoryName = category.name
                                    error = null
                                },
                            )
                            Text(
                                text = stringResource(category.labelRes),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        if (index < reportCategories(type).lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline
                                    .copy(alpha = 0.45f),
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = description,
                onValueChange = { value ->
                    description = value.take(REPORT_DESCRIPTION_MAX_CHARS)
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !creating,
                minLines = 4,
                maxLines = 8,
                shape = RoundedCornerShape(16.dp),
                label = {
                    Text(
                        stringResource(
                            if (type == ProblemReportType.GENERAL) {
                                R.string.report_description_required
                            } else {
                                R.string.report_description_optional
                            },
                        ),
                    )
                },
                supportingText = {
                    Text(
                        stringResource(
                            R.string.report_description_count,
                            description.length,
                            REPORT_DESCRIPTION_MAX_CHARS,
                        ),
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = warmColors.surfaceRaised,
                    unfocusedContainerColor = warmColors.surfaceRaised,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )

            if (canIncludeConversation) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = warmColors.surfaceRaised,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !creating) {
                                includeConversation = !includeConversation
                                error = null
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = includeConversation,
                            enabled = !creating,
                            onCheckedChange = { checked ->
                                includeConversation = checked
                                error = null
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (type == ProblemReportType.ASSISTANT_RESPONSE) {
                                    R.string.report_include_conversation
                                } else {
                                    R.string.report_include_current_conversation
                                },
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            if (canIncludeObiDiagnostics) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = warmColors.surfaceRaised,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !creating) {
                                includeObiDiagnostics = !includeObiDiagnostics
                                error = null
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = includeObiDiagnostics,
                            enabled = !creating,
                            onCheckedChange = { checked ->
                                includeObiDiagnostics = checked
                                error = null
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.report_include_obi_diagnostics,
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(
                                    R.string.report_include_obi_diagnostics_hint,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = warmColors.surfaceRaised,
            ) {
                Text(
                    text = reportPrivacyDisclosure(
                        type = type,
                        includeConversation =
                            canIncludeConversation && includeConversation,
                        includeObiDiagnostics =
                            canIncludeObiDiagnostics &&
                                includeObiDiagnostics,
                    ),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            error?.let {
                Text(
                    text = reportErrorText(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                text = stringResource(R.string.report_screenshot_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    val category = selectedCategory ?: return@Button
                    scope.launch {
                        creating = true
                        error = null
                        error = onCreateAndShare(
                            ProblemReportFormSubmission(
                                category = category,
                                description = description.trim(),
                                includeConversation =
                                    canIncludeConversation &&
                                        includeConversation,
                                includeObiDiagnostics =
                                    canIncludeObiDiagnostics &&
                                        includeObiDiagnostics,
                            ),
                        )
                        creating = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSubmit,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_report_problem_24),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (creating) {
                            R.string.report_creating
                        } else {
                            R.string.report_create_and_share
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun ProblemReportTopBar(
    onBack: () -> Unit,
    enabled: Boolean,
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
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back_24),
                        contentDescription = stringResource(R.string.cd_back),
                    )
                }
                Text(
                    text = stringResource(R.string.report_screen_title),
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
private fun reportPrivacyDisclosure(
    type: ProblemReportType,
    includeConversation: Boolean,
    includeObiDiagnostics: Boolean,
): String {
    val base = stringResource(
        when (type) {
            ProblemReportType.ASSISTANT_RESPONSE ->
                if (includeConversation) {
                    R.string.report_privacy_assistant_with_conversation
                } else {
                    R.string.report_privacy_assistant_without_conversation
                }

            ProblemReportType.GENERAL ->
                if (includeConversation) {
                    R.string.report_privacy_general_with_conversation
                } else {
                    R.string.report_privacy_general_without_conversation
                }
        },
    )
    val diagnostics = stringResource(
        if (includeObiDiagnostics) {
            R.string.report_privacy_obi_diagnostics_included
        } else {
            R.string.report_privacy_obi_diagnostics_not_included
        },
    )
    return "$base\n\n$diagnostics"
}

@Composable
private fun reportErrorText(
    error: ProblemReportUiError,
): String =
    stringResource(
        when (error) {
            ProblemReportUiError.TARGET_UNAVAILABLE ->
                R.string.report_error_target_unavailable
            ProblemReportUiError.DESCRIPTION_REQUIRED ->
                R.string.report_error_description_required
            ProblemReportUiError.GENERATION_FAILED ->
                R.string.report_error_generation_failed
            ProblemReportUiError.SHARE_UNAVAILABLE ->
                R.string.report_error_share_unavailable
        },
    )
