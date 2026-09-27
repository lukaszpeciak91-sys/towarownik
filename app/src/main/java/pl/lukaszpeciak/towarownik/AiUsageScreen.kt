package pl.lukaszpeciak.towarownik

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import pl.lukaszpeciak.towarownik.aiusage.AiUsageRepository
import pl.lukaszpeciak.towarownik.aiusage.AiUsageSnapshot
import pl.lukaszpeciak.towarownik.aiusage.CURRENT_AI_MODEL_LABEL
import pl.lukaszpeciak.towarownik.aiusage.NbpUsdPlnRateProvider
import pl.lukaszpeciak.towarownik.aiusage.UsdPlnRate
import pl.lukaszpeciak.towarownik.ui.theme.towarownikColors

@Composable
internal fun AiUsageScreen(
    repository: AiUsageRepository,
    rateProvider: NbpUsdPlnRateProvider,
    onBack: () -> Unit,
    onBudgetChanged: () -> Unit,
) {
    var snapshot by remember {
        mutableStateOf(repository.snapshot())
    }
    var usdPlnRate by remember {
        mutableStateOf<UsdPlnRate?>(null)
    }
    var budgetInput by remember { mutableStateOf("") }
    var budgetInputInvalid by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        usdPlnRate = runCatching {
            rateProvider.loadRate()
        }.getOrNull()
        snapshot = runCatching {
            repository.snapshot()
        }.getOrElse { snapshot }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AiUsageTopBar(onBack = onBack)
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
            UsageCard(
                title = stringResource(R.string.ai_usage_overview),
            ) {
                UsageMetricRow(
                    stringResource(R.string.ai_usage_current_model),
                    CURRENT_AI_MODEL_LABEL,
                )
                UsageMetricRow(
                    stringResource(R.string.ai_usage_tracking_since),
                    formatTrackingSince(snapshot),
                )
                UsageMetricRow(
                    stringResource(R.string.ai_usage_requests),
                    snapshot.requests.toString(),
                )
                UsageMetricRow(
                    stringResource(R.string.ai_usage_turns),
                    snapshot.turns.toString(),
                )
                UsageMetricRow(
                    stringResource(R.string.ai_usage_tool_turns),
                    snapshot.toolAssistedTurns.toString(),
                )
            }

            UsageCard(
                title = stringResource(R.string.ai_usage_tokens),
            ) {
                UsageMetricRow(
                    stringResource(R.string.ai_usage_input_tokens),
                    snapshot.inputTokens.toString(),
                )
                UsageMetricRow(
                    stringResource(R.string.ai_usage_cached_tokens),
                    snapshot.cachedInputTokens?.toString()
                        ?: stringResource(R.string.ai_usage_unavailable),
                )
                UsageMetricRow(
                    stringResource(R.string.ai_usage_output_tokens),
                    snapshot.outputTokens.toString(),
                )
                UsageMetricRow(
                    stringResource(R.string.ai_usage_reasoning_tokens),
                    snapshot.reasoningTokens?.toString()
                        ?: stringResource(R.string.ai_usage_unavailable),
                )
                UsageMetricRow(
                    stringResource(R.string.ai_usage_total_tokens),
                    snapshot.totalTokens.toString(),
                )
            }

            UsageCard(
                title = stringResource(R.string.ai_usage_cost),
            ) {
                UsageMetricRow(
                    stringResource(R.string.ai_usage_cost_usd),
                    formatAdaptiveMoney(
                        snapshot.estimatedCostUsd,
                        "USD",
                    ),
                )
                UsageMetricRow(
                    stringResource(R.string.ai_usage_cost_pln),
                    formatPlnCost(
                        snapshot = snapshot,
                        rate = usdPlnRate,
                    ),
                )
                usdPlnRate?.let { rate ->
                    Text(
                        text = if (rate.isStale) {
                            stringResource(
                                R.string.ai_usage_nbp_rate_cached,
                                rate.effectiveDate,
                            )
                        } else {
                            stringResource(
                                R.string.ai_usage_nbp_rate,
                                rate.effectiveDate,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            UsageCard(
                title = stringResource(R.string.ai_usage_budget_title),
            ) {
                Text(
                    text = stringResource(
                        R.string.ai_usage_budget_explanation,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                snapshot.budget?.let { budget ->
                    UsageMetricRow(
                        stringResource(
                            R.string.ai_usage_budget_configured,
                        ),
                        formatAdaptiveMoney(
                            budget.configuredStartingBudgetUsd,
                            "USD",
                        ),
                    )
                    UsageMetricRow(
                        stringResource(
                            R.string.ai_usage_budget_remaining,
                        ),
                        budget.remainingBudgetUsd?.let {
                            formatAdaptiveMoney(it, "USD")
                        } ?: stringResource(
                            R.string.ai_usage_unavailable,
                        ),
                    )
                }

                OutlinedTextField(
                    value = budgetInput,
                    onValueChange = {
                        budgetInput = it
                        budgetInputInvalid = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text(
                            stringResource(
                                R.string.ai_usage_budget_input,
                            ),
                        )
                    },
                    suffix = { Text("USD") },
                    isError = budgetInputInvalid,
                    supportingText = if (budgetInputInvalid) {
                        {
                            Text(
                                stringResource(
                                    R.string.ai_usage_budget_invalid,
                                ),
                            )
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                    ),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (snapshot.budget != null) {
                        TextButton(
                            onClick = {
                                runCatching {
                                    repository.clearBudget()
                                }.onSuccess {
                                    snapshot = repository.snapshot()
                                    budgetInput = ""
                                    onBudgetChanged()
                                }
                            },
                        ) {
                            Text(
                                stringResource(
                                    R.string.ai_usage_budget_clear,
                                ),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Button(
                        onClick = {
                            val parsed = parseBudgetInput(budgetInput)
                            if (parsed == null) {
                                budgetInputInvalid = true
                            } else {
                                runCatching {
                                    repository.configureBudget(parsed)
                                }.onSuccess {
                                    snapshot = repository.snapshot()
                                    budgetInput = ""
                                    budgetInputInvalid = false
                                    onBudgetChanged()
                                }.onFailure {
                                    budgetInputInvalid = true
                                }
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                R.string.ai_usage_budget_save,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiUsageTopBar(
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
                        painter = painterResource(
                            R.drawable.ic_arrow_back_24,
                        ),
                        contentDescription = stringResource(R.string.cd_back),
                    )
                }
                Text(
                    text = stringResource(R.string.settings_ai_usage),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(
                    alpha = 0.55f,
                ),
            )
        }
    }
}

@Composable
private fun UsageCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val warmColors = MaterialTheme.towarownikColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = warmColors.surfaceRaised,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun UsageMetricRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun formatTrackingSince(
    snapshot: AiUsageSnapshot,
): String =
    snapshot.trackingStartedAt?.let {
        TRACKING_DATE_FORMATTER.format(
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()),
        )
    } ?: stringResource(R.string.ai_usage_not_started)

@Composable
private fun formatPlnCost(
    snapshot: AiUsageSnapshot,
    rate: UsdPlnRate?,
): String {
    if (rate == null) {
        return stringResource(R.string.ai_usage_unavailable)
    }
    return formatAdaptiveMoney(
        convertUsdToPln(
            snapshot.estimatedCostUsd,
            rate.rate,
        ),
        "PLN",
    )
}

internal fun convertUsdToPln(
    usd: BigDecimal,
    usdPlnRate: BigDecimal,
): BigDecimal = usd * usdPlnRate

internal fun parseBudgetInput(raw: String): BigDecimal? =
    raw.trim()
        .replace(',', '.')
        .takeIf { it.isNotBlank() }
        ?.toBigDecimalOrNull()
        ?.takeIf {
            it.signum() >= 0 &&
                it <= MAX_CONFIGURED_BUDGET_USD
        }

internal fun formatAdaptiveMoney(
    value: BigDecimal,
    currencyCode: String,
): String {
    if (value.signum() == 0) {
        return "$currencyCode 0"
    }

    val absolute = value.abs()
    val scale = when {
        absolute >= BigDecimal.ONE -> 4
        absolute >= BigDecimal("0.01") -> 6
        else -> 9
    }
    val rounded = value.setScale(scale, RoundingMode.HALF_UP)
    if (rounded.signum() == 0) {
        val minimum = BigDecimal.ONE
            .movePointLeft(scale)
            .toPlainString()
        return "$currencyCode <$minimum"
    }
    return "$currencyCode " +
        rounded.stripTrailingZeros().toPlainString()
}

private val TRACKING_DATE_FORMATTER =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private val MAX_CONFIGURED_BUDGET_USD =
    BigDecimal("1000000")
