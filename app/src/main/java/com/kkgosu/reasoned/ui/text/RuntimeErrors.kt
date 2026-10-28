package com.kkgosu.reasoned.ui.text

import android.content.Context
import com.kkgosu.reasoned.R
import com.kkgosu.reasoned.api.ChatGptAuth

/** Actionable plan errors without exposing provider diagnostics or changing the session's billing. */
internal fun Context.runtimeError(reason: String): String = when (reason) {
    "subscription_sharing_usage_limit_exceeded" -> getString(R.string.chatgpt_error_limit)
    "subscription_sharing_user_not_eligible" -> getString(R.string.chatgpt_error_eligibility)
    "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable", "chatgpt_http_503" -> getString(
        R.string.chatgpt_error_unavailable
    )

    "subscription_sharing_unsupported_capability", "subscription_sharing_route_not_supported" -> getString(
        R.string.chatgpt_error_unsupported
    )

    "chatgpt_plan_permission_required", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> getString(
        R.string.chatgpt_error_permission
    )

    "chatgpt_sign_in_required", in ChatGptAuth.TERMINAL_REFRESH -> getString(R.string.chatgpt_error_sign_in)
    "chatgpt_sign_in_timeout" -> getString(R.string.chatgpt_error_timeout)
    "chatgpt_models_failed" -> getString(R.string.chatgpt_models_failed)
    "chatgpt_response_limit" -> getString(R.string.chatgpt_error_response_limit)
    else -> getString(R.string.runtime_error, reason)
}
