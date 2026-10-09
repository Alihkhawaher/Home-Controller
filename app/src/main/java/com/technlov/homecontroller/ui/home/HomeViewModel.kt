package com.technlov.homecontroller.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.technlov.home.core.DeviceKey
import com.technlov.home.core.Keys
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value
import com.technlov.homecontroller.AppContainer
import com.technlov.homecontroller.R
import com.technlov.homecontroller.hub.DeviceIssue
import com.technlov.homecontroller.ui.UiMessage
import com.technlov.homecontroller.ui.UiText
import com.technlov.homecontroller.ui.toUiText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val showHidden = MutableStateFlow(false)

    val ui: StateFlow<HomeUi> = combine(container.repository.data, container.hub.live, showHidden) { data, live, hidden ->
        buildHomeUi(data, live, hidden)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: Flow<UiMessage> = _messages

    /** Pull to refresh: reads every device now. Lasts at least a moment so the gesture feels answered. */
    suspend fun refresh() {
        val started = System.currentTimeMillis()
        container.hub.refresh()
        val elapsed = System.currentTimeMillis() - started
        if (elapsed < MIN_REFRESH_MS) delay(MIN_REFRESH_MS - elapsed)
    }

    fun setPower(key: DeviceKey, name: String, on: Boolean) {
        viewModelScope.launch {
            container.hub.apply(key, listOf(SetValue(Keys.POWER, Value.Bool(on)))).onFailure { error ->
                _messages.tryEmit(
                    UiMessage(UiText.res(R.string.message_power_failed, name, DeviceIssue.from(error).toUiText())) {
                        setPower(key, name, on)
                    },
                )
            }
        }
    }

    fun setShowHidden(show: Boolean) {
        showHidden.value = show
    }

    private companion object {
        const val MIN_REFRESH_MS = 700L
    }
}
