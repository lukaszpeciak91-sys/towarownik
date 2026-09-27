package pl.lukaszpeciak.towarownik

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot

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

internal fun formatVerifiedProductTimestamp(
    verifiedAt: Long,
): String =
    "Sprawdzono " + Instant.ofEpochMilli(verifiedAt)
        .atZone(ZoneId.systemDefault())
        .format(VERIFIED_AT_FORMATTER)

@Composable
internal fun VerifiedProductCard(
    product: VerifiedProductUiModel,
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = product.name,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "OBIK: ${product.obik}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = formatStore075Price(product.grossPrice),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = formatStore075Stock(product.stock),
                style = MaterialTheme.typography.bodyLarge,
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
            ) {
                Text("Otwórz w OBI")
            }
        }
    }
}


private val VERIFIED_AT_FORMATTER =
    DateTimeFormatter.ofPattern("dd.MM, HH:mm")
