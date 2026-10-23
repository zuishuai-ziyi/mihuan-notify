package com.mihuan.notifyrelay

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 主界面（Jetpack Compose 实现）：状态卡（运行状态 + 连接信息 + 启停/测试/复制）、
 * 传输模式配置、应用行为设置（开机自启 / 最近任务隐藏 / 日志保留条数）、
 * 应用外观与通知（桌面名称图标方案 / 常驻通知标题正文）、运行日志。
 */
class MainActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.ensureChannels(this)
        requestNotificationPermission()

        // 最近任务隐藏为运行时设置，进界面即按用户偏好应用
        applyRecentsSetting()

        // 默认自动启动服务
        if (!RelayService.running) {
            ContextCompat.startForegroundService(this, serviceIntent())
        }

        setContent {
            RelayTheme {
                MainScreen()
            }
        }
    }

    // ---------- 非组合态动作（供 Composable 回调） ----------

    private fun hasNotifPerm(): Boolean = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun serviceIntent(action: String = RelayService.ACTION_START) =
        Intent(this, RelayService::class.java).setAction(action)

    private fun toggleService() {
        if (RelayService.running) {
            startService(serviceIntent(RelayService.ACTION_STOP))
        } else {
            ContextCompat.startForegroundService(this, serviceIntent())
        }
    }

    private fun sendTestNotification() {
        startService(Intent(this, RelayService::class.java).setAction(RelayService.ACTION_TEST))
        Toast.makeText(this, "已请求发送测试通知", Toast.LENGTH_SHORT).show()
    }

    private fun regenerateToken() {
        ConfigStore.regenerateToken(this)
        Toast.makeText(this, "Token 已重新生成，请同步更新 PC 端设置页", Toast.LENGTH_LONG).show()
    }

    /** 400ms 后拉起服务（给 stop 一点时间，避免 start/stop 竞态）。 */
    private fun restartServiceSoon() {
        handler.postDelayed({
            ContextCompat.startForegroundService(this, serviceIntent())
        }, 400)
    }

    /** 保存并应用传输模式（含中继三要素）。返回 false 表示校验失败未应用。 */
    private fun applyMode(relaySelected: Boolean, url: String, cid: String, ptok: String): Boolean {
        if (relaySelected && (url.isEmpty() || cid.isEmpty() || ptok.isEmpty())) {
            Toast.makeText(this, "中继模式需填写地址、通道 ID 与 Poll Token", Toast.LENGTH_LONG).show()
            return false
        }
        val newMode = if (relaySelected) ConfigStore.MODE_RELAY else ConfigStore.MODE_DIRECT
        val wasRunning = RelayService.running
        if (wasRunning) startService(serviceIntent(RelayService.ACTION_STOP))
        ConfigStore.setMode(this, newMode)
        if (relaySelected) {
            ConfigStore.setRelayUrl(this, url)
            ConfigStore.setRelayChannelId(this, cid)
            ConfigStore.setRelayPollToken(this, ptok)
        }
        Toast.makeText(
            this,
            "已切换为${if (relaySelected) "中继" else "直连"}模式",
            Toast.LENGTH_SHORT
        ).show()
        if (wasRunning || relaySelected) restartServiceSoon()
        return true
    }

    /** 端口修改：写入配置并重启服务使其生效。 */
    private fun applyPort(raw: String) {
        val p = raw.trim().toIntOrNull()
        when {
            p == null || p !in 1024..65535 ->
                Toast.makeText(this, "端口需为 1024-65535 的数字", Toast.LENGTH_SHORT).show()
            p == ConfigStore.port(this) ->
                Toast.makeText(this, "端口未变化", Toast.LENGTH_SHORT).show()
            else -> {
                val wasRunning = RelayService.running
                if (wasRunning) startService(serviceIntent(RelayService.ACTION_STOP))
                ConfigStore.setPort(this, p)
                Toast.makeText(this, "端口已改为 $p", Toast.LENGTH_SHORT).show()
                if (wasRunning) restartServiceSoon()
            }
        }
    }

    /** 复制连接配置：直连模式=PC 端设置页 [手机端] 片段；中继模式=中继三要素。 */
    private fun copyConfig() {
        val text = if (ConfigStore.mode(this) == ConfigStore.MODE_RELAY) {
            buildString {
                appendLine("中继地址：${ConfigStore.relayUrl(this@MainActivity)}")
                appendLine("通道 ID：${ConfigStore.relayChannelId(this@MainActivity)}")
                append("Poll Token：${ConfigStore.relayPollToken(this@MainActivity)}")
            }
        } else {
            val ip = localIpv4() ?: "0.0.0.0"
            buildString {
                appendLine("[phone]")
                appendLine("url = \"http://$ip:${ConfigStore.port(this@MainActivity)}\"")
                append("token = \"${ConfigStore.token(this@MainActivity)}\"")
            }
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("relay_config", text))
        Toast.makeText(this, "配置已复制，可粘贴到 PC 端设置页", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
            )
        }
    }

    /** 按用户偏好把主界面任务在最近任务列表中隐藏/显示（部分 ROM 可能不支持，失败忽略）。 */
    private fun applyRecentsSetting() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val hide = ConfigStore.hideRecents(this)
            am.appTasks.forEach { it.setExcludeFromRecents(hide) }
        } catch (e: Exception) {
            // 个别 ROM 不支持运行时切换：保持默认行为即可
        }
    }

    /** 枚举网卡取局域网 IPv4（无需定位权限）。 */
    private fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    // ---------- Compose UI ----------

    @Composable
    fun MainScreen() {
        val ctx = this
        val statusColors = relayStatusColors()

        // 周期刷新的服务侧状态（500ms，足够灵敏且开销极小）
        var running by remember { mutableStateOf(RelayService.running) }
        var permGranted by remember { mutableStateOf(hasNotifPerm()) }
        var ip by remember { mutableStateOf<String?>(null) }
        var logs by remember { mutableStateOf<List<String>>(emptyList()) }
        var savedMode by remember { mutableStateOf(ConfigStore.mode(ctx)) }
        // 用户改过模式但未「保存并应用」——非空期间 UI 显示 pendingMode，轮询不得回写
        var pendingMode by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            while (true) {
                running = RelayService.running
                permGranted = hasNotifPerm()
                ip = localIpv4()
                logs = RelayService.logSnapshot()
                savedMode = ConfigStore.mode(ctx)
                delay(500)
            }
        }

        // 本地表单状态（初值即持久化值，无需回填脏标记）
        var relayUrl by remember { mutableStateOf(ConfigStore.relayUrl(ctx)) }
        var relayChannel by remember { mutableStateOf(ConfigStore.relayChannelId(ctx)) }
        var relayPoll by remember { mutableStateOf(ConfigStore.relayPollToken(ctx)) }
        var portText by remember { mutableStateOf(ConfigStore.port(ctx).toString()) }
        var bootStart by remember { mutableStateOf(ConfigStore.bootStart(ctx)) }
        var hideRecents by remember { mutableStateOf(ConfigStore.hideRecents(ctx)) }
        var logLimit by remember { mutableIntStateOf(ConfigStore.logLimit(ctx)) }
        // 应用外观与通知
        var appProfile by remember { mutableStateOf(ConfigStore.appProfile(ctx)) }
        var notifTitle by remember { mutableStateOf(ConfigStore.notifTitle(ctx)) }
        var notifText by remember { mutableStateOf(ConfigStore.notifText(ctx)) }

        val modeDirty = pendingMode != null
        val displayMode = pendingMode ?: savedMode

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ===== 状态卡 =====
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (running) statusColors.ok else statusColors.bad)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (running) "服务运行中" else "服务已停止",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = if (savedMode == ConfigStore.MODE_RELAY) "中继模式" else "直连模式",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    }

                    SelectionContainer {
                        Text(
                            text = buildString {
                                if (savedMode == ConfigStore.MODE_RELAY) {
                                    appendLine("中继：${ConfigStore.relayUrl(ctx)}")
                                    append("通道：${ConfigStore.relayChannelId(ctx)}")
                                } else {
                                    appendLine("监听地址：http://${ip ?: "（无局域网 IP）"}:${ConfigStore.port(ctx)}")
                                    append("Token：${ConfigStore.token(ctx)}")
                                }
                            } + if (modeDirty) "\n⚠ 改动未生效：请点「保存并应用」" else "",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { toggleService() },
                            modifier = Modifier.weight(1f)
                        ) { Text(if (running) "停止服务" else "启动服务") }
                        OutlinedButton(
                            onClick = { sendTestNotification() },
                            modifier = Modifier.weight(1f)
                        ) { Text("测试通知") }
                    }
                    Row {
                        TextButton(onClick = { copyConfig() }) {
                            Text("复制配置信息", fontSize = 13.sp)
                        }
                        TextButton(onClick = { regenerateToken() }) {
                            Text("重新生成 Token", fontSize = 13.sp)
                        }
                    }
                }
            }

            // ===== 通知权限提示 =====
            if (!permGranted) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = statusColors.warnContainer
                    )
                ) {
                    Text(
                        text = "⚠ 未授予通知权限，收到消息也无法展示。请点「启动服务」后在系统弹窗中允许，或到系统设置里开启。",
                        fontSize = 12.sp,
                        color = statusColors.onWarnContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }
            }

            // ===== 传输模式 =====
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "传输模式",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = displayMode == ConfigStore.MODE_DIRECT,
                            onClick = { pendingMode = ConfigStore.MODE_DIRECT },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            modifier = Modifier.weight(1f)
                        ) { Text("直连（局域网）") }
                        SegmentedButton(
                            selected = displayMode == ConfigStore.MODE_RELAY,
                            onClick = { pendingMode = ConfigStore.MODE_RELAY },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            modifier = Modifier.weight(1f)
                        ) { Text("经中继服务器") }
                    }

                    if (displayMode == ConfigStore.MODE_RELAY) {
                        OutlinedTextField(
                            value = relayUrl,
                            onValueChange = { relayUrl = it },
                            label = { Text("中继服务地址") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = relayChannel,
                            onValueChange = { relayChannel = it },
                            label = { Text("通道 ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = relayPoll,
                            onValueChange = { relayPoll = it },
                            label = { Text("Poll Token") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { portText = it },
                                label = { Text("监听端口") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = { applyPort(portText) }) { Text("应用") }
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            if (applyMode(
                                    displayMode == ConfigStore.MODE_RELAY,
                                    relayUrl.trim(), relayChannel.trim(), relayPoll.trim()
                                )
                            ) {
                                pendingMode = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("保存并应用（重启服务）") }
                }
            }

            // ===== 应用行为 =====
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "应用行为",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    SettingRow(
                        title = "开机自启",
                        desc = "重启手机后自动恢复转发（需停机前服务在运行）",
                        checked = bootStart,
                        onChange = { checked ->
                            bootStart = checked
                            ConfigStore.setBootStart(ctx, checked)
                            Toast.makeText(
                                ctx,
                                if (checked) "开机自启已开启（需停机前服务在运行）" else "开机自启已关闭",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                    SettingRow(
                        title = "在最近任务中隐藏",
                        desc = "主界面不出现在系统最近任务列表，后台转发不受影响",
                        checked = hideRecents,
                        onChange = { checked ->
                            hideRecents = checked
                            ConfigStore.setHideRecents(ctx, checked)
                            applyRecentsSetting()
                            Toast.makeText(
                                ctx,
                                if (checked) "已隐藏：主界面不出现在最近任务列表" else "已显示：主界面出现在最近任务列表",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = "日志保留条数",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "主界面「运行日志」最多保留的行数，超出自动清理，立即生效",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val limits = listOf(40, 100, 200, 500)
                        limits.forEachIndexed { i, v ->
                            SegmentedButton(
                                selected = logLimit == v,
                                onClick = {
                                    if (v != ConfigStore.logLimit(ctx)) {
                                        logLimit = v
                                        ConfigStore.setLogLimit(ctx, v)
                                        RelayService.applyLogLimit(v)
                                        Toast.makeText(ctx, "日志保留条数：$v", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = limits.size),
                                modifier = Modifier.weight(1f)
                            ) { Text("$v") }
                        }
                    }
                }
            }

            // ===== 应用外观与通知 =====
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "应用外观与通知",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "桌面名称与图标",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "切换后桌面图标与名称随之更新；系统「应用信息」页仍显示默认名称，部分桌面需稍候刷新",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        AppAppearance.PROFILES.forEachIndexed { i, p ->
                            SegmentedButton(
                                selected = appProfile == p.key,
                                onClick = {
                                    if (appProfile != p.key) {
                                        appProfile = p.key
                                        ConfigStore.setAppProfile(ctx, p.key)
                                        AppAppearance.apply(ctx, p.key)
                                        Toast.makeText(
                                            ctx,
                                            "已切换为「${p.label}」，桌面图标与名称稍后刷新",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = i,
                                    count = AppAppearance.PROFILES.size
                                ),
                                modifier = Modifier.weight(1f)
                            ) { Text(p.short) }
                        }
                    }

                    Text(
                        text = "常驻通知内容",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "前台服务常驻通知的标题与正文，留空使用默认；仅显示在本机通知栏，不会转发到手环",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = notifTitle,
                        onValueChange = { notifTitle = it },
                        label = { Text("通知标题") },
                        placeholder = { Text("通知转发服务运行中") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = notifText,
                        onValueChange = { notifText = it },
                        label = { Text("通知正文") },
                        placeholder = { Text("监听 0.0.0.0:${ConfigStore.port(ctx)}，等待 PC 推送") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            ConfigStore.setNotifTitle(ctx, notifTitle)
                            ConfigStore.setNotifText(ctx, notifText)
                            ctx.startService(
                                Intent(ctx, RelayService::class.java)
                                    .setAction(RelayService.ACTION_REFRESH_NOTIF)
                            )
                            Toast.makeText(ctx, "常驻通知内容已应用", Toast.LENGTH_SHORT).show()
                        }) { Text("应用通知内容") }
                        TextButton(onClick = {
                            notifTitle = ""
                            notifText = ""
                            ConfigStore.setNotifTitle(ctx, "")
                            ConfigStore.setNotifText(ctx, "")
                            ctx.startService(
                                Intent(ctx, RelayService::class.java)
                                    .setAction(RelayService.ACTION_REFRESH_NOTIF)
                            )
                            Toast.makeText(ctx, "已恢复默认常驻通知", Toast.LENGTH_SHORT).show()
                        }) { Text("恢复默认") }
                    }
                }
            }

            // ===== 运行日志 =====
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "运行日志",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            RelayService.clearLogs()
                            logs = RelayService.logSnapshot()
                        }) { Text("清空") }
                    }
                    SelectionContainer {
                        Text(
                            text = logs.joinToString("\n").ifEmpty { "（暂无日志）" },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingRow(
        title: String,
        desc: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onChange(!checked) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = desc,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    companion object {
        private const val REQ_NOTIF = 1001
    }
}
