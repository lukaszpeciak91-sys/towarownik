package pl.lukaszpeciak.towarownik.agent

import pl.lukaszpeciak.towarownik.product.isSupportedObiStoreNumber
import pl.lukaszpeciak.towarownik.product.supportedObiStoreTokens
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID

internal data class AdvisorTurnStoreAuthorization(
    val conversationStoreNumber: String,
    val explicitStoreNumbers: Set<String>,
    val conversationProviderId: String = OBI_PROVIDER_ID.value,
) {
    init {
        require(conversationProviderId.isNotBlank())
        require(conversationStoreNumber.isNotBlank())
        if (conversationProviderId == OBI_PROVIDER_ID.value) {
            require(isSupportedObiStoreNumber(conversationStoreNumber))
        }
    }

    fun isAuthorized(
        providerId: String,
        storeNumber: String,
    ): Boolean {
        if (providerId != conversationProviderId) return false
        return if (providerId == OBI_PROVIDER_ID.value) {
            isSupportedObiStoreNumber(storeNumber) &&
                (
                    storeNumber == conversationStoreNumber ||
                        storeNumber in explicitStoreNumbers
                )
        } else {
            storeNumber == conversationStoreNumber
        }
    }

    fun isAuthorized(storeNumber: String): Boolean =
        isAuthorized(
            providerId = conversationProviderId,
            storeNumber = storeNumber,
        )

    companion object {
        fun capture(
            conversationStoreNumber: String,
            currentUserMessage: String,
            conversationProviderId: String = OBI_PROVIDER_ID.value,
        ): AdvisorTurnStoreAuthorization =
            AdvisorTurnStoreAuthorization(
                conversationStoreNumber = conversationStoreNumber,
                explicitStoreNumbers =
                    if (conversationProviderId == OBI_PROVIDER_ID.value) {
                        supportedObiStoreTokens(currentUserMessage)
                    } else {
                        emptySet()
                    },
                conversationProviderId = conversationProviderId,
            )
    }
}
