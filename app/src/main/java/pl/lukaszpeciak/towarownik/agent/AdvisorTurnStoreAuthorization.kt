package pl.lukaszpeciak.towarownik.agent

import pl.lukaszpeciak.towarownik.product.isSupportedObiStoreNumber
import pl.lukaszpeciak.towarownik.product.supportedObiStoreTokens

internal data class AdvisorTurnStoreAuthorization(
    val conversationStoreNumber: String,
    val explicitStoreNumbers: Set<String>,
) {
    init {
        require(isSupportedObiStoreNumber(conversationStoreNumber))
    }

    fun isAuthorized(storeNumber: String): Boolean =
        isSupportedObiStoreNumber(storeNumber) &&
            (
                storeNumber == conversationStoreNumber ||
                    storeNumber in explicitStoreNumbers
            )

    companion object {
        fun capture(
            conversationStoreNumber: String,
            currentUserMessage: String,
        ): AdvisorTurnStoreAuthorization =
            AdvisorTurnStoreAuthorization(
                conversationStoreNumber = conversationStoreNumber,
                explicitStoreNumbers =
                    supportedObiStoreTokens(currentUserMessage),
            )
    }
}
