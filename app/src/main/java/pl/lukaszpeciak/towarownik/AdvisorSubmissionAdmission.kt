package pl.lukaszpeciak.towarownik

import kotlinx.coroutines.Job

/**
 * Synchronous admission predicate for Advisor submission.
 * The caller still owns the Job assignment and all persistence/network effects.
 */
internal fun canSubmitAdvisorTurn(
    activeJob: Job?,
    attachmentImporting: Boolean,
): Boolean = activeJob?.isActive != true && !attachmentImporting
