package com.ufi_toolswidget.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台保活权限引导工具。
 *
 * 涵盖三个维度：
 * 1. 电池优化白名单（系统标准 API，全版本通用）
 * 2. 自启动权限（国产 ROM 专属，MIUI / EMUI / ColorOS / FuntouchOS / Flyme）
 * 3. 最近任务锁定（引导用户在 Recents 中锁定应用卡片）
 */
object BatteryOptimizationHelper {

    // ══════════════════════════════════════════════
    // 1. 电池优化白名单
    // ══════════════════════════════════════════════

    /** 当前应用是否已在电池优化白名单中 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 跳转到系统的「忽略电池优化」设置页面。
     * 优先使用 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（一步到位弹窗），
     * 如果权限被拒则回退到 IGNORE_BATTERY_OPTIMIZATION_SETTINGS（列表页手动操作）。
     */
    fun requestIgnoreBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            // 优先尝试一步弹窗
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // 回退到列表页
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                context.startActivity(intent)
            } catch (e: Exception) {
                DebugLogger.w("BatteryHelper", "Opening battery settings failed: ${e.message}")
            }
        }
    }

    // ══════════════════════════════════════════════
    // 2. 自启动权限（国产 ROM）
    // ══════════════════════════════════════════════

    /** 当前 ROM 品牌名称 */
    fun getRomBrand(): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> "MIUI"
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> "EMUI"
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> "ColorOS"
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> "FuntouchOS"
            manufacturer.contains("meizu") -> "Flyme"
            manufacturer.contains("samsung") -> "OneUI"
            else -> "Unknown"
        }
    }

    /** 当前 ROM 是否有已知的自启动管理页面 */
    fun hasAutoStartPage(): Boolean = getRomBrand() != "Unknown"

    /**
     * 尝试跳转到当前 ROM 的自启动管理页面。
     * 不同厂商的 ComponentName 不同，逐一尝试，全部失败则跳转到应用详情页。
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val intents = getAutoStartIntents()
        for (intent in intents) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: Exception) { /* try next */ }
        }
        // 全部失败 → 回退到应用详情页
        return openAppDetailSettings(context)
    }

    private fun getAutoStartIntents(): List<Intent> {
        val pkg = Build.MANUFACTURER.lowercase()
        val components = mutableListOf<ComponentName>()

        when {
            pkg.contains("xiaomi") || pkg.contains("redmi") -> {
                components.add(ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            }
            pkg.contains("huawei") || pkg.contains("honor") -> {
                components.add(ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                components.add(ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"))
            }
            pkg.contains("oppo") || pkg.contains("realme") -> {
                components.add(ComponentName("com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"))
                components.add(ComponentName("com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"))
            }
            pkg.contains("vivo") || pkg.contains("iqoo") -> {
                components.add(ComponentName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
                components.add(ComponentName("com.iqoo.secure",
                    "com.iqoo.secure.MainActivity"))
            }
            pkg.contains("meizu") -> {
                components.add(ComponentName("com.meizu.safe",
                    "com.meizu.safe.security.SHOW_APPSEC"))
            }
            pkg.contains("samsung") -> {
                components.add(ComponentName("com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"))
            }
        }

        return components.map { comp ->
            Intent().apply {
                component = comp
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /** 跳转到应用详情页（通用回退方案） */
    private fun openAppDetailSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            DebugLogger.w("BatteryHelper", "Opening app detail settings failed: ${e.message}")
            false
        }
    }

    // ══════════════════════════════════════════════
    // 3. 最近任务锁定提示
    // ══════════════════════════════════════════════

    /**
     * 获取适合当前 ROM 的「最近任务锁定」操作说明文案。
     */
    fun getTaskLockGuideText(): String {
        return when (getRomBrand()) {
            "MIUI" -> "从最近任务界面长按本应用卡片，点击锁定图标"
            "EMUI" -> "从最近任务界面下拉本应用卡片，点击锁定图标"
            "ColorOS" -> "从最近任务界面长按本应用卡片，选择「锁定」"
            "FuntouchOS" -> "从最近任务界面下拉本应用卡片，点击锁定图标"
            "Flyme" -> "从最近任务界面长按本应用卡片，选择「锁定」"
            "OneUI" -> "从最近任务界面点击本应用卡片上方的锁图标"
            else -> "从最近任务界面长按或下拉本应用卡片，寻找锁定选项"
        }
    }
}
