package com.ufi_toolswidget

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ufi_toolswidget.util.*
import com.ufi_toolswidget.widget.BaseWifiWidget
import com.ufi_toolswidget.worker.WifiWorker
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var tvModel: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvNetSignal: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvCpu: TextView
    private lateinit var tvMem: TextView
    private lateinit var tvDaily: TextView
    private lateinit var tvFlow: TextView
    private lateinit var tvFirmware: TextView
    private lateinit var tvFirmwareSub: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvBatterySub: TextView
    private lateinit var tvStorage: TextView
    private lateinit var tvClientIp: TextView

    // ── 缓存字段：减少每次刷新的重复计算 ──
    /** 缓存 App 版本号（进程生命周期内不变，避免每次刷新都 IPC 调用 getPackageInfo） */
    private var cachedAppVersion: String? = null
    /** 缓存信号图标 ImageView 引用（避免每次刷新 findViewById） */
    private lateinit var ivAntenna: ImageView
    /** 上次 UI 数据的快照哈希，用于跳过未变更字段的 UI 更新 */
    private var lastUiDataHash: Long = 0L

    // 保存最后一次获取到的完整数据（用于弹窗详情） — 已迁移至 MainViewModel
    // 线程: 仅限主线程，使用 viewModel.getWifiEntity() 访问

    // 主题变更接收器
    private var themeChangeReceiver: BroadcastReceiver? = null

    // 当前打开的详情弹窗（支持实时刷新）
    // activeDialogType 已迁移至 MainViewModel，通过 viewModel.setActiveDialogType() / viewModel.activeDialogType.value 访问
    private var activeDialog: Dialog? = null

    // 弹窗防拥堵：debounce 时间戳
    private var lastDialogClickTime = 0L

    // 加载 / 主内容状态
    private lateinit var loadingView: View
    private lateinit var mainContentView: View

    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null
    private var autoRefreshJob: Job? = null

    /** ViewModel：横跨配置变更存活，解决旋转屏幕数据丢失问题 */
    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    /** 首次加载动画是否已执行（避免旋转后重复触发） */
    private var hasShownFirstLoadAnimation = false

    /** 检查更新是否正在进行中（防止重复点击） */
    private var isCheckingUpdate = false

    override fun onResume() {
        super.onResume()
        DebugLogger.logLife(TAG, "onResume() called")
        ThemeUtil.applyTheme(this, ThemeUtil.PageType.MAIN)
        DebugLogger.logUi(TAG, "Theme applied in onResume")
        if (::tvModel.isInitialized) {
            // 先检测 Worker 是否因连续失败被停止
            checkWorkerFailureState()
            // 只有没有错误弹窗显示时才刷新数据
            if (viewModel.activeDialogType.value != "error") {
                DebugLogger.logLife(TAG, "onResume: no error dialog, calling refreshData")
                // 冷启动时保持加载动画可见，等待首次数据到达；
                // 旋转恢复等场景则直接显示缓存数据
                if (viewModel.hasCompletedFirstRefresh) {
                    viewModel.getWifiEntity()?.let { cachedData ->
                        DebugLogger.logUi(TAG, "onResume: applying cached entity from ViewModel")
                        hideLoadingView(false)
                        if (!hasShownFirstLoadAnimation) {
                            hasShownFirstLoadAnimation = true
                            setupAnimations()  // 首次加载 / 屏幕旋转后播放入场动画
                        }
                        applyWifiEntityToUi(cachedData)  // 后填充数据，内容在动画中淡入
                    }
                } else {
                    DebugLogger.logUi(TAG, "onResume: cold start, keeping loading visible")
                }
                refreshData()
                startAutoRefreshTimer()
            } else {
                DebugLogger.logLife(TAG, "onResume: error dialog IS visible, skipping refreshData")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        DebugLogger.logLife(TAG, "onPause() called, stopping auto refresh")
        // 离开界面时停止自动刷新
        stopAutoRefreshTimer()
        dismissActiveDialog()
        DebugLogger.flushToFile() // Activity 离开前台，落盘日志
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ═══ 初始化全局崩溃捕获 ═══
        CrashHandler.init(applicationContext)

        // 全局应用主题
        AppCompatDelegate.setDefaultNightMode(SPUtil.getNightMode(this))
        super.onCreate(savedInstanceState)

        if (SPUtil.isFirstRun(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        DebugLogger.init(this)

        try {
            // 1. 设置视图
            setContentView(R.layout.activity_main)
            // 2. 应用主题
            ThemeUtil.applyTheme(this, ThemeUtil.PageType.MAIN)
            // 3. 注册主题变更监听（后台也能实时更新背景）
            themeChangeReceiver = ThemeChangeNotifier.register(this) {
                AnimationUtil.applyCircleRevealPulse(this@MainActivity) {
                    ThemeUtil.applyThemeSync(this@MainActivity, ThemeUtil.PageType.MAIN)
                }
            }
            // 3. 入场动画（安全包装）
            try {
                if (AnimationUtil.pendingTransitionBitmap?.get() != null) {
                    AnimationUtil.applyCrossfadeEnterFromRecreate(this)
                }
            } catch (e: Exception) {
                DebugLogger.logExc(TAG, "入场动画执行失败: ${e.message}")
            }

            initViews()
            displayAppVersion()

            // 自动检测更新逻辑
            checkUpdateSilently()

            // 启动数据同步
            startWorker(SPUtil.getRefreshInterval(this))
            
            // ════ 检测上次崩溃 ════
            checkCrashLogOnStartup()
        } catch (e: Exception) {
            DebugLogger.logExc(TAG, "MainActivity 启动流程崩溃: ${e.message}")
            e.printStackTrace()
            // 兜底：避免白屏
            val errorView = TextView(this).apply {
                text = "加载界面失败\n\n${e.message}\n\n建议前往「诊断中心」查看原因"
                textSize = 14f
                setPadding(40, 80, 40, 40)
            }
            val scroll = ScrollView(this).apply { addView(errorView) }
            setContentView(scroll)
        }
    }

    /**
     * 静默检查更新（启动时触发，受开关和频率限制）。
     * 使用 lifecycleScope 确保 Activity 销毁时协程自动取消。
     */
    private fun checkUpdateSilently() {
        if (!SPUtil.getAutoCheckUpdate(this)) return

        val now = System.currentTimeMillis()
        val lastCheck = SPUtil.getLastUpdateCheckTime(this)
        
        // 限制自动检测频率：每 24 小时检查一次
        if (now - lastCheck < 24 * 60 * 60 * 1000L) {
            DebugLogger.logSys(TAG, "checkUpdateSilently: skip, last check was within 24h")
            return
        }

        DebugLogger.logSys(TAG, "checkUpdateSilently: starting background check")
        lifecycleScope.launch {
            when (val result = UpdateChecker.checkUpdate(this@MainActivity)) {
                is UpdateChecker.UpdateResult.NewVersion -> {
                    SPUtil.setLastUpdateCheckTime(this@MainActivity, now)
                    showUpdateDialog(result.info)
                }
                is UpdateChecker.UpdateResult.Latest -> {
                    SPUtil.setLastUpdateCheckTime(this@MainActivity, now)
                }
                is UpdateChecker.UpdateResult.Error -> {
                    // 静默检查，错误不提示用户
                }
            }
        }
    }

    private fun displayAppVersion() {
        // 使用缓存的版本号，避免重复 IPC 调用
        tvSubtitle.text = "Version: v${cachedAppVersion ?: "--"}"
    }

    private fun setupAnimations() {
        // 交错入场序列：Header → 网络卡片 → 更新按钮 → 硬件卡片
        // 每个子元素内部也有微小的 cascade 延迟
        val entries = listOf(
            Triple(findViewById<View>(R.id.main_header), 0L, 60L),
            Triple(findViewById<View>(R.id.card_network), 70L, 30L),
            Triple(findViewById<View>(R.id.btn_check_update), 120L, 0L),
            Triple(findViewById<View>(R.id.card_device), 160L, 50L)
        )
        entries.forEachIndexed { index, (view, stagger, childOffset) ->
            view?.let {
                it.alpha = 0f
                it.translationY = 60f
                it.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(550)
                    .setStartDelay(index * stagger)
                    .setInterpolator(DecelerateInterpolator(2.5f))
                    .start()
            }
        }

        // 卡片内数据行依次淡入
        val dataViews = listOf(
            findViewById<View>(R.id.main_tv_model),
            findViewById<View>(R.id.main_tv_subtitle),
            findViewById<View>(R.id.main_iv_antenna), findViewById<View>(R.id.main_tv_net_signal),
            findViewById<View>(R.id.main_iv_temp), findViewById<View>(R.id.main_tv_temp),
            findViewById<View>(R.id.main_iv_cpu), findViewById<View>(R.id.main_tv_cpu),
            findViewById<View>(R.id.main_iv_chip), findViewById<View>(R.id.main_tv_mem),
            findViewById<View>(R.id.main_tv_daily), findViewById<View>(R.id.main_tv_flow),
        )
        dataViews.forEachIndexed { i, v ->
            v?.let {
                it.alpha = 0f
                it.animate()
                    .alpha(1f)
                    .setDuration(350)
                    .setStartDelay(300 + i * 40L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { it.alpha = 1f }
                    .start()
            }
        }
    }

    private fun initViews() {
        tvModel = findViewById(R.id.main_tv_model)

        // 设备名称 Q 弹回弹动画（纯视觉反馈，无实际功能）
        apply {
            val overshoot = OvershootInterpolator(1.3f)
            var pressAnim: AnimatorSet? = null
            var releaseAnim: AnimatorSet? = null
            tvModel.setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        pressAnim?.cancel()
                        releaseAnim?.cancel()
                        pressAnim = AnimatorSet().apply {
                            playTogether(
                                ObjectAnimator.ofFloat(v, "scaleX", 0.92f),
                                ObjectAnimator.ofFloat(v, "scaleY", 0.92f)
                            )
                            duration = 100
                            start()
                        }
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        pressAnim?.cancel()
                        releaseAnim = AnimatorSet().apply {
                            playTogether(
                                ObjectAnimator.ofFloat(v, "scaleX", 1f),
                                ObjectAnimator.ofFloat(v, "scaleY", 1f)
                            )
                            duration = 250
                            interpolator = overshoot
                            start()
                        }
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        pressAnim?.cancel()
                        releaseAnim = AnimatorSet().apply {
                            playTogether(
                                ObjectAnimator.ofFloat(v, "scaleX", 1f),
                                ObjectAnimator.ofFloat(v, "scaleY", 1f)
                            )
                            duration = 150
                            start()
                        }
                    }
                }
                true
            }
        }
        tvSubtitle = findViewById(R.id.main_tv_subtitle)
        tvNetSignal = findViewById(R.id.main_tv_net_signal)
        tvTemp = findViewById(R.id.main_tv_temp)
        tvCpu = findViewById(R.id.main_tv_cpu)
        tvMem = findViewById(R.id.main_tv_mem)
        tvDaily = findViewById(R.id.main_tv_daily)
        tvFlow = findViewById(R.id.main_tv_flow)
        tvFirmware = findViewById(R.id.main_tv_firmware)
        tvFirmwareSub = findViewById(R.id.main_tv_firmware_sub)
        tvBattery = findViewById(R.id.main_tv_battery)
        tvBatterySub = findViewById(R.id.main_tv_battery_sub)
        tvStorage = findViewById(R.id.main_tv_storage)
        tvClientIp = findViewById(R.id.main_tv_client_ip)

        // 缓存信号图标 ImageView 引用，避免每次刷新 findViewById
        ivAntenna = findViewById(R.id.main_iv_antenna)

        // 缓存 App 版本号（进程生命周期内不变）
        cachedAppVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { null }

        // 网络 点击 → 网络详情 (RSRP/SINR/RSRQ)
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_network)) {
            viewModel.getWifiEntity()?.let { showNetworkDetailDialog(it) }
        }
        // 温度 点击 → 温度详情
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_temp)) {
            viewModel.getWifiEntity()?.let { showTemperatureDialog(it) }
        }
        // CPU 点击 → 详情弹窗
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_cpu)) {
            viewModel.getWifiEntity()?.let { showCpuDetailDialog(it) }
        }
        // 内存 点击 → 详情弹窗
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_mem)) {
            viewModel.getWifiEntity()?.let { showMemDetailDialog(it) }
        }
        // 存储 点击 → 详情弹窗
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_storage)) {
            viewModel.getWifiEntity()?.let { showStorageDetailDialog(it) }
        }
        // 固件 点击 → 详情弹窗
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_firmware)) {
            viewModel.getWifiEntity()?.let { showFirmwareDetailDialog(it) }
        }
        // IP 点击 → 详情弹窗
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_ip)) {
            viewModel.getWifiEntity()?.let { showIpDetailDialog(it) }
        }
        // 电池 点击 → 详情弹窗
        AnimationUtil.applyScaleClickAnimation(findViewById(R.id.main_item_battery)) {
            viewModel.getWifiEntity()?.let { showBatteryDetailDialog(it) }
        }

        // 状态视图
        mainContentView = findViewById(R.id.main_content)
        loadingView = findViewById(R.id.loading_view)

        // 初始：显示加载界面，隐藏主内容
        loadingView.visibility = View.VISIBLE
        mainContentView.visibility = View.GONE

        findViewById<View>(R.id.btn_settings).apply {
            AnimationUtil.applyScaleClickAnimation(this) {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        // 检查更新按钮：使用 applyScaleClickAnimation 统一按压动画 + 点击回调
        // 修复旧代码 setOnTouchListener + setOnClickListener 冲突导致 onClick 不触发的 Bug
        setupCheckUpdateButton()

        // 启动载入动画（文字点动画 + 容器脉冲）
        startLoadingDotAnimation()
        startLoadingPulse()
    }

    /**
     * 初始化检查更新按钮：ObjectAnimator Q 弹缩放动画 + 点击触发更新检查。
     *
     * 将 TouchListener 设置在内部 TextView 上（绕开 <include> 标签的 ViewPropertyAnimator 兼容问题），
     * 使用 ObjectAnimator + AnimatorSet 提供可靠的按压缩放 + OvershootInterpolator 弹性回弹。
     */
    private fun setupCheckUpdateButton() {
        val btnRoot = findViewById<View>(R.id.btn_check_update)
        val btnText = btnRoot.findViewById<TextView>(R.id.common_btn_text)
        btnText.text = "检查更新"
        btnText.isClickable = true
        btnText.isFocusable = true

        val overshoot = OvershootInterpolator(1.3f)
        var pressAnim: AnimatorSet? = null
        var releaseAnim: AnimatorSet? = null

        btnText.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pressAnim?.cancel()
                    releaseAnim?.cancel()
                    pressAnim = AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(v, "scaleX", 0.92f),
                            ObjectAnimator.ofFloat(v, "scaleY", 0.92f)
                        )
                        duration = 100
                        start()
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    pressAnim?.cancel()
                    releaseAnim = AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(v, "scaleX", 1f),
                            ObjectAnimator.ofFloat(v, "scaleY", 1f)
                        )
                        duration = 250
                        interpolator = overshoot
                        start()
                    }
                    if (event.x >= 0 && event.x <= v.width &&
                        event.y >= 0 && event.y <= v.height) {
                        v.performClick()
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    pressAnim?.cancel()
                    releaseAnim = AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(v, "scaleX", 1f),
                            ObjectAnimator.ofFloat(v, "scaleY", 1f)
                        )
                        duration = 150
                        start()
                    }
                }
            }
            true
        }
        btnText.setOnClickListener {
            if (isCheckingUpdate) return@setOnClickListener
            performCheckUpdate(btnRoot, btnText)
        }
    }

    /**
     * 执行更新检查，期间按钮半透明且不可重复点击，文字显示 "检查中" 点循环动画，
     * 完成后显示结果文字并自动恢复。
     */
    private fun performCheckUpdate(btnRoot: View, btnText: TextView) {
        isCheckingUpdate = true
        btnRoot.alpha = 0.65f
        btnRoot.isEnabled = false

        // "检查中" 文字点动画（检查中 → 检查中. → 检查中.. → 检查中... 循环）
        val dotJob = lifecycleScope.launch {
            val base = "检查中"
            val dots = arrayOf("", ".", "..", "...")
            var i = 0
            while (true) {
                btnText.text = base + dots[i % dots.size]
                i++
                delay(400)
            }
        }

        lifecycleScope.launch {
            val result = try {
                UpdateChecker.checkUpdate(this@MainActivity)
            } catch (e: Exception) {
                UpdateChecker.UpdateResult.Error("检查失败: ${e.message ?: "未知错误"}")
            }

            dotJob.cancel()
            isCheckingUpdate = false
            btnRoot.alpha = 1f
            btnRoot.isEnabled = true

            when (result) {
                is UpdateChecker.UpdateResult.NewVersion -> {
                    btnText.text = "发现新版本"
                    showUpdateDialog(result.info)
                    lifecycleScope.launch {
                        delay(3000)
                        btnText.text = "检查更新"
                    }
                }
                is UpdateChecker.UpdateResult.Latest -> {
                    btnText.text = "已是最新版本 ✓"
                    ToastUtil.showDropToast(this@MainActivity, ToastStyle.SUCCESS, "当前已是最新版本")
                    lifecycleScope.launch {
                        delay(2500)
                        btnText.text = "检查更新"
                    }
                }
                is UpdateChecker.UpdateResult.Error -> {
                    btnText.text = "检查失败"
                    ToastUtil.showDropToast(this@MainActivity, ToastStyle.WARNING, result.message)
                    lifecycleScope.launch {
                        delay(2500)
                        btnText.text = "检查更新"
                    }
                }
            }
        }
    }

    /** "请稍候" 文字点动画（. → .. → ... 循环） */
    private fun startLoadingDotAnimation() {
        lifecycleScope.launch {
            val hintView = findViewById<TextView>(R.id.loading_hint)
            val baseText = "请稍候"
            val dots = arrayOf("", ".", "..", "...")
            var index = 0
            while (loadingView.visibility == View.VISIBLE) {
                hintView?.text = baseText + dots[index]
                index = (index + 1) % dots.size
                delay(500)
            }
        }
    }

    /** 加载容器呼吸式脉冲缩放 */
    private fun startLoadingPulse() {
        if (loadingView.visibility != View.VISIBLE || isFinishing || isDestroyed) return
        val container = findViewById<View>(R.id.loading_container) ?: return
        container.post {
            if (loadingView.visibility != View.VISIBLE) return@post
            container.pivotX = container.width / 2f
            container.pivotY = container.height / 2f
            container.animate()
                .scaleX(1.03f).scaleY(1.03f)
                .setDuration(1000)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                .withEndAction {
                    container.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(1000)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                        .withEndAction {
                            startLoadingPulse()
                        }
                        .start()
                }
                .start()
        }
    }

    // ==================== 信号图标（同小组件实现） ====================

    /** 根据 RSRP 信号值更新网络图标，同 WifiWidget.parseSignalLevel */
    private fun updateSignalIcon(signal: String) {
        val level = parseSignalLevel(signal)
        val resId = when (level) {
            0 -> R.drawable.ic_signal_0
            1 -> R.drawable.ic_signal_1
            2 -> R.drawable.ic_signal_2
            3 -> R.drawable.ic_signal_3
            4 -> R.drawable.ic_signal_4
            5 -> R.drawable.ic_signal_5
            else -> R.drawable.ic_signal_0
        }
        // 使用缓存的 ImageView 引用，避免每次 findViewById
        if (::ivAntenna.isInitialized) {
            ivAntenna.setImageResource(resId)
        }
    }

    /** 从 RSRP dBm 信号值推算 0-5 格信号强度 */
    private fun parseSignalLevel(signal: String): Int {
        return try {
            val raw = signal.replace("dBm", "").trim().toIntOrNull() ?: 0
            // RSRP 应为负值；若为正值则取反（兼容部分设备返回绝对值的情况）
            val rssi = if (raw > 0) -raw else raw
            if (rssi >= 0) return 0   // 0 或无法解析 → 无信号
            when {
                rssi > -85  -> 5
                rssi >= -95 -> 4
                rssi >= -105 -> 3
                rssi >= -115 -> 2
                else         -> 1
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun refreshData() {
        DebugLogger.logApi(TAG, "refreshData() started")
        viewModel.refreshData(
            ctx = this,
            onSuccess = { data ->
                val wasLoading = loadingView.visibility == View.VISIBLE
                hideLoadingView(wasLoading)
                if (wasLoading && !hasShownFirstLoadAnimation) {
                    hasShownFirstLoadAnimation = true
                    setupAnimations()
                    DebugLogger.logUi(TAG, "First load complete, setupAnimations called")
                }
                applyWifiEntityToUi(data)
                refreshActiveDialog(data)
            },
            onError = { reason ->
                // 冷启动首次刷新失败时，隐藏加载动画并回退到已有缓存数据
                if (loadingView.visibility == View.VISIBLE) {
                    hideLoadingView(true)
                    viewModel.getWifiEntity()?.let { cachedData ->
                        if (!hasShownFirstLoadAnimation) {
                            hasShownFirstLoadAnimation = true
                            setupAnimations()
                        }
                        applyWifiEntityToUi(cachedData)
                    }
                }
                showErrorDialog()
            },
            onToast = { msg, _ ->
                if (!isFinishing && !isDestroyed) {
                    ToastUtil.showDropToast(this@MainActivity, ToastStyle.INFO, msg)
                }
            }
        )
    }

    /**
     * 将 [WifiEntity] 各字段应用到主界面 UI。
     * 由 refreshData() 成功分支和旋转恢复时调用。
     */
    private fun applyWifiEntityToUi(data: WifiEntity) {
        DebugLogger.logApi(TAG, "applyWifiEntityToUi: model=${data.model}")

        // ── 数据变更检测：计算核心字段的快速哈希，未变时跳过大部分 UI 更新 ──
        val quickHash = computeQuickUiHash(data)
        val dataUnchanged = quickHash == lastUiDataHash && lastUiDataHash != 0L
        lastUiDataHash = quickHash

        // 设备名称 & 软件版本（使用缓存，避免 IPC 调用）
        val deviceName = data.deviceModel.ifEmpty { data.model }
        if (tvModel.text != deviceName) tvModel.text = deviceName
        // 使用缓存的 App 版本号，不再每次刷新都调用 packageManager.getPackageInfo()
        val subText = "Version v${cachedAppVersion ?: "--"}"
        if (tvSubtitle.text != subText) tvSubtitle.text = subText

        if (dataUnchanged) {
            DebugLogger.logUi(TAG, "applyWifiEntityToUi: data unchanged, skipping UI updates")
            return
        }

        // 网络制式
        val info = data.atNetworkInfo
        val newNetText = if (info != null) {
            val carrierText = info.carrier.ifEmpty { info.operator }
            val typeText = info.networkType.replace(" NSA", "").replace(" SA", "")
                .ifEmpty { data.netType }
            if (carrierText.isNotEmpty() && typeText.isNotEmpty() && !typeText.contains(carrierText)) {
                "$carrierText $typeText"
            } else {
                typeText.ifEmpty { carrierText.ifEmpty { "--" } }
            }
        } else {
            data.netType.ifEmpty { "--" }
        }
        DebugLogger.logUi(TAG, "Updating UI: net=$newNetText")
        AnimationUtil.smoothUpdateText(tvNetSignal, newNetText)
        // 根据信号值更新网络图标（同小组件实现）
        updateSignalIcon(data.signal)
        AnimationUtil.smoothUpdateText(tvTemp, MainDialogHelper.getHighestTemp(data))
        AnimationUtil.smoothUpdateText(tvCpu, data.cpu.ifEmpty { "--" })
        AnimationUtil.smoothUpdateText(tvMem, data.mem.ifEmpty { "--" })
        AnimationUtil.smoothUpdateText(tvDaily, data.dailyFlow.ifEmpty { "--" })
        AnimationUtil.smoothUpdateText(tvFlow, data.flow.ifEmpty { "--" })

        // 电池
        val (batteryPct, batterySub) = MainDialogHelper.buildBatteryParts(data)
        AnimationUtil.smoothUpdateText(tvBattery, batteryPct)
        if (batterySub != null) {
            if (tvBatterySub.visibility != View.VISIBLE) {
                tvBatterySub.text = batterySub
                tvBatterySub.alpha = 0f
                tvBatterySub.visibility = View.VISIBLE
                tvBatterySub.animate().alpha(1f).setDuration(300).start()
            } else {
                AnimationUtil.smoothUpdateText(tvBatterySub, batterySub)
            }
        } else {
            tvBatterySub.visibility = View.GONE
        }

        // 固件与存储
        val fwMain = data.appVer.ifEmpty { data.firmwareVer.ifEmpty { "--" } }
        AnimationUtil.smoothUpdateText(tvFirmware, fwMain)
        if (data.appVerCode.isNotEmpty()) {
            val fwSub = "build ${data.appVerCode}"
            if (tvFirmwareSub.visibility != View.VISIBLE) {
                tvFirmwareSub.text = fwSub
                tvFirmwareSub.alpha = 0f
                tvFirmwareSub.visibility = View.VISIBLE
                tvFirmwareSub.animate().alpha(1f).setDuration(300).start()
            } else {
                AnimationUtil.smoothUpdateText(tvFirmwareSub, fwSub)
            }
        } else {
            tvFirmwareSub.visibility = View.GONE
        }
        AnimationUtil.smoothUpdateText(tvStorage, data.internalStorage.ifEmpty { "--" })
        AnimationUtil.smoothUpdateText(tvClientIp, data.clientIp.ifEmpty { "--" })

        // ════ 通知提醒检测 ════
        NotificationHelper.checkAndNotify(
            context = this,
            dailyFlowStr = data.dailyFlow,
            monthlyFlowStr = data.flow,
            tempStr = MainDialogHelper.getHighestTemp(data),
            cpuStr = data.cpu,
            memStr = data.mem,
            batteryPercent = data.batteryPercent,
            isDeviceOnline = !WifiWorker.isWorkerStopped(this),
            activity = this
        )
    }

    /**
     * 计算主界面核心数据的快速哈希值，用于检测数据是否有变化。
     * 仅包含会在 UI 上显示的字段，避免不必要的 UI 重绘。
     * 使用 Long 类型减少碰撞概率（相比 Int.hashCode()）。
     */
    private fun computeQuickUiHash(data: WifiEntity): Long {
        var h = 17L
        h = 31 * h + data.signal.hashCode()
        h = 31 * h + data.cpu.hashCode()
        h = 31 * h + data.mem.hashCode()
        h = 31 * h + data.dailyFlow.hashCode()
        h = 31 * h + data.flow.hashCode()
        h = 31 * h + data.temp.hashCode()
        h = 31 * h + data.battery.hashCode()
        h = 31 * h + data.batteryCurrent.hashCode()
        h = 31 * h + data.internalStorage.hashCode()
        h = 31 * h + data.clientIp.hashCode()
        h = 31 * h + data.appVer.hashCode()
        h = 31 * h + data.appVerCode.hashCode()
        h = 31 * h + data.firmwareVer.hashCode()
        h = 31 * h + data.netType.hashCode()
        h = 31 * h + (data.atNetworkInfo?.carrier.hashCode().toLong())
        h = 31 * h + (data.atNetworkInfo?.networkType.hashCode().toLong())
        return h
    }

    /** 隐藏加载界面，根据参数决定是否执行淡出动画 */
    private fun hideLoadingView(animate: Boolean) {
        if (loadingView.visibility != View.VISIBLE) return
        // 立即停止加载容器脉冲动画
        findViewById<View>(R.id.loading_container)?.animate()?.cancel()
        if (animate) {
            loadingView.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    loadingView.visibility = View.GONE
                    loadingView.alpha = 1f
                }
                .start()
            mainContentView.alpha = 0f
            mainContentView.visibility = View.VISIBLE
            mainContentView.animate()
                .alpha(1f)
                .setDuration(350)
                .setStartDelay(80)
                .withEndAction { mainContentView.alpha = 1f }
                .start()
        } else {
            loadingView.visibility = View.GONE
            mainContentView.visibility = View.VISIBLE
        }
    }

    /**
     * 启动时检测上次崩溃标志，若存在则弹出对话框引导用户查看日志。
     * 该弹窗优先级低于 error 弹窗，仅在主流程成功后调用。
     */
    private fun checkCrashLogOnStartup() {
        val crashTime = SPUtil.getLastCrashTime(this)
        if (crashTime <= 0) return

        val summary = SPUtil.getLastCrashSummary(this)
        DebugLogger.logExc(TAG, "checkCrashLogOnStartup: last crash=$crashTime, summary=$summary")

        // 延迟 1 秒弹出，避免与 loading → content 过渡动画冲突
        lifecycleScope.launch {
            delay(1000)
            if (isFinishing || isDestroyed) return@launch

            try {
                // 使用通用弹窗展示崩溃提示
                val dialog = showCommonDialog(
                    type = "crash",
                    title = "检测到异常退出",
                    iconRes = android.R.drawable.ic_dialog_alert,
                    onFill = { content ->
                        content.addView(MainDialogHelper.sectionTitleView(this@MainActivity, "崩溃信息"))
                        val timeStr = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date(crashTime))
                        content.addView(MainDialogHelper.keyValueView(this@MainActivity, "发生时间", timeStr))
                        if (summary.isNotEmpty()) {
                            content.addView(MainDialogHelper.keyValueView(this@MainActivity, "异常摘要", summary.take(150)))
                        }
                        content.addView(MainDialogHelper.keyValueView(this@MainActivity, "提示", "日志已自动保存（已脱敏），建议分享给开发者以帮助修复问题"))
                    },
                    primaryBtnText = "查看并分享日志",
                    onPrimaryClick = { dlg ->
                        dlg.dismiss()
                        SPUtil.clearCrashInfo(this@MainActivity)
                        val intent = Intent(this@MainActivity, DebugLogActivity::class.java).apply {
                            putExtra(DebugLogActivity.EXTRA_CRASH_MODE, true)
                        }
                        startActivity(intent)
                    },
                    secondaryBtnText = "忽略",
                    onSecondaryClick = { dlg ->
                        dlg.dismiss()
                        SPUtil.clearCrashInfo(this@MainActivity)
                        CrashHandler.clearCrashLog(this@MainActivity)
                        ToastUtil.showDropToast(this@MainActivity, ToastStyle.SUCCESS, "崩溃记录已清除")
                    }
                )
            } catch (e: Exception) {
                DebugLogger.logExc(TAG, "Failed to show crash dialog: ${e.message}")
                // 静默清除崩溃标志，避免反复弹窗
                SPUtil.clearCrashInfo(this@MainActivity)
            }
        }
    }

    /**
     * 显示错误状态弹窗
     */
    private fun showErrorDialog() {
        if (viewModel.activeDialogType.value == "error") return
        dismissActiveDialog()

        val reason = SPUtil.getWorkerStopReason(this)
        val address = SPUtil.getDeviceAddress(this)

        DebugLogger.logSys(TAG, "showErrorDialog: reason=$reason, target=$address")

        val title: String
        val message: String
        val hint: String
        val iconRes: Int

        when (reason) {
            WifiWorker.REASON_NETWORK -> {
                title = "无法与设备通信"
                message = "无法连接到设备 ($address)，后台刷新已自动暂停以节省电量。\n\n" +
                        "可能原因：\n" +
                        "• 设备未开机或不在同一内网\n" +
                        "• 设备 IP 地址配置有误\n" +
                        "• 设备网络异常"
                hint = "设备恢复上线后将自动恢复刷新"
                iconRes = R.drawable.ic_router_off
            }
            WifiWorker.REASON_API -> {
                title = "连接配置异常"
                message = "设备网络可达，但 API 请求连续失败，后台刷新已自动暂停以节省电量。\n\n" +
                        "可能原因：\n" +
                        "• 设备端口配置错误\n" +
                        "• Token / 管理口令不正确或已更改\n" +
                        "• 设备 API 服务异常"
                hint = "请修改配置后点击「重新连接」重试"
                iconRes = R.drawable.ic_router_off
            }
            else -> {
                title = "连接失败"
                message = "连续多次无法连接到设备，后台刷新已自动暂停以节省电量。\n\n" +
                        "请检查设备状态和连接配置后重试。"
                hint = "设备恢复上线后将自动恢复刷新"
                iconRes = R.drawable.ic_router_off
            }
        }

        val dialog = showCommonDialog(
            type = "error",
            title = title,
            iconRes = iconRes,
            onFill = { content ->
                // 消息文本
                content.addView(TextView(this).apply {
                    text = message
                    textSize = 14f
                    setTextColor(ThemeColors.textPrimary(this@MainActivity))
                    setLineSpacing(0f, 1.3f)
                })
                
                // 目标地址行
                content.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 12.dpToPx() }
                    alpha = 0.7f
                    
                    addView(ImageView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(14.dpToPx(), 14.dpToPx()).apply {
                            rightMargin = 6.dpToPx()
                        }
                        setImageResource(R.drawable.ic_antenna)
                        setColorFilter(ThemeColors.textPrimary(this@MainActivity))
                    })
                    
                    addView(TextView(this@MainActivity).apply {
                        text = address
                        textSize = 13f
                        setTextColor(ThemeColors.textPrimary(this@MainActivity))
                        typeface = android.graphics.Typeface.MONOSPACE
                    })
                })
                
                // 提示文本
                content.addView(TextView(this).apply {
                    text = hint
                    textSize = 12f
                    setTextColor(ThemeColors.textSecondary(this@MainActivity))
                    alpha = 0.6f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 14.dpToPx() }
                })
            },
            primaryBtnText = "重新连接",
            onPrimaryClick = { d ->
                WifiWorker.resetFailureState(this@MainActivity)
                BaseWifiWidget.renderAllWidgets(this@MainActivity)
                d.dismiss()
                loadingView.visibility = View.VISIBLE
                mainContentView.visibility = View.GONE
                stopAutoRefreshTimer()
                refreshData()
            },
            secondaryBtnText = "修改连接配置",
            onSecondaryClick = {
                startActivity(Intent(this@MainActivity, ConfigModifyActivity::class.java))
            }
        )
        
        dialog?.setCancelable(false)
        dialog?.setCanceledOnTouchOutside(false)
        stopAutoRefreshTimer()
    }
    
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun startWorker(minutes: Int) {
        DebugLogger.logSys(TAG, "startWorker: minutes=$minutes")
        val workManager = WorkManager.getInstance(this)
        if (minutes <= 0) {
            DebugLogger.logSys(TAG, "startWorker: minutes<=0, cancelling worker")
            workManager.cancelUniqueWork("wifi_crawl")
            return
        }
        // 只在 Worker 当前未 stopped 时才重置 — 如果已 stopped，保持 stopped 状态让用户看到错误界面
        // Worker 内部自己会处理恢复逻辑（ping 通过后自动解除 stopped）
        val wasStopped = WifiWorker.isWorkerStopped(this)
        DebugLogger.logSys(TAG, "startWorker: wasStopped=$wasStopped")
        if (!wasStopped) {
            WifiWorker.resetFailureState(this)
        } else {
            DebugLogger.logSys(TAG, "startWorker: worker is stopped, NOT resetting failure state")
        }
        val workRequest = PeriodicWorkRequestBuilder<WifiWorker>(minutes.toLong(), TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork(
            "wifi_crawl",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    /**
     * 检查 Worker 是否因失败被停止。
     * 失败时显示错误弹窗。
     *
     * 如果 Worker 已恢复，关闭弹窗。
     */
    private fun checkWorkerFailureState() {
        val stopped = WifiWorker.isWorkerStopped(this)
        DebugLogger.logSys(TAG, "checkWorkerFailureState: stopped=$stopped")

        if (!stopped) {
            // Worker 正常 → 如果错误弹窗已显示则关闭
            if (viewModel.activeDialogType.value == "error") {
                DebugLogger.logSys(TAG, "checkWorkerFailureState: stopped=false, dismissing error dialog")
                dismissActiveDialog()
            }
            return
        }

        // Worker 已停止 → 显示错误弹窗
        showErrorDialog()
    }

    /**
     * 启动前台自动刷新定时器（基于主界面刷新间隔设置）
     */
    private fun startAutoRefreshTimer() {
        stopAutoRefreshTimer()
        val intervalSeconds = SPUtil.getMainRefreshSeconds(this)
        if (intervalSeconds <= 0) return // 用户关闭了自动刷新

        autoRefreshJob = lifecycleScope.launch {
            while (true) {
                delay(intervalSeconds * 1000L)
                if (!isFinishing && !isDestroyed) {
                    refreshData()
                }
            }
        }
    }

    /**
     * 停止前台自动刷新定时器
     */
    private fun stopAutoRefreshTimer() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    // ===== 详情弹窗（主题统一 + 实时刷新） =====

    /** 创建主题统一的 Dialog，带防拥堵 + 弹窗动效 + 动态模糊背景（含退出渐变） */
    private fun createThemedDialog(layoutRes: Int, widthRatio: Float = 0.92f, dialogType: String): Dialog {
        // 同类型弹窗已显示 → 直接复用，避免叠加/闪烁
        if (activeDialog?.isShowing == true && viewModel.activeDialogType.value == dialogType) {
            return activeDialog!!
        }
        // 不同类型 → 关闭旧的，创建新的
        dismissActiveDialog()

        val dialog = CommonDialogHelper.createAnimatedDialog(this) {
            activeDialog = null
            viewModel.setActiveDialogType(null)
        }

        dialog.setContentView(layoutRes)

        // ── 动态应用主题色到弹窗根布局 ──
        CommonDialogHelper.applyThemeToDialogRoot(this, dialog)

        dialog.setCanceledOnTouchOutside(true)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.01f)
            setWindowAnimations(R.style.DialogAnimationTheme)
            val width = (resources.displayMetrics.widthPixels * widthRatio).toInt()
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // ── 动态模糊背景：API 31+ 原生模糊，API 26-30 bitmap 缩放模糊 ──
        CommonDialogHelper.applyDialogBlur(this, dialog)

        // 清理逻辑已由 createAnimatedDialog 的 onDismissed 回调处理，无需重复设置
        activeDialog = dialog
        viewModel.setActiveDialogType(dialogType)
        return dialog
    }

    /** 弹窗防拥堵检查：返回 true 允许创建/显示 */
    private fun checkDialogDebounce(): Boolean {
        // 如果当前已有正在显示的弹窗且不是 error 弹窗，禁止开启新的
        // 增加 null 判定，确保关闭过程中也能及时重置状态
        val isShowing = activeDialog?.isShowing == true
        if (isShowing && viewModel.activeDialogType.value != "error") {
            return false
        }
        val now = System.currentTimeMillis()
        if (now - lastDialogClickTime < 300L) return false // 缩短 debounce 时间
        lastDialogClickTime = now
        return true
    }

    /** 弹窗内容容器（ScrollView 内的 LinearLayout） */
    private fun LinearLayout.clearAndFill(block: LinearLayout.() -> Unit) {
        removeAllViews()
        block()
    }

    private fun dismissActiveDialog() {
        try { activeDialog?.dismiss() } catch (_: Exception) {
            // Dialog.dismiss 在 Window 已销毁时可能抛异常，非关键错误
        }
        activeDialog = null
        viewModel.setActiveDialogType(null)
    }

    /** 数据刷新后自动更新已打开的弹窗内容 */
    private fun refreshActiveDialog(data: WifiEntity) {
        val dialog = activeDialog
        if (dialog == null || !dialog.isShowing) {
            activeDialog = null
            viewModel.setActiveDialogType(null)
            return
        }
        
        // 尝试获取通用内容容器
        val content = try { 
            activeDialog!!.findViewById<LinearLayout>(R.id.common_dialog_content) 
        } catch (_: Exception) {
            // 弹窗尚未完全初始化时 findViewById 可能失败
            null
        } ?: return

        with(MainDialogHelper) {
            when (viewModel.activeDialogType.value) {
                "temperature" -> {
                    val currentHash = data.cpuTempList.hashCode().toLong()
                    if (content.tag != currentHash) {
                        content.removeAllViews()
                        content.fillTemperature(this@MainActivity, data)
                        content.tag = currentHash
                    }
                }
                "cpu" -> {
                    val currentHash = data.cpuFreqInfo.hashCode().toLong() + data.cpuUsageInfo.hashCode().toLong()
                    if (content.tag != currentHash) {
                        content.removeAllViews()
                        content.fillCpuDetail(this@MainActivity, data)
                        content.tag = currentHash
                    }
                }
                "mem" -> {
                    val currentHash = data.memUsedKb + data.memAvailableKb + data.swapUsedKb
                    if (content.tag != currentHash) {
                        content.removeAllViews()
                        content.fillMemDetail(this@MainActivity, data)
                        content.tag = currentHash
                    }
                }
                "storage" -> {
                    val currentHash = data.internalUsedStorage + data.externalUsedStorage
                    if (content.tag != currentHash) {
                        content.removeAllViews()
                        content.fillStorageDetail(this@MainActivity, data)
                        content.tag = currentHash
                    }
                }
                "firmware" -> {
                    val currentHash = data.appVer.hashCode().toLong() + data.webVersion.hashCode().toLong()
                    if (content.tag != currentHash) {
                        content.removeAllViews()
                        content.fillFirmwareDetail(this@MainActivity, data)
                        content.tag = currentHash
                    }
                }
                "ip" -> {
                    val currentHash = data.clientIp.hashCode().toLong() + data.wanIp.hashCode().toLong() +
                                     (data.atNetworkInfo?.wanIpAt?.hashCode()?.toLong() ?: 0L)
                    if (content.tag != currentHash) {
                        content.removeAllViews()
                        content.fillIpDetail(this@MainActivity, data)
                        content.tag = currentHash
                    }
                }
                "battery" -> {
                    val currentHash = data.battery.hashCode().toLong() + data.batteryCurrent.hashCode().toLong() + data.batteryVoltage.hashCode().toLong()
                    if (content.tag != currentHash) {
                        content.removeAllViews()
                        content.fillBatteryDetail(this@MainActivity, data)
                        content.tag = currentHash
                    }
                }
                "network" -> {
                    val currentHash = data.atNetworkInfo?.hashCode()?.toLong() ?: 0L
                    if (content.tag != currentHash) {
                        content.removeAllViews()
                        content.fillNetworkDetail(this@MainActivity, data)
                        content.tag = currentHash
                    }
                }
            }
        }
        
        // 动态数据刷新后，重新评估弹窗高度（安全守卫：弹窗可能在刷新期间被用户关闭）
        activeDialog?.let { dialog ->
            if (dialog.isShowing) {
                com.ufi_toolswidget.util.PopupViewUtil.autoAdjustDialogHeight(this, dialog, 0.92f)
            }
        }
    }

    /**
     * 显示通用主题弹窗的公共逻辑
     */
    private fun showCommonDialog(
        type: String,
        title: String,
        iconRes: Int,
        onFill: (LinearLayout) -> Unit,
        primaryBtnText: String = "关闭",
        onPrimaryClick: ((Dialog) -> Unit)? = null,
        secondaryBtnText: String? = null,
        onSecondaryClick: ((Dialog) -> Unit)? = null
    ): Dialog? {
        if (!checkDialogDebounce()) return null
        
        val dialog = createThemedDialog(R.layout.layout_common_dialog, dialogType = type)
        
        // 如果是错误弹窗，禁止点击外部关闭，强制用户处理
        if (type == "error") {
            dialog.setCanceledOnTouchOutside(false)
            dialog.setCancelable(false)
        } else {
            dialog.setCanceledOnTouchOutside(true)
            dialog.setCancelable(true)
        }
        
        dialog.findViewById<TextView>(R.id.common_dialog_title).text = title
        dialog.findViewById<ImageView>(R.id.common_dialog_icon).setImageResource(iconRes)
        
        val content = dialog.findViewById<LinearLayout>(R.id.common_dialog_content)
        content.clearAndFill { onFill(this) }
        
        val btnPrimary = dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_primary)
        btnPrimary.text = primaryBtnText
        AnimationUtil.applyScaleClickAnimation(btnPrimary) {
            if (onPrimaryClick != null) onPrimaryClick(dialog) else dialog.dismiss()
        }
        
        val btnSecondary = dialog.findViewById<MaterialButton>(R.id.common_dialog_btn_secondary)
        if (secondaryBtnText != null) {
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.text = secondaryBtnText
            AnimationUtil.applyScaleClickAnimation(btnSecondary) {
                onSecondaryClick?.invoke(dialog) ?: dialog.dismiss()
            }
            // 两个按钮时，主按钮保持间距
            (btnPrimary.layoutParams as? LinearLayout.LayoutParams)?.marginStart = 12.dpToPx()
        } else {
            btnSecondary.visibility = View.GONE
            // 只有一个按钮时，主按钮消除间距以占满宽度
            (btnPrimary.layoutParams as? LinearLayout.LayoutParams)?.marginStart = 0
        }
        
        // ── 动态高度适配：在填充内容后、展示前进行测量 ──
        com.ufi_toolswidget.util.PopupViewUtil.autoAdjustDialogHeight(this, dialog, 0.92f)
        
        dialog.show()
        return dialog
    }

    // ---------- 各弹窗 ----------

    private fun showNetworkDetailDialog(data: WifiEntity) {
        val dialog = showCommonDialog(
            type = "network",
            title = "网络详情",
            iconRes = R.drawable.ic_antenna,
            onFill = { with(MainDialogHelper) { it.fillNetworkDetail(this@MainActivity, data) } }
        )
        dialog?.findViewById<LinearLayout>(R.id.common_dialog_content)?.tag = 
            data.atNetworkInfo?.hashCode()?.toLong() ?: 0L
    }

    private fun showFirmwareDetailDialog(data: WifiEntity) {
        val dialog = showCommonDialog(
            type = "firmware",
            title = "固件与版本",
            iconRes = R.drawable.ic_check,
            onFill = { with(MainDialogHelper) { it.fillFirmwareDetail(this@MainActivity, data) } }
        )
        dialog?.findViewById<LinearLayout>(R.id.common_dialog_content)?.tag = 
            data.appVer.hashCode().toLong() + data.webVersion.hashCode().toLong()
    }

    private fun showIpDetailDialog(data: WifiEntity) {
        val dialog = showCommonDialog(
            type = "ip",
            title = "网络地址详情",
            iconRes = R.drawable.ic_router,
            onFill = { with(MainDialogHelper) { it.fillIpDetail(this@MainActivity, data) } }
        )
        dialog?.findViewById<LinearLayout>(R.id.common_dialog_content)?.tag = 
            data.clientIp.hashCode().toLong() + data.wanIp.hashCode().toLong() + 
            (data.atNetworkInfo?.wanIpAt?.hashCode()?.toLong() ?: 0L)
    }

    private fun showTemperatureDialog(data: WifiEntity) {
        val dialog = showCommonDialog(
            type = "temperature",
            title = "设备温度",
            iconRes = R.drawable.ic_temp,
            onFill = { with(MainDialogHelper) { it.fillTemperature(this@MainActivity, data) } }
        )
        dialog?.findViewById<LinearLayout>(R.id.common_dialog_content)?.tag = 
            data.cpuTempList.hashCode().toLong()
    }

    private fun showCpuDetailDialog(data: WifiEntity) {
        val dialog = showCommonDialog(
            type = "cpu",
            title = "CPU 详情",
            iconRes = R.drawable.ic_cpu,
            onFill = { with(MainDialogHelper) { it.fillCpuDetail(this@MainActivity, data) } }
        )
        dialog?.findViewById<LinearLayout>(R.id.common_dialog_content)?.tag = 
            data.cpuFreqInfo.hashCode().toLong() + data.cpuUsageInfo.hashCode().toLong()
    }

    private fun showMemDetailDialog(data: WifiEntity) {
        val dialog = showCommonDialog(
            type = "mem",
            title = "内存详情",
            iconRes = R.drawable.ic_chip,
            onFill = { with(MainDialogHelper) { it.fillMemDetail(this@MainActivity, data) } }
        )
        dialog?.findViewById<LinearLayout>(R.id.common_dialog_content)?.tag = 
            data.memUsedKb + data.memAvailableKb + data.swapUsedKb
    }

    private fun showStorageDetailDialog(data: WifiEntity) {
        val dialog = showCommonDialog(
            type = "storage",
            title = "存储详情",
            iconRes = R.drawable.ic_router,
            onFill = { with(MainDialogHelper) { it.fillStorageDetail(this@MainActivity, data) } }
        )
        dialog?.findViewById<LinearLayout>(R.id.common_dialog_content)?.tag = 
            data.internalUsedStorage + data.externalUsedStorage
    }

    private fun showBatteryDetailDialog(data: WifiEntity) {
        val batteryLevel = data.batteryPercent
        val iconRes = when {
            batteryLevel >= 80 -> R.drawable.ic_battery_4
            batteryLevel >= 60 -> R.drawable.ic_battery_3
            batteryLevel >= 40 -> R.drawable.ic_battery_2
            batteryLevel >= 10 -> R.drawable.ic_battery_1
            else -> R.drawable.ic_battery_0
        }
        val dialog = showCommonDialog(
            type = "battery",
            title = "电池详情",
            iconRes = iconRes,
            onFill = { with(MainDialogHelper) { it.fillBatteryDetail(this@MainActivity, data) } }
        )
        dialog?.findViewById<LinearLayout>(R.id.common_dialog_content)?.tag =
            data.battery.hashCode().toLong() + data.batteryCurrent.hashCode().toLong() + data.batteryVoltage.hashCode().toLong()
    }

        // ── 以下方法已迁移至 MainDialogHelper ──
    // fillNetworkDetail, sectionTitleView, keyValueView, dividerView, emptyHintView,
    // getHighestTemp, buildBatteryParts, parseCurrentMa, formatKb, formatStorageGb

    // ===== 更新检查与下载 =====

    /**
     * 弹窗显示更新详情（合并显示最新更新日志）
     */
    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        val message = buildString {
            append("发现新版本: ${info.versionName}\n\n")
            if (info.changelog.isNotBlank()) {
                append("更新日志:\n")
                append(UpdateChecker.formatChangelog(info.changelog))
                append("\n\n")
            }
            if (info.apkSize > 0) {
                append("大小: ${UpdateChecker.formatFileSize(info.apkSize)}")
            }
        }

        showCommonDialog(
            type = "update",
            title = "更新可用",
            iconRes = R.drawable.ic_check,
            onFill = { content ->
                content.addView(TextView(this).apply {
                    text = message
                    textSize = 13f
                    setTextColor(ThemeColors.textPrimary(this@MainActivity))
                    setLineSpacing(0f, 1.4f)
                })
            },
            primaryBtnText = "下载更新",
            onPrimaryClick = { d ->
                d.dismiss()
                downloadAndInstall(info.apkUrl, info.tagName, info.apkSha256)
            },
            secondaryBtnText = "稍后"
        )
    }

    private fun downloadAndInstall(url: String, tag: String, sha256: String) {
        val receiver = UpdateChecker.prepareDownload(this, url, tag, sha256) { id ->
            downloadId = id
            ToastUtil.showDropToast(this, ToastStyle.INFO, "开始下载...")
        }
        if (receiver != null) {
            downloadReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
            downloadReceiver = receiver
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == UpdateChecker.PERMISSION_REQUEST_CODE && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            ToastUtil.showDropToast(this, ToastStyle.WARNING, "请重新点击下载")
        }
    }

    override fun onDestroy() {
        ThemeChangeNotifier.unregister(this, themeChangeReceiver)
        themeChangeReceiver = null
        downloadReceiver?.let { unregisterReceiver(it) }
        downloadReceiver = null
        super.onDestroy()
    }
}
