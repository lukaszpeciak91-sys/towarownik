package pl.lukaszpeciak.towarownik.agent

internal const val ADVISOR_TRACE_HEADER = "X-Taksula-Trace-Id"

private val ADVISOR_TRACE_ID_PATTERN = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
        "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
)

internal fun advisorTraceIdOrNull(value: String?): String? =
    value
        ?.trim()
        ?.takeIf(ADVISOR_TRACE_ID_PATTERN::matches)
        ?.lowercase()
