package com.mihuan.notifyrelay

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 应用外观方案：桌面名称 + 图标的组合。
 *
 * Android 无法在运行时直接改应用名称/图标，标准做法是多 activity-alias：
 * 每个 alias 自带独立的 label 与 icon，经 PackageManager.setComponentEnabledSetting
 * 启用目标 alias、禁用其余即可切换桌面入口。该状态由系统包管理器持久化，
 * 重启后仍然生效；「默认」方案对应 Manifest 中 enabled="true" 的初始 alias。
 */
object AppAppearance {
    data class Profile(val key: String, val short: String, val label: String, val alias: String)

    const val DEFAULT = "default"

    val PROFILES = listOf(
        Profile(DEFAULT, "默认", "默认（通知转发）", ".LauncherDefault"),
        Profile("notes", "记事本", "记事本", ".LauncherNotes"),
        Profile("calc", "计算器", "计算器", ".LauncherCalc"),
        Profile("weather", "天气", "天气", ".LauncherWeather"),
    )

    fun profileOf(key: String): Profile =
        PROFILES.firstOrNull { it.key == key } ?: PROFILES[0]

    /**
     * 切换到指定外观方案：先启用目标 alias，再禁用其余当前启用的 alias
     * （顺序保证任何时刻桌面都至少有一个入口）。
     */
    fun apply(ctx: Context, key: String) {
        val profile = profileOf(key)
        val pm = ctx.packageManager
        val pkg = ctx.packageName

        PROFILES.filter { it.key == profile.key }.forEach { p ->
            pm.setComponentEnabledSetting(
                ComponentName(pkg, pkg + p.alias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        PROFILES.filter { it.key != profile.key }.forEach { p ->
            val cn = ComponentName(pkg, pkg + p.alias)
            if (pm.getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }
}
