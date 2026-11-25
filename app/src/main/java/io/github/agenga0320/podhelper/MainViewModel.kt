package io.github.agenga0320.podhelper

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PermState(
    val bluetooth: Boolean = false,
    val notifications: Boolean = false,
    val overlay: Boolean = false,
    val batteryUnrestricted: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val pods: StateFlow<PodUiState> = PodRepository.state

    private val _perms = MutableStateFlow(PermState())
    val perms: StateFlow<PermState> = _perms.asStateFlow()

    /** 權限都在系統設定頁改，回到 App（onResume）時重新讀一次。 */
    fun refreshPermissions() {
        val c = getApplication<Application>()
        _perms.value = PermState(
            bluetooth = Permissions.bluetoothGranted(c),
            notifications = Permissions.notificationsGranted(c),
            overlay = Permissions.overlayGranted(c),
            batteryUnrestricted = Permissions.batteryUnrestricted(c),
        )
    }
}
