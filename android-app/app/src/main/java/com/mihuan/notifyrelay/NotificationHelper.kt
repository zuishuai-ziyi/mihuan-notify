package com.mihuan.notifyrelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * 系统通知发布。
 *
 * 链路关键（task-1 调研结论）：App 只发普通 Android 系统通知，
 * 由小米运动健康通过「通知使用权」监听并转发到小米手环。
 * 因此消息通知必须让 Mi Fitness 可见——不要设置 setLocalOnly(true)。
 * 前台服务自身通知则用 IMPORTANCE_MIN + setLocalOnly(true) + 静音 channel，
 * 尽量避免被 Mi Fitness 转发到手环（task-5 联调验证）。
 */
object NotificationHelper {
    const val CH_NORMAL = "messages_normal"
    const val CH_HIGH = "messages_high"
    const val CH_SERVICE = "relay_service"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_NORMAL, "消息通知", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_HIGH, "重要消息通知", NotificationManager.IMPORTANCE_HIGH)
        )
        NotificationChannel(CH_SERVICE, "转发服务（静默）", NotificationManager.IMPORTANCE_MIN).let {
            it.setShowBadge(false)
            it.setSound(null, null)
            it.enableVibration(false)
            nm.createNotificationChannel(it)
        }
    }

    /**
     * 前台服务常驻通知：静默 + localOnly，避免转发到手环。
     * 标题/副标题由用户自定义，留空 = 不显示对应行（不回退默认文案）。
     */
    fun serviceNotification(ctx: Context): Notification {
        val builder = NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setLocalOnly(true)
        ConfigStore.notifTitle(ctx).takeIf { it.isNotEmpty() }?.let { builder.setContentTitle(it) }
        ConfigStore.notifText(ctx).takeIf { it.isNotEmpty() }?.let { builder.setContentText(it) }
        return builder.build()
    }

    /**
     * 发布一条转发消息通知。返回通知 id（由消息 id 派生，同 id 自动覆盖旧通知）。
     */
    fun showMessage(
        ctx: Context,
        id: String,
        title: String,
        body: String,
        source: String?,
        priority: String,
    ): Int {
        val high = priority == "high"
        val builder = NotificationCompat.Builder(ctx, if (high) CH_HIGH else CH_NORMAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
        source?.let { builder.setSubText(it) }
        if (high) builder.setPriority(NotificationCompat.PRIORITY_HIGH)

        val nid = id.hashCode()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(nid, builder.build())
        return nid
    }
}
