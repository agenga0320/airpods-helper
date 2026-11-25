package io.github.agenga0320.podhelper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.agenga0320.podhelper.overlay.PodCard
import io.github.agenga0320.podhelper.service.PodService

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
        PodService.start(this) // 權限不足時不會啟動
    }

    @Composable
    private fun Screen() {
        val pods by vm.pods.collectAsState()
        val perms by vm.perms.collectAsState()
        val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            vm.refreshPermissions()
            PodService.start(this)
        }

        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("AirPods 小幫手", fontSize = 26.sp, fontWeight = FontWeight.Bold)

            PodCard(pods, onClose = null)
            Text(
                buildString {
                    append(if (pods.classicConnected) "手機已透過藍牙連著 AirPods" else "手機目前沒有連著 AirPods")
                    pods.rssi?.let { append(" · 訊號 $it dBm") }
                    pods.error?.let { append("\n⚠ $it") }
                },
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("權限設定", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            PermRow("藍牙（掃描、連線）", "掃描 AirPods 的廣播，必要", perms.bluetooth) {
                askPerms.launch(Permissions.BLUETOOTH)
            }
            PermRow("通知", "在通知列顯示電量", perms.notifications) {
                askPerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            PermRow("顯示在其他應用程式上層", "打開盒蓋時跳出電量卡片", perms.overlay) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            PermRow("不限制電池用量", "避免系統把背景監聽停掉", perms.batteryUnrestricted) { requestBatteryExemption() }

            OutlinedButton(
                onClick = { PodService.start(this@MainActivity, PodService.ACTION_TEST_OVERLAY) },
                enabled = perms.bluetooth && perms.overlay,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("測試彈出卡片") }
            Spacer(Modifier.height(24.dp))
        }
    }

    @SuppressLint("BatteryLife") // 自用 App，不上架，可以直接要求
    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    @Composable
    private fun PermRow(title: String, desc: String, granted: Boolean, onRequest: () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (granted) "✅ " else "⬜ ") + title, fontWeight = FontWeight.Medium)
                    Text(desc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!granted) Button(onClick = onRequest) { Text("設定") }
            }
        }
    }
}
