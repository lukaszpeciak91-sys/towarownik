package pl.lukaszpeciak.towarownik

internal class AdvisorRequestGuard {
    private var generation: Int = 0

    fun invalidate() {
        generation += 1
    }

    fun token(): Int = generation

    fun isCurrent(
        token: Int,
        expectedConversationId: Long,
        activeConversationId: Long?,
    ): Boolean =
        token == generation &&
            activeConversationId == expectedConversationId
}
