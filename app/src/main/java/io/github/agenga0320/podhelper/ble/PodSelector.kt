package io.github.agenga0320.podhelper.ble

/** 一次收到的廣播。address 是 BLE 隨機位址，會定期變動，也和經典藍牙的 MAC 不同。 */
data class Sighting(val address: String, val rssi: Int, val status: PodStatus, val timeMs: Long)

/**
 * 從附近所有 AirPods 廣播中挑出「我的」那一副。純 Kotlin。
 *
 * 限制：公開廣播裡沒有能對到經典藍牙裝置的識別資訊（要靠 AAP 取得的金鑰解 IRK），
 * 所以只能用「訊號強度」加「耳機目前有連著某台裝置」來推斷。
 * shortcut: 旁邊有人戴著同型號、而且更靠近手機的 AirPods 時仍可能選錯；要根治需實作 IRK 比對（第二階段有 AAP 後）。
 */
object PodSelector {
    /** 手機沒有連著任何 AirPods 時（例如剛打開盒蓋）：只接受貼近手機的耳機。 */
    const val RSSI_NEARBY = -65
    /** 手機已透過經典藍牙連著 AirPods：耳機戴在頭上，距離較遠，放寬門檻。 */
    const val RSSI_CONNECTED = -85
    const val MAX_AGE_MS = 10_000L

    fun pick(sightings: Collection<Sighting>, nowMs: Long, classicConnected: Boolean): Sighting? {
        val minRssi = if (classicConnected) RSSI_CONNECTED else RSSI_NEARBY
        return sightings
            .filter { nowMs - it.timeMs <= MAX_AGE_MS && it.rssi >= minRssi }
            .maxWithOrNull(
                compareBy<Sighting>(
                    // 手機已連線時，優先選「耳機自己說有連著裝置」的那一副
                    { classicConnected && it.status.isConnectedToSomething },
                    { it.rssi },
                ),
            )
    }
}
