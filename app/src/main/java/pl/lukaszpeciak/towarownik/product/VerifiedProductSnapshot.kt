package pl.lukaszpeciak.towarownik.product

import java.math.BigDecimal

internal data class VerifiedProductKey(
    val storeNumber: String,
    val obik: String,
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
) {
    val key: VerifiedProductKey
        get() = VerifiedProductKey(
            storeNumber = storeNumber,
            obik = obik,
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
