package io.github.agenga0320.podhelper

import io.github.agenga0320.podhelper.ble.PodStatus
import kotlinx.coroutines.flow.MutableStateFlow

data class PodUiState(
    val pods: PodStatus? = null,
    val rssi: Int? = null,
    /** 耳機都不在盒內時，廣播不帶盒子電量，改顯示最後一次收到的值。 */
    val lastCaseBattery: Int? = null,
    val classicConnected: Boolean = false,
    val error: String? = null,
) {
    val caseBattery: Int? get() = pods?.caseBattery ?: lastCaseBattery
    val caseBatteryIsStale: Boolean get() = pods?.caseBattery == null && lastCaseBattery != null

    fun shortText(): String {
        val p = pods ?: return "附近沒有偵測到 AirPods"
        fun f(v: Int?, charging: Boolean) = (v?.let { "$it%" } ?: "--") + if (charging) "⚡" else ""
        return "左 ${f(p.leftBattery, p.leftCharging)} · 右 ${f(p.rightBattery, p.rightCharging)} · 盒 ${f(caseBattery, p.caseCharging)}"
    }
}

/** Service 寫入，畫面、浮動卡片、Widget 讀取。只在同一個 process 內共用。 */
object PodRepository {
    val state = MutableStateFlow(PodUiState())
}
