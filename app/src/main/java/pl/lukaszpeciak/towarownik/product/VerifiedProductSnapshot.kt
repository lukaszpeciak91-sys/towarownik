package pl.lukaszpeciak.towarownik.product

import java.math.BigDecimal

internal data class VerifiedProductSnapshot(
    val obik: String,
    val name: String,
    val stock: Int?,
    val grossPrice: BigDecimal?,
    val productUrl: String,
    val verifiedAt: Long,
)

internal fun LocalProduct.toVerifiedProductSnapshot(
    verifiedAt: Long,
): VerifiedProductSnapshot =
    VerifiedProductSnapshot(
        obik = obik,
        name = name,
        stock = stock,
        grossPrice = grossPrice,
        productUrl = productUrl,
        verifiedAt = verifiedAt,
    )
