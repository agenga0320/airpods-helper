package io.github.agenga0320.podhelper.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.agenga0320.podhelper.MainActivity
import io.github.agenga0320.podhelper.PodRepository
import io.github.agenga0320.podhelper.Permissions
import io.github.agenga0320.podhelper.R
import io.github.agenga0320.podhelper.ble.LidState
import io.github.agenga0320.podhelper.ble.PodSelector
import io.github.agenga0320.podhelper.ble.ProximityParser
import io.github.agenga0320.podhelper.ble.Sighting
import io.github.agenga0320.podhelper.overlay.PodOverlay
import io.github.agenga0320.podhelper.widget.BatteryWidget

/**
 * 常駐掃描 AirPods 的 BLE 廣播，更新通知、Widget，並在打開盒蓋時跳出浮動卡片。
 * 所有回呼都在主執行緒，不需要加鎖。
 */
@SuppressLint("MissingPermission") // 呼叫端（Permissions.bluetoothGranted）先檢查過才會啟動這個 service
class PodService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val sightings = HashMap<String, Sighting>()
    private lateinit var overlay: PodOverlay
    private var scanning = false
    private var lastLid: LidState? = null
    private var lastNotifiedText: String? = null
    private var a2dp: BluetoothProfile? = null
    private var headset: BluetoothProfile? = null
    /** 快取起來：掃描結果每秒好幾筆，不要每筆都跨 process 查詢。 */
    private var classicConnected = false

    private val adapter: BluetoothAdapter? get() = getSystemService(BluetoothManager::class.java).adapter

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = PodOverlay(this)
        createChannel()
        try {
            startForeground(NOTIF_ID, buildNotification(PodRepository.state.value.shortText()), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // 權限被收回等情況：不要讓 App 當掉
            PodRepository.state.value = PodRepository.state.value.copy(error = "無法啟動背景服務：${e.message}")
            stopSelf()
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(ACTION_A2DP_STATE)
            addAction(ACTION_HEADSET_STATE)
        }
        // 都是系統廣播，NOT_EXPORTED 仍收得到
        ContextCompat.registerReceiver(this, systemReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        adapter?.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
        adapter?.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
        restartScan()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST_OVERLAY) overlay.show(autoHideMs = 8_000)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopScan()
        overlay.hide()
        runCatching { unregisterReceiver(systemReceiver) }
        a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        headset?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        super.onDestroy()
    }

    // ---------- 掃描 ----------

    private fun restartScan() {
        stopScan()
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            publish(null, error = "藍牙已關閉")
            return
        }
        // 螢幕亮著時用低延遲，打開盒蓋能馬上跳卡片；螢幕關閉時省電。
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        val settings = ScanSettings.Builder()
            .setScanMode(if (screenOn) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        try {
            scanner.startScan(listOf(scanFilter()), settings, scanCallback)
            scanning = true
            publish(null, error = null, keepPods = true)
        } catch (e: Exception) {
            publish(null, error = "無法開始掃描：${e.message}")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
    }

    /**
     * 只收 Apple 的 Proximity Pairing 訊息；有篩選條件，螢幕關閉時系統才會繼續送結果。
     * 出處：CAPod ProximityPairing.getBleScanFilter()；LibrePods BLEManager.kt（manufacturerData[0]=7, [1]=25）
     */
    private fun scanFilter(): ScanFilter {
        val data = ByteArray(27).also { it[0] = ProximityParser.TYPE_PROXIMITY_PAIRING.toByte(); it[1] = ProximityParser.PAIRING_MESSAGE_LENGTH.toByte() }
        val mask = ByteArray(27).also { it[0] = 1; it[1] = 1 }
        return ScanFilter.Builder().setManufacturerData(ProximityParser.APPLE_COMPANY_ID, data, mask).build()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(ProximityParser.APPLE_COMPANY_ID) ?: return
            val status = ProximityParser.parse(data) ?: return
            val now = SystemClock.elapsedRealtime()
            sightings[result.device.address] = Sighting(result.device.address, result.rssi, status, now)
            evaluate(now)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            publish(null, error = "掃描失敗（錯誤碼 $errorCode），30 秒後重試")
            handler.postDelayed({ restartScan() }, 30_000)
        }
    }

    /** 每 5 秒清掉過期的廣播；每 25 分鐘重開一次掃描。 */
    private val tick = object : Runnable {
        private var startedAt = SystemClock.elapsedRealtime()
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            sightings.values.removeAll { now - it.timeMs > PodSelector.MAX_AGE_MS }
            refreshClassic()
            // shortcut: 系統會把跑太久的掃描降級，定時重開；若實機上仍會停掉，再改用 PendingIntent 掃描。
            if (now - startedAt > 25 * 60_000L) { startedAt = now; restartScan() }
            handler.postDelayed(this, 5_000)
        }
    }

    private fun evaluate(now: Long) {
        val pick = PodSelector.pick(sightings.values, now, classicConnected)
        publish(pick)
        handleLid(pick?.status?.lid)
    }

    /** 打開盒蓋（從非 OPEN 變成 OPEN）時跳出卡片，關上時收起。UNKNOWN 不可信，忽略。 */
    private fun handleLid(lid: LidState?) {
        when (lid) {
            LidState.OPEN -> if (lastLid != LidState.OPEN) overlay.show(autoHideMs = 20_000)
            LidState.CLOSED -> overlay.hide()
            LidState.UNKNOWN -> return
            LidState.NOT_IN_CASE, null -> Unit
        }
        lastLid = lid
    }

    // ---------- 經典藍牙連線狀態 ----------

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) a2dp = proxy else headset = proxy
            refreshClassic()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) a2dp = null else headset = null
            refreshClassic()
        }
    }

    private fun refreshClassic() {
        classicConnected = runCatching {
            listOfNotNull(a2dp, headset).flatMap { it.connectedDevices }.any { isAppleAudio(it) }
        }.getOrDefault(false)
        evaluate(SystemClock.elapsedRealtime())
    }

    /**
     * 是不是 AirPods：看 SDP 服務裡有沒有 Apple 的 UUID，名稱裡有 AirPods 也算（使用者可能改過名字）。
     * UUID 出處：CAPod ContinuityProtocol.kt BLE_FEATURE_UUIDS
     */
    private fun isAppleAudio(device: BluetoothDevice): Boolean =
        device.uuids?.any { it in APPLE_UUIDS } == true || device.name?.contains("AirPods", ignoreCase = true) == true

    // ---------- 狀態輸出 ----------

    private fun publish(pick: Sighting?, error: String? = PodRepository.state.value.error, keepPods: Boolean = false) {
        val old = PodRepository.state.value
        val new = old.copy(
            pods = if (keepPods) old.pods else pick?.status,
            rssi = if (keepPods) old.rssi else pick?.rssi,
            lastCaseBattery = pick?.status?.caseBattery ?: old.lastCaseBattery,
            classicConnected = classicConnected,
            error = error,
        )
        if (new == old) return
        PodRepository.state.value = new
        val text = new.error ?: new.shortText()
        if (text != lastNotifiedText) {
            lastNotifiedText = text
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
            BatteryWidget.updateAll(this, new)
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "AirPods 電量", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pods)
            .setContentTitle(PodRepository.state.value.pods?.modelName ?: "AirPods")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> restartScan()
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (s == BluetoothAdapter.STATE_ON || s == BluetoothAdapter.STATE_OFF) restartScan()
                }
                else -> refreshClassic()
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "status"
        private const val NOTIF_ID = 1
        const val ACTION_TEST_OVERLAY = "io.github.agenga0320.podhelper.TEST_OVERLAY"
        private const val ACTION_A2DP_STATE = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
        private const val ACTION_HEADSET_STATE = "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED"
        private val APPLE_UUIDS = setOf(
            ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a"),
            ParcelUuid.fromString("2a72e02b-7b99-778f-014d-ad0b7221ec74"),
        )

        /** 只有藍牙權限都拿到時才啟動，否則 startForeground 會丟例外。 */
        fun start(context: Context, action: String? = null) {
            if (!Permissions.bluetoothGranted(context)) return
            context.startForegroundService(Intent(context, PodService::class.java).setAction(action))
        }
    }
}
