package pl.lukaszpeciak.towarownik

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import coil3.compose.AsyncImage
import java.time.format.DateTimeFormatter
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.ui.theme.towarownikColors

internal data class VerifiedProductUiModel(
    val name: String,
    val obik: String,
    val grossPrice: BigDecimal?,
    val stock: Int?,
    val productUrl: String,
    val verifiedAt: Long? = null,
    val storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
    val primaryImageUrl: String? = null,
)

internal fun VerifiedProductSnapshot.toVerifiedProductUiModel():
    VerifiedProductUiModel =
    VerifiedProductUiModel(
        name = name,
        obik = obik,
        grossPrice = grossPrice,
        stock = stock,
        productUrl = productUrl,
        primaryImageUrl = primaryImageUrl,
        verifiedAt = verifiedAt,
        storeNumber = storeNumber,
    )

internal fun verifiedProductOpenUrl(
    product: VerifiedProductUiModel,
): String = product.productUrl

internal fun stockStringRes(stock: Int?): Int = when (stock) {
    null -> R.string.product_stock_unknown
    0 -> R.string.product_stock_zero
    else -> R.string.product_stock_count
}

internal fun priceStringRes(price: BigDecimal?): Int =
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
internal fun formatStoreStock(stock: Int?): String {
    val resource = stockStringRes(stock)
    return if (stock != null && stock > 0) {
        stringResource(resource, stock)
    } else {
        stringResource(resource)
    }
}

@Composable
internal fun formatStorePrice(price: BigDecimal?): String {
    val resource = priceStringRes(price)
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
    val accentColor = MaterialTheme.colorScheme.primary
    val stockColor = when {
        product.stock == null -> MaterialTheme.colorScheme.onSurfaceVariant
        product.stock == 0 -> MaterialTheme.colorScheme.error
        else -> warmColors.success
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                drawRect(
                    color = accentColor,
                    size = Size(3.dp.toPx(), size.height),
                )
            },
        shape = RoundedCornerShape(18.dp),
        color = warmColors.surfaceRaised,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(
            modifier = Modifier.padding(
                start = 18.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VerifiedProductThumbnail(
                product = product,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
            )
            SelectionContainer {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.product_store,
                            product.storeNumber,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(
                            R.string.product_obik,
                            product.obik,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(
                    alpha = 0.65f,
                ),
            )

            Text(
                text = formatStorePrice(product.grossPrice),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = formatStoreStock(product.stock),
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
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(stringResource(R.string.open_in_obi))
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_open_in_new_24),
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
internal fun VerifiedProductThumbnail(
    product: VerifiedProductUiModel,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(96.dp),
) {
    val imageUrl = product.primaryImageUrl ?: return
    var loadFailed by remember(imageUrl) {
        mutableStateOf(false)
    }
    if (loadFailed) return

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(
            alpha = 0.35f,
        ),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = product.name,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentScale = ContentScale.Fit,
            onError = {
                loadFailed = true
            },
        )
    }
}

private val VERIFIED_AT_FORMATTER =
    DateTimeFormatter.ofPattern("dd.MM, HH:mm")
