package com.mihuan.notifyrelay

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 主界面：显示连接信息（IP:port + token），启动/停止前台服务，发测试通知，查看日志。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var btnToggle: Button
    private lateinit var rgMode: RadioGroup
    private lateinit var llRelayConf: LinearLayout

    /** 用户手动改过模式单选但尚未「保存并应用」——周期 render() 不得回写/回填，否则 1s 后被改回去。 */
    private var modeDirty = false
    private var syncingMode = false
    /** 中继三要素仅首次 render 回填一次，避免边删边被填回。 */
    private var relayPrefilled = false

    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        NotificationHelper.ensureChannels(this)
        requestNotificationPermission()

        tvStatus = findViewById(R.id.tv_status)
        tvLogs = findViewById(R.id.tv_logs)
        btnToggle = findViewById(R.id.btn_toggle)
        rgMode = findViewById(R.id.rg_mode)
        llRelayConf = findViewById(R.id.ll_relay_conf)

        btnToggle.setOnClickListener { toggle() }
        findViewById<Button>(R.id.btn_test).setOnClickListener {
            startService(Intent(this, RelayService::class.java).setAction(RelayService.ACTION_TEST))
            Toast.makeText(this, "已请求发送测试通知", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_token).setOnClickListener {
            ConfigStore.regenerateToken(this)
            Toast.makeText(this, "Token 已重新生成，请同步更新 PC 端设置页", Toast.LENGTH_LONG).show()
            render()
        }
        findViewById<Button>(R.id.btn_copy).setOnClickListener { copyConfig() }

        // 传输模式切换：直连 / 中继
        val rbRelay = findViewById<RadioButton>(R.id.rb_relay)
        val etRelayUrl = findViewById<EditText>(R.id.et_relay_url)
        val etRelayChannel = findViewById<EditText>(R.id.et_relay_channel)
        val etRelayPoll = findViewById<EditText>(R.id.et_relay_poll)

        rgMode.setOnCheckedChangeListener { _, _ ->
            llRelayConf.visibility = if (rbRelay.isChecked) android.view.View.VISIBLE else android.view.View.GONE
            // 代码同步（render）不算用户操作，不置脏标记
            if (!syncingMode) modeDirty = true
        }
        findViewById<Button>(R.id.btn_relay_apply).setOnClickListener {
            val url = etRelayUrl.text.toString().trim()
            val cid = etRelayChannel.text.toString().trim()
            val ptok = etRelayPoll.text.toString().trim()
            val relaySelected = rbRelay.isChecked
            if (relaySelected && (url.isEmpty() || cid.isEmpty() || ptok.isEmpty())) {
                Toast.makeText(this, "中继模式需填写地址、通道 ID 与 Poll Token", Toast.LENGTH_LONG).show()
                return@setOnClickListener
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
            modeDirty = false
            relayPrefilled = true
            Toast.makeText(this, "已切换为${if (newMode == ConfigStore.MODE_RELAY) "中继" else "直连"}模式", Toast.LENGTH_SHORT).show()
            if (wasRunning || newMode == ConfigStore.MODE_RELAY) {
                handler.postDelayed({
                    ContextCompat.startForegroundService(this, serviceIntent())
                }, 400)
            }
            handler.postDelayed({ render() }, 900)
        }

        // 端口修改：写入配置并重启服务使其生效
        findViewById<Button>(R.id.btn_port).setOnClickListener {
            val raw = findViewById<EditText>(R.id.et_port).text.toString().trim()
            val p = raw.toIntOrNull()
            if (p == null || p !in 1024..65535) {
                Toast.makeText(this, "端口需为 1024-65535 的数字", Toast.LENGTH_SHORT).show()
            } else if (p == ConfigStore.port(this)) {
                Toast.makeText(this, "端口未变化", Toast.LENGTH_SHORT).show()
            } else {
                val wasRunning = RelayService.running
                if (wasRunning) startService(serviceIntent(RelayService.ACTION_STOP))
                ConfigStore.setPort(this, p)
                Toast.makeText(this, "端口已改为 $p", Toast.LENGTH_SHORT).show()
                if (wasRunning) {
                    handler.postDelayed({
                        ContextCompat.startForegroundService(this, serviceIntent())
                    }, 400)
                }
                handler.postDelayed({ render() }, 900)
            }
        }

        // 默认自动启动服务
        if (!RelayService.running) {
            ContextCompat.startForegroundService(this, serviceIntent())
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.post(poller)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
    }

    private fun serviceIntent(action: String = RelayService.ACTION_START) =
        Intent(this, RelayService::class.java).setAction(action)

    private fun toggle() {
        if (RelayService.running) {
            startService(serviceIntent(RelayService.ACTION_STOP))
        } else {
            ContextCompat.startForegroundService(this, serviceIntent())
        }
        handler.postDelayed({ render() }, 300)
    }

    private fun render() {
        val state = if (RelayService.running) "● 运行中" else "○ 已停止"
        // 通知权限被拒时给常驻提示条
        val granted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        findViewById<TextView>(R.id.tv_perm).visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
        // 模式显示与表单回填（用户改过模式后不再回写，交给「保存并应用」）
        val mode = ConfigStore.mode(this)
        if (!modeDirty) {
            val want = if (mode == ConfigStore.MODE_RELAY) R.id.rb_relay else R.id.rb_direct
            if (rgMode.checkedRadioButtonId != want) {
                syncingMode = true
                rgMode.check(want)
                syncingMode = false
            }
            llRelayConf.visibility = if (mode == ConfigStore.MODE_RELAY) android.view.View.VISIBLE else android.view.View.GONE
        }
        val etUrl = findViewById<EditText>(R.id.et_relay_url)
        val etCh = findViewById<EditText>(R.id.et_relay_channel)
        val etTok = findViewById<EditText>(R.id.et_relay_poll)
        if (!relayPrefilled) {
            if (ConfigStore.relayUrl(this).isNotEmpty()) etUrl.setText(ConfigStore.relayUrl(this))
            if (ConfigStore.relayChannelId(this).isNotEmpty()) etCh.setText(ConfigStore.relayChannelId(this))
            if (ConfigStore.relayPollToken(this).isNotEmpty()) etTok.setText(ConfigStore.relayPollToken(this))
            relayPrefilled = true
        }

        val ip = localIpv4() ?: "（无局域网 IP）"
        tvStatus.text = buildString {
            appendLine("状态：$state（${if (mode == ConfigStore.MODE_RELAY) "中继模式" else "直连模式"}）${if (modeDirty) " — 改动未生效，请点「保存并应用」" else ""}")
            if (mode == ConfigStore.MODE_RELAY) {
                appendLine("中继：${ConfigStore.relayUrl(this@MainActivity)}")
                appendLine("通道：${ConfigStore.relayChannelId(this@MainActivity)}")
            } else {
                appendLine("监听地址：http://$ip:${ConfigStore.port(this@MainActivity)}")
                appendLine("Token（需与 PC 端设置页 [手机端] 一致）：")
                append(ConfigStore.token(this@MainActivity))
            }
        }
        btnToggle.text = if (RelayService.running) "停止服务" else "启动服务"
        tvLogs.text = RelayService.logSnapshot().joinToString("\n")
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

    companion object {
        private const val REQ_NOTIF = 1001
    }
}
