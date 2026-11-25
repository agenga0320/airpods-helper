package io.github.agenga0320.podhelper.ble

/*
 * AirPods「Proximity Pairing」BLE 廣播解析。純 Kotlin，不依賴 Android，方便單元測試。
 *
 * 出處（以下兩個專案都是 GPL-3.0，本檔為參考後自行改寫）：
 *  [CAPod-CP]  d4rken-org/capod  app/src/main/java/eu/darken/capod/pods/core/apple/ble/protocol/ContinuityProtocol.kt
 *  [CAPod-PP]  d4rken-org/capod  .../pods/core/apple/ble/protocol/ProximityPairing.kt
 *  [CAPod-AP]  d4rken-org/capod  .../pods/core/apple/ble/devices/ApplePods.kt
 *  [CAPod-DP]  d4rken-org/capod  .../pods/core/apple/ble/devices/DualApplePods.kt
 *  [CAPod-T]   d4rken-org/capod  app/src/test/.../ble/devices/DualApplePodsTest.kt（連線狀態位元組）
 *  [LP-BLE]    kavishdevar/librepods  android/app/src/main/java/me/kavishdevar/librepods/bluetooth/BLEManager.kt
 *
 * 只解析公開（未加密）部分。加密部分需要先透過 AAP 取得金鑰，第一階段不做，
 * 所以電量解析度是 10%。
 */

enum class LidState { OPEN, CLOSED, NOT_IN_CASE, UNKNOWN }

data class PodStatus(
    val modelId: Int,
    /** 0–100，以 10 為單位；null = 這顆耳機沒有回報（例如不在附近）。 */
    val leftBattery: Int?,
    val rightBattery: Int?,
    /** 只有至少一顆耳機在盒內時才會回報，否則為 null。 */
    val caseBattery: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
    val leftInEar: Boolean,
    val rightInEar: Boolean,
    /** true = 左耳是 primary。 */
    val primaryLeft: Boolean,
    val anyPodInCase: Boolean,
    val lid: LidState,
    /** 0x00 = 沒有連到任何裝置；其他值表示耳機正連著某台裝置（不一定是這台手機）。 */
    val connectionState: Int,
) {
    val modelName: String get() = MODEL_NAMES[modelId] ?: "AirPods (0x%04X)".format(modelId)
    val isConnectedToSomething: Boolean get() = connectionState != 0x00
}

// [LP-BLE] modelNames。AirPods Max 是單體耳機，格式不同，不在這裡。
private val MODEL_NAMES = mapOf(
    0x0220 to "AirPods 1",
    0x0F20 to "AirPods 2",
    0x1320 to "AirPods 3",
    0x1920 to "AirPods 4",
    0x1B20 to "AirPods 4 (ANC)",
    0x0E20 to "AirPods Pro",
    0x1420 to "AirPods Pro 2",
    0x2420 to "AirPods Pro 2 (USB-C)",
)
private val SINGLE_POD_MODELS = setOf(0x0A20, 0x1F20) // [LP-BLE] AirPods Max / Max (USB-C)

object ProximityParser {

    const val APPLE_COMPANY_ID = 0x004C            // [CAPod-CP] APPLE_COMPANY_IDENTIFIER
    const val TYPE_PROXIMITY_PAIRING = 0x07         // [CAPod-PP] CONTINUITY_PROTOCOL_MESSAGE_TYPE_PROXIMITY_PAIRING
    const val PAIRING_MESSAGE_LENGTH = 25           // [CAPod-PP] PAIRING_MESSAGE_LENGTH（Apple 原廠耳機）
    private const val PAIRING_PREFIX = 0x01         // [CAPod-PP] PAIRING_MESSAGE_PREFIX：其他前綴的 0x07 訊息不是明文狀態

    /**
     * @param appleData `ScanRecord.getManufacturerSpecificData(0x004C)` 的內容（不含公司 ID）。
     * 裡面是一串 [type][length][data...] 的 Continuity 訊息。[CAPod-CP] Decoder.decode
     */
    fun parse(appleData: ByteArray): PodStatus? {
        var i = 0
        while (i + 2 <= appleData.size) {
            val type = appleData[i].u
            val len = appleData[i + 1].u
            val start = i + 2
            if (start + len > appleData.size) return null // 長度不合，丟棄
            if (type == TYPE_PROXIMITY_PAIRING && len == PAIRING_MESSAGE_LENGTH && appleData[start].u == PAIRING_PREFIX) {
                return decode(appleData, start)
            }
            i = start + len
        }
        return null
    }

    /** [s] 指向前綴位元組 0x01。各欄位偏移見 [CAPod-AP] pub* 屬性。 */
    private fun decode(d: ByteArray, s: Int): PodStatus? {
        val model = (d[s + 1].u shl 8) or d[s + 2].u    // [CAPod-AP] pubDeviceModel
        if (model in SINGLE_POD_MODELS) return null      // shortcut: 不支援 AirPods Max，要支援時另寫單體解析
        val status = d[s + 3].u                           // [CAPod-AP] pubStatus
        val pods = d[s + 4].u                             // [CAPod-AP] pubPodsBattery
        val flags = d[s + 5].u shr 4                      // [CAPod-AP] pubFlags = 高 4 位
        val caseNibble = d[s + 5].u and 0x0F              // [CAPod-AP] pubCaseBattery = 低 4 位
        val lidByte = d[s + 6].u                          // [CAPod-AP] pubCaseLidState
        val connState = d[s + 8].u                        // [CAPod-T] connection state 測試；[LP-BLE] connStates

        // [CAPod-DP] primaryPod：bit5 = 左耳是 primary；不是的話左右資料對調（areValuesFlipped）
        val primaryLeft = status.bit(5)
        val flipped = !primaryLeft
        val thisPodInCase = status.bit(6)                 // [CAPod-DP] isThisPodInThecase
        val onePodInCase = status.bit(4)                  // [CAPod-DP] isOnePodInCase
        val bothPodsInCase = status.bit(2)                // [CAPod-DP] areBothPodsInCase

        // [CAPod-DP] batteryLeftPodPercent / batteryRightPodPercent
        val leftNibble = if (flipped) pods shr 4 else pods and 0x0F
        val rightNibble = if (flipped) pods and 0x0F else pods shr 4

        // [CAPod-DP] isLeftPodInEar / isRightPodInEar。
        // 注意：[LP-BLE] 用的是 primaryLeft xor thisInCase，條件剛好相反。
        // 這裡採用 CAPod，因為它的測試向量來自實機錄下的廣播。要在實機上確認。
        val swapEar = flipped xor thisPodInCase

        val hasCaseContext = thisPodInCase || onePodInCase || bothPodsInCase
        return PodStatus(
            modelId = model,
            leftBattery = nibbleToPercent(leftNibble),
            rightBattery = nibbleToPercent(rightNibble),
            caseBattery = nibbleToPercent(caseNibble),
            // [CAPod-DP] isLeftPodCharging / isRightPodCharging / isCaseCharging
            leftCharging = if (flipped) flags.bit(1) else flags.bit(0),
            rightCharging = if (flipped) flags.bit(0) else flags.bit(1),
            caseCharging = flags.bit(2),
            leftInEar = if (swapEar) status.bit(3) else status.bit(1),
            rightInEar = if (swapEar) status.bit(1) else status.bit(3),
            primaryLeft = primaryLeft,
            anyPodInCase = hasCaseContext,
            // [CAPod-DP] caseLidState / LidState.fromRaw：只有「盒內耳機」或「兩顆都在盒內」時，
            // 盒蓋位元才可信；盒外耳機送出的盒蓋位元是舊值，回報 UNKNOWN。
            lid = when {
                !hasCaseContext -> LidState.NOT_IN_CASE
                !(thisPodInCase || bothPodsInCase) -> LidState.UNKNOWN
                lidByte.bit(3) -> LidState.CLOSED
                else -> LidState.OPEN
            },
            connectionState = connState,
        )
    }

    /** [CAPod-DP]：15 = 沒有資料，大於 10 視為 100%，其餘 ×10。 */
    private fun nibbleToPercent(n: Int): Int? = when {
        n == 15 -> null
        n > 10 -> 100
        else -> n * 10
    }

    private val Byte.u: Int get() = toInt() and 0xFF
    private fun Int.bit(n: Int): Boolean = (this shr n) and 1 == 1
}
