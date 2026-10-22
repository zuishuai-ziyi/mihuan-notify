package com.mihuan.notifyrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启：系统完成启动后，若用户此前未手动停用服务（ConfigStore.autoStart），
 * 自动拉起前台服务。用户手动「停止服务」后重启手机不会自启。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!ConfigStore.autoStart(context)) return
        context.startForegroundService(
            Intent(context, RelayService::class.java).setAction(RelayService.ACTION_START)
        )
        RelayService.log("开机自启：已请求启动服务")
    }
}
