package io.github.kanylbullen.tung.ui.ai

import io.github.kanylbullen.tung.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import io.github.kanylbullen.tung.ai.AiFailure

/** What the screen says for a failed call; the provider's own message, if any, goes under it. */
fun aiFailureText(failure: AiFailure): StringResource = when (failure) {
    AiFailure.NOT_CONFIGURED -> Res.string.ai_not_configured
    AiFailure.NETWORK -> Res.string.ai_error_network
    AiFailure.TIMEOUT -> Res.string.ai_error_timeout
    AiFailure.UNAUTHORIZED -> Res.string.ai_error_unauthorized
    AiFailure.NOT_FOUND -> Res.string.ai_error_not_found
    AiFailure.RATE_LIMITED -> Res.string.ai_error_rate_limited
    AiFailure.REJECTED -> Res.string.ai_error_rejected
    AiFailure.SERVER -> Res.string.ai_error_server
    AiFailure.NO_ANSWER -> Res.string.ai_error_no_answer
    AiFailure.OUT_OF_ROOM -> Res.string.ai_error_out_of_room
    AiFailure.DECLINED -> Res.string.ai_error_declined
    AiFailure.UNREADABLE -> Res.string.ai_error_unreadable
    AiFailure.NOTHING_USABLE -> Res.string.ai_error_nothing_usable
}
