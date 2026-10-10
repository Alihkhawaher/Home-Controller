package com.technlov.homecontroller.ui.automations

import com.technlov.home.core.DeviceKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.technlov.home.core.Automation
import com.technlov.homecontroller.AppContainer
import com.technlov.homecontroller.R
import com.technlov.homecontroller.data.HomeLocation
import com.technlov.homecontroller.data.HomeRepository
import com.technlov.homecontroller.ui.UiMessage
import com.technlov.homecontroller.ui.UiText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

data class AutomationsUi(
    val loaded: Boolean = false,
    val automations: List<Automation> = emptyList(),
    val devices: List<DeviceOption> = emptyList(),
    val lastRuns: Map<String, LocalDateTime> = emptyMap(),
    /** Sunrise and sunset automations wait for the home's location. */
    val hasHomeLocation: Boolean = true,
)

class AutomationsViewModel(private val container: AppContainer) : ViewModel() {
    val ui: StateFlow<AutomationsUi> = combine(container.repository.data, container.hub.live) { data, live ->
        AutomationsUi(true, data.automations, deviceOptions(data, live), data.lastRuns, data.settings.homeLocation != null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutomationsUi())

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { container.repository.setAutomationEnabled(id, enabled) }
    }
}

class AutomationEditorViewModel(
    private val container: AppContainer,
    private val automationId: String?,
    /** A new automation opened from a device's "Add schedule": starts as that device's schedule. */
    private val prefillDevice: DeviceKey? = null,
) : ViewModel() {
    private val _draft = MutableStateFlow<AutomationDraft?>(null)
    val draft: StateFlow<AutomationDraft?> = _draft.asStateFlow()

    val devices: StateFlow<List<DeviceOption>> = combine(container.repository.data, container.hub.live) { data, live ->
        deviceOptions(data, live)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The home's location, for sunrise and sunset triggers (null: not set yet). */
    val homeLocation: StateFlow<HomeLocation?> = container.settings.map { it.homeLocation }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), container.settings.value.homeLocation)

    fun setHomeLocation(location: HomeLocation?) {
        viewModelScope.launch { container.repository.updateSettings { it.copy(homeLocation = location) } }
    }

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: Flow<UiMessage> = _messages

    init {
        viewModelScope.launch {
            val data = container.repository.current()
            val existing = automationId?.let { data.automation(it) }
            val forDevice = prefillDevice?.let { key -> deviceOptions(data, container.hub.live.value).firstOrNull { it.key == key } }
            _draft.value = existing?.let { AutomationDraft.from(it) }
                ?: forDevice?.let { AutomationDraft.newSchedule(HomeRepository.newId(), it) }
                ?: AutomationDraft(id = HomeRepository.newId(), isNew = true)
        }
    }

    fun update(transform: (AutomationDraft) -> AutomationDraft) = _draft.update { it?.let(transform) }

    fun save(fallbackName: String, onSaved: () -> Unit) {
        val automation = _draft.value?.toAutomation(fallbackName) ?: return
        viewModelScope.launch {
            container.repository.saveAutomation(automation)
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = _draft.value?.id ?: return
        viewModelScope.launch {
            container.repository.deleteAutomation(id)
            onDeleted()
        }
    }

    /** Runs the actions now, to try the automation out. */
    fun runNow(fallbackName: String) {
        val automation = _draft.value?.toAutomation(fallbackName) ?: return
        viewModelScope.launch {
            _messages.tryEmit(UiMessage(UiText.res(R.string.message_automation_running, automation.name)))
            container.hub.execute(automation)
        }
    }
}
