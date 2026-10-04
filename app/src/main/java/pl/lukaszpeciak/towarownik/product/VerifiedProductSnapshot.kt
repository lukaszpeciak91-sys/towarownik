package pl.lukaszpeciak.towarownik.product

import java.math.BigDecimal
import pl.lukaszpeciak.towarownik.product.provider.ProviderPriceScope

internal data class VerifiedProductKey(
    val storeNumber: String,
    val obik: String,
    val providerId: String = "obi-pl",
)

internal data class VerifiedProductSnapshot(
    val obik: String,
    val name: String,
    val stock: Int?,
    val grossPrice: BigDecimal?,
    val productUrl: String,
    val verifiedAt: Long,
    val storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
    val primaryImageUrl: String? = null,
    val providerId: String = "obi-pl",
    val productId: String = obik,
    val branchId: String = storeNumber,
    val articleNumber: String? = null,
    val priceScope: ProviderPriceScope? = null,
) {
    val effectiveBranchId: String
        get() = if (providerId == "obi-pl") storeNumber else branchId

    val effectiveProductId: String
        get() = if (providerId == "obi-pl") obik else productId

    val key: VerifiedProductKey
        get() = VerifiedProductKey(
            storeNumber = effectiveBranchId,
            obik = effectiveProductId,
            providerId = providerId,
        )
}

internal fun LocalProduct.toVerifiedProductSnapshot(
    verifiedAt: Long,
): VerifiedProductSnapshot =
    VerifiedProductSnapshot(
        obik = obik,
        name = name,
        stock = stock,
        grossPrice = grossPrice,
        productUrl = productUrl,
        primaryImageUrl = primaryImageUrl,
        verifiedAt = verifiedAt,
        storeNumber = storeNumber,
    )
