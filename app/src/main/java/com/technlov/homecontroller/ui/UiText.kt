package com.technlov.homecontroller.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.technlov.homecontroller.R
import com.technlov.homecontroller.hub.DeviceIssue

/** Text decided outside the UI (view models, the service) and turned into words with the current resources. */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText

    fun resolve(context: Context): String = when (this) {
        is Raw -> text
        is Res -> if (args.isEmpty()) context.getString(id)
        else context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}

@Composable
fun UiText.asString(): String = resolve(LocalContext.current)

/** A one-off message for a snackbar, with an optional retry. */
data class UiMessage(val text: UiText, val retry: (() -> Unit)? = null)

fun DeviceIssue.toUiText(): UiText = when (this) {
    DeviceIssue.Timeout -> UiText.res(R.string.issue_timeout)
    DeviceIssue.UnknownDevice -> UiText.res(R.string.issue_unknown_device)
    is DeviceIssue.NoConnector -> UiText.res(R.string.issue_no_connector, connectorId)
    is DeviceIssue.Failed -> if (message.isNullOrBlank()) UiText.res(R.string.issue_generic) else UiText.Raw(message)
}
