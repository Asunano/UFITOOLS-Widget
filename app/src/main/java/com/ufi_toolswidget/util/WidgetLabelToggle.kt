package com.ufi_toolswidget.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.ufi_toolswidget.widget.WifiWidget4x2
import com.ufi_toolswidget.widget.WifiWidget4x2NoLabel

/**
 * 小组件标签隐藏/显示工具。
 *
 * 通过切换原始组件（带标签）和影子组件（无标签）的 enabled 状态，
 * 强制桌面启动器重新读取组件元数据（android:label），实现标签的视觉隐藏。
 *
 * 原理：
 * - [WifiWidget4x2] 在 Manifest 中注册 `android:label="UFI 状态 (4x2)"`
 * - [WifiWidget4x2NoLabel] 注册 `android:label`（零宽空格，不可见）
 * - 两者共享相同的布局、渲染逻辑和数据处理
 * - [setComponentEnabledSetting] 触发桌面重新扫描组件，读取新标签
 *
 * 注意：切换组件状态后，部分桌面可能要求用户重新添加小组件才能看到标签变化。
 */
object WidgetLabelToggle {

    private const val TAG = "WidgetLabelToggle"

    private val originalComponent = { pkg: String -> ComponentName(pkg, WifiWidget4x2::class.java.name) }
    private val shadowComponent = { pkg: String -> ComponentName(pkg, WifiWidget4x2NoLabel::class.java.name) }

    /**
     * 切换标签显示/隐藏状态。
     *
     * @param context 上下文
     * @param hideLabel true = 隐藏标签（启用影子组件），false = 显示标签（启用原始组件）
     */
    fun apply(context: Context, hideLabel: Boolean) {
        val pm = context.packageManager
        val pkg = context.packageName

        if (hideLabel) {
            // 隐藏标签：启用影子组件，禁用原始组件
            setComponentState(pm, shadowComponent(pkg), true)
            setComponentState(pm, originalComponent(pkg), false)
            DebugLogger.d(TAG, "Switched to shadow (no-label) component")
        } else {
            // 显示标签：启用原始组件，禁用影子组件
            setComponentState(pm, originalComponent(pkg), true)
            setComponentState(pm, shadowComponent(pkg), false)
            DebugLogger.d(TAG, "Switched to original (labeled) component")
        }
    }

    /**
     * 获取当前是否处于隐藏标签状态。
     * 通过检查影子组件的 enabled 状态来判断。
     */
    fun isShadowActive(context: Context): Boolean {
        val pm = context.packageManager
        val componentName = shadowComponent(context.packageName)
        return try {
            val state = pm.getComponentEnabledSetting(componentName)
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取当前活跃的 4x2 组件类（原始或影子）。
     * 用于 renderAllWidgets 确定正确的 ComponentName 进行渲染。
     */
    fun getActive4x2Class(context: Context): Class<*> {
        return if (isShadowActive(context)) WifiWidget4x2NoLabel::class.java
        else WifiWidget4x2::class.java
    }

    private fun setComponentState(pm: PackageManager, component: ComponentName, enabled: Boolean) {
        try {
            pm.setComponentEnabledSetting(
                component,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            DebugLogger.w(TAG, "setComponentEnabled(${component.className}, $enabled) failed: ${e.message}")
        }
    }
}
