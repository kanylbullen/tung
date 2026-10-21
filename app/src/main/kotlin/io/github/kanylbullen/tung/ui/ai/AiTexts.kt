package io.github.kanylbullen.tung.ui.ai

import androidx.annotation.StringRes
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ai.AiFailure

/** What the screen says for a failed call; the provider's own message, if any, goes under it. */
@StringRes
fun aiFailureText(failure: AiFailure): Int = when (failure) {
    AiFailure.NOT_CONFIGURED -> R.string.ai_not_configured
    AiFailure.NETWORK -> R.string.ai_error_network
    AiFailure.TIMEOUT -> R.string.ai_error_timeout
    AiFailure.UNAUTHORIZED -> R.string.ai_error_unauthorized
    AiFailure.NOT_FOUND -> R.string.ai_error_not_found
    AiFailure.RATE_LIMITED -> R.string.ai_error_rate_limited
    AiFailure.REJECTED -> R.string.ai_error_rejected
    AiFailure.SERVER -> R.string.ai_error_server
    AiFailure.NO_ANSWER -> R.string.ai_error_no_answer
    AiFailure.OUT_OF_ROOM -> R.string.ai_error_out_of_room
    AiFailure.DECLINED -> R.string.ai_error_declined
    AiFailure.UNREADABLE -> R.string.ai_error_unreadable
    AiFailure.NOTHING_USABLE -> R.string.ai_error_nothing_usable
}
