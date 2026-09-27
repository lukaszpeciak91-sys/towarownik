package pl.lukaszpeciak.towarownik

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.ui.theme.towarownikColors

internal data class VerifiedProductUiModel(
    val name: String,
    val obik: String,
    val grossPrice: BigDecimal?,
    val stock: Int?,
    val productUrl: String,
    val verifiedAt: Long? = null,
)

internal fun VerifiedProductSnapshot.toVerifiedProductUiModel():
    VerifiedProductUiModel =
    VerifiedProductUiModel(
        name = name,
        obik = obik,
        grossPrice = grossPrice,
        stock = stock,
        productUrl = productUrl,
        verifiedAt = verifiedAt,
    )

internal fun verifiedProductOpenUrl(
    product: VerifiedProductUiModel,
): String = product.productUrl

internal fun store075StockStringRes(stock: Int?): Int = when (stock) {
    null -> R.string.product_stock_unknown
    0 -> R.string.product_stock_zero
    else -> R.string.product_stock_count
}

internal fun store075PriceStringRes(price: BigDecimal?): Int =
    if (price == null) {
        R.string.product_price_unknown
    } else {
        R.string.product_price
    }

internal fun formatVerifiedProductTimestampValue(
    verifiedAt: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String =
    Instant.ofEpochMilli(verifiedAt)
        .atZone(zoneId)
        .format(VERIFIED_AT_FORMATTER)

@Composable
internal fun formatStore075Stock(stock: Int?): String {
    val resource = store075StockStringRes(stock)
    return if (stock != null && stock > 0) {
        stringResource(resource, stock)
    } else {
        stringResource(resource)
    }
}

@Composable
internal fun formatStore075Price(price: BigDecimal?): String {
    val resource = store075PriceStringRes(price)
    return price?.let {
        stringResource(resource, it.toPlainString())
    } ?: stringResource(resource)
}

@Composable
internal fun formatVerifiedProductTimestamp(
    verifiedAt: Long,
): String =
    stringResource(
        R.string.product_verified_at,
        formatVerifiedProductTimestampValue(verifiedAt),
    )

@Composable
internal fun VerifiedProductCard(
    product: VerifiedProductUiModel,
) {
    val context = LocalContext.current
    val warmColors = MaterialTheme.towarownikColors
    val shape = RoundedCornerShape(18.dp)
    val stockColor = when {
        product.stock == null -> MaterialTheme.colorScheme.onSurfaceVariant
        product.stock == 0 -> MaterialTheme.colorScheme.error
        else -> warmColors.success
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = warmColors.surfaceRaised,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline,
        ),
    ) {
        Box {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRect(
                            color = MaterialTheme.colorScheme.primary,
                            size = Size(3.dp.toPx(), size.height),
                        )
                    },
            )

            Column(
                modifier = Modifier.padding(
                    start = 18.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.product_obik, product.obik),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(
                        alpha = 0.65f,
                    ),
                )

                Text(
                    text = formatStore075Price(product.grossPrice),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = formatStore075Stock(product.stock),
                    style = MaterialTheme.typography.bodyLarge,
                    color = stockColor,
                )

                product.verifiedAt?.let { verifiedAt ->
                    Text(
                        text = formatVerifiedProductTimestamp(verifiedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(verifiedProductOpenUrl(product)),
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline,
                    ),
                    colors = androidx.compose.material3.ButtonDefaults
                        .outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                ) {
                    Text(stringResource(R.string.open_in_obi))
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.padding(horizontal = 3.dp),
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_open_in_new_24),
                        contentDescription = null,
                    )
                }
            }
        }
    }
}

private val VERIFIED_AT_FORMATTER =
    DateTimeFormatter.ofPattern("dd.MM, HH:mm")
