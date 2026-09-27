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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.lukaszpeciak.towarownik.ui.theme.towarownikColors

internal const val REPORT_PROBLEM_AVAILABLE = false
internal const val PRIVACY_POLICY_AVAILABLE = false

@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val currentLanguage = appLanguageForTag(
        configuration.locales[0]?.language,
    )
    var languageDialogOpen by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SettingsTopBar(onBack = onBack)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SettingsSectionTitle(
                text = stringResource(R.string.settings_section_general),
            )
            SettingsRow(
                iconRes = R.drawable.ic_language_24,
                title = stringResource(R.string.settings_language),
                value = stringResource(currentLanguage.labelRes),
                selected = true,
                onClick = {
                    languageDialogOpen = true
                },
            )

            SettingsSectionTitle(
                text = stringResource(R.string.settings_section_help),
            )
            SettingsRow(
                iconRes = R.drawable.ic_diagnostics_24,
                title = stringResource(R.string.settings_diagnostics),
                onClick = onOpenDiagnostics,
            )
            SettingsRow(
                iconRes = R.drawable.ic_report_problem_24,
                title = stringResource(R.string.settings_report_problem),
                value = stringResource(R.string.settings_coming_soon),
                enabled = REPORT_PROBLEM_AVAILABLE,
            )

            SettingsSectionTitle(
                text = stringResource(R.string.settings_section_about),
            )
            SettingsIdentityCard()
            SettingsRow(
                iconRes = R.drawable.ic_privacy_24,
                title = stringResource(R.string.settings_privacy_policy),
                value = stringResource(R.string.settings_coming_soon),
                enabled = PRIVACY_POLICY_AVAILABLE,
            )
        }
    }

    if (languageDialogOpen) {
        LanguageSelectionDialog(
            currentLanguage = currentLanguage,
            onDismiss = {
                languageDialogOpen = false
            },
            onSelect = { language ->
                languageDialogOpen = false
                setApplicationLanguage(language)
            },
        )
    }
}

@Composable
private fun SettingsTopBar(
    onBack: () -> Unit,
) {
    Surface(
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
                    text = stringResource(R.string.settings),
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
private fun SettingsSectionTitle(
    text: String,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsRow(
    iconRes: Int,
    title: String,
    value: String? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val warmColors = MaterialTheme.towarownikColors
    val rowModifier = if (enabled && onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.58f),
        shape = RoundedCornerShape(16.dp),
        color = warmColors.surfaceRaised,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = rowModifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            value?.let {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsIdentityCard() {
    val warmColors = MaterialTheme.towarownikColors

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = warmColors.surfaceRaised,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_info_24),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(
                        R.string.settings_version,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LanguageSelectionDialog(
    currentLanguage: AppLanguage,
    onDismiss: () -> Unit,
    onSelect: (AppLanguage) -> Unit,
) {
    val warmColors = MaterialTheme.towarownikColors

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = warmColors.surfaceRaised,
        title = {
            Text(stringResource(R.string.settings_language))
        },
        text = {
            Column {
                AppLanguage.entries.forEach { language ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(language)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = language == currentLanguage,
                            onClick = {
                                onSelect(language)
                            },
                        )
                        Text(
                            text = stringResource(language.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
