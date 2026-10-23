package com.mihuan.notifyrelay

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.io.File

/**
 * 应用外观方案：桌面名称 + 图标的组合。
 *
 * Android 无法在运行时直接改应用名称/图标，标准做法是多 activity-alias：
 * 每个 alias 自带独立的 label 与 icon，经 PackageManager.setComponentEnabledSetting
 * 启用目标 alias、禁用其余即可切换桌面入口。该状态由系统包管理器持久化，
 * 重启甚至升级后仍然生效；「默认」方案对应 Manifest 中 enabled="true" 的初始 alias。
 *
 * 「自定义」方案（custom）基于桌面固定快捷方式（ShortcutManager.requestPinShortcut）：
 * 用户上传一张图片并命名，确认后在桌面生成带自定义图标/名称的快捷方式，
 * 同时隐藏 4 个内置 alias（原图标从桌面消失）；切回其他方案即可恢复。
 */
object AppAppearance {
    data class Profile(val key: String, val short: String, val label: String, val alias: String)

    const val DEFAULT = "default"
    const val CUSTOM = "custom"

    val PROFILES = listOf(
        Profile(DEFAULT, "默认", "默认（通知转发）", ".LauncherDefault"),
        Profile("notes", "记事本", "记事本", ".LauncherNotes"),
        Profile("calc", "计算器", "计算器", ".LauncherCalc"),
        Profile("weather", "天气", "天气", ".LauncherWeather"),
        Profile(CUSTOM, "自定义", "自定义图标", alias = ""),
    )

    fun profileOf(key: String): Profile =
        PROFILES.firstOrNull { it.key == key } ?: PROFILES[0]

    /**
     * 应用指定外观方案（仅影响 4 个内置 alias 的启用状态）。
     *
     * 注意：其余 alias 必须【无条件】禁用——不能只禁用「显式 ENABLED」的，
     * 因为从未被动过的组件 getComponentEnabledSetting 返回 DEFAULT（而非 ENABLED），
     * 若跳过就会出现「新旧两个图标同时留在桌面」的 bug。
     */
    fun apply(ctx: Context, key: String) {
        val pm = ctx.packageManager
        val pkg = ctx.packageName

        fun set(alias: String, state: Int) {
            pm.setComponentEnabledSetting(
                ComponentName(pkg, pkg + alias),
                state,
                PackageManager.DONT_KILL_APP
            )
        }

        when (profileOf(key).key) {
            CUSTOM -> PROFILES.forEach { p -> set(p.alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED) }
            else -> {
                val target = profileOf(key)
                set(target.alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                PROFILES.filter { it.key != target.key }.forEach { p ->
                    set(p.alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                }
            }
        }
    }

    // ---------- 自定义图标 ----------

    private const val CUSTOM_ICON_FILE = "custom_icon.png"
    private const val CUSTOM_SHORTCUT_ID = "custom_launcher_icon"
    private const val MAX_ICON_DIM = 512

    /** 用户上传的图标图片（内部存储，随应用卸载删除）。 */
    fun iconFile(ctx: Context): File = File(ctx.filesDir, CUSTOM_ICON_FILE)

    fun hasCustomIcon(ctx: Context): Boolean = iconFile(ctx).let { it.exists() && it.length() > 0 }

    /** 从图片选择器返回的 Uri 读取位图，等比缩放到最长边 ≤512px 后存为 PNG。 */
    fun saveIconFromUri(ctx: Context, uri: Uri): Boolean {
        return try {
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return false
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_ICON_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return false

        val w = src.width
        val h = src.height
        val scale = MAX_ICON_DIM.toFloat() / maxOf(w, h)
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (w * scale + 0.5f).toInt().coerceAtLeast(1), (h * scale + 0.5f).toInt().coerceAtLeast(1), true)
        } else src

        iconFile(ctx).outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            if (bmp !== src) src.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun loadIconBitmap(ctx: Context): Bitmap? = try {
        BitmapFactory.decodeFile(iconFile(ctx).absolutePath)
    } catch (e: Exception) {
        null
    }

    /**
     * 请求把自定义图标固定到桌面（系统/桌面会弹出确认框）。
     * pinCallback：固定结果回调（见 ShortcutManager.requestPinShortcut），
     * 由调用方在「用户确认」后再调用 [apply] 隐藏内置 alias。
     */
    fun requestCustomShortcut(ctx: Context, label: String, pinCallback: PendingIntent): Boolean {
        val bmp = loadIconBitmap(ctx) ?: return false
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return false

        val shortcut = ShortcutInfoCompat.Builder(ctx, CUSTOM_SHORTCUT_ID)
            .setShortLabel(label.ifEmpty { "通知转发" })
            .setIcon(IconCompat.createWithAdaptiveBitmap(bmp))
            .setIntent(
                Intent(Intent.ACTION_MAIN)
                    .setClassName(ctx, "com.mihuan.notifyrelay.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            .build()
        return ShortcutManagerCompat.requestPinShortcut(ctx, shortcut, pinCallback.intentSender)
    }
}
