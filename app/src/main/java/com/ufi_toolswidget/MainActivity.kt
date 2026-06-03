package com.ufi_toolswidget

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.*
import android.widget.ImageView
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ufi_toolswidget.util.BackgroundUtil
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.SPUtil
import com.ufi_toolswidget.util.ThemeUtil
import com.ufi_toolswidget.util.UpdateChecker
import com.ufi_toolswidget.util.WifiCrawl
import com.ufi_toolswidget.widget.BaseWifiWidget
import com.ufi_toolswidget.worker.WifiWorker
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import java.io.File
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
    private lateinit var tvPower: TextView
    private lateinit var tvStorage: TextView
    private lateinit var tvClientIp: TextView

    // 错误状态覆盖层
    private lateinit var errorStateView: View
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView
    private lateinit var errorTarget: TextView
    private lateinit var errorIcon: ImageView
    private lateinit var errorHint: TextView
    private lateinit var mainContentView: View

    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null
    private var autoRefreshJob: Job? = null

    override fun onResume() {
        super.onResume()
        DebugLogger.d(TAG, "onResume() called")
        BackgroundUtil.applyWindowBackground(this)
        ThemeUtil.applyToMainActivity(this)
        if (::tvModel.isInitialized) {
            // 先检测 Worker 是否因连续失败被停止 — 必须在 refreshData 之前
            // 否则 refreshData 成功会重置失败状态，导致错误界面永远无法弹出
            checkWorkerFailureState()
            // 只有 Worker 正常时才刷新数据，避免在错误状态下重复发起无意义的网络请求
            if (errorStateView.visibility != View.VISIBLE) {
                DebugLogger.d(TAG, "onResume: error not visible, calling refreshData + startAutoRefreshTimer")
                refreshData()
                startAutoRefreshTimer()
            } else {
                DebugLogger.d(TAG, "onResume: error IS visible, skipping refreshData")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        DebugLogger.d(TAG, "onPause() called, stopping auto refresh")
        // 离开界面时停止自动刷新
        stopAutoRefreshTimer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 全局应用主题
        AppCompatDelegate.setDefaultNightMode(SPUtil.getNightMode(this))
        super.onCreate(savedInstanceState)

        if (SPUtil.isFirstRun(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        try {
            setContentView(R.layout.activity_main)
            BackgroundUtil.applyWindowBackground(this)
            ThemeUtil.applyToMainActivity(this)
            initViews()
            displayAppVersion()
            // 不要在这里调用 refreshData() — 让 onResume 统一处理，
            // onResume 会先检查 stopped 状态再决定是否刷新
            startWorker(SPUtil.getRefreshInterval(this))
            setupAnimations()
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "MainActivity onCreate crashed: ${e.message}", e)
            // 兜底：用最简布局避免白屏
            val errorView = TextView(this).apply {
                text = "加载界面失败\n\n${e.message}\n\n请清除应用数据后重试"
                textSize = 14f
                setPadding(40, 80, 40, 40)
                setTextColor(0xFF333333.toInt())
            }
            val scroll = ScrollView(this).apply { addView(errorView) }
            setContentView(scroll)
            Toast.makeText(this, "界面加载失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayAppVersion() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        tvSubtitle.text = "软件版本: v${version ?: "--"}"
    }

    private fun setupAnimations() {
        val cards = listOf(
            findViewById<View>(R.id.main_header),
            findViewById<View>(R.id.card_network),
            findViewById<View>(R.id.card_device)
        )
        
        cards.forEachIndexed { index, view ->
            view?.let {
                it.alpha = 0f
                it.translationY = 50f
                it.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(500)
                    .setStartDelay(index * 100L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun initViews() {
        tvModel = findViewById(R.id.main_tv_model)
        tvSubtitle = findViewById(R.id.main_tv_subtitle)
        tvNetSignal = findViewById(R.id.main_tv_net_signal)
        tvTemp = findViewById(R.id.main_tv_temp)
        tvCpu = findViewById(R.id.main_tv_cpu)
        tvMem = findViewById(R.id.main_tv_mem)
        tvDaily = findViewById(R.id.main_tv_daily)
        tvFlow = findViewById(R.id.main_tv_flow)
        tvFirmware = findViewById(R.id.main_tv_firmware)
        tvPower = findViewById(R.id.main_tv_power)
        tvStorage = findViewById(R.id.main_tv_storage)
        tvClientIp = findViewById(R.id.main_tv_client_ip)

        // 错误状态覆盖层
        mainContentView = findViewById(R.id.main_content)
        errorStateView = findViewById(R.id.error_state_view)
        errorTitle = findViewById(R.id.error_title)
        errorMessage = findViewById(R.id.error_message)
        errorTarget = findViewById(R.id.error_target)
        errorIcon = findViewById(R.id.error_icon)
        errorHint = findViewById(R.id.error_hint)

        // 重试按钮
        findViewById<View>(R.id.btn_error_retry).setOnClickListener {
            // 重置失败状态并立即刷新
            WifiWorker.resetFailureState(this@MainActivity)
            errorStateView.visibility = View.GONE
            mainContentView.visibility = View.VISIBLE
            refreshData()
            // 恢复前台自动刷新
            startAutoRefreshTimer()
            Toast.makeText(this@MainActivity, "正在重新连接...", Toast.LENGTH_SHORT).show()
        }

        // 修改配置按钮
        findViewById<View>(R.id.btn_error_config).setOnClickListener {
            startActivity(Intent(this@MainActivity, ConfigModifyActivity::class.java))
        }

        findViewById<View>(R.id.btn_settings).apply {
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
            setOnTouchListener(ScaleTouchListener())
        }

        findViewById<View>(R.id.btn_check_update).apply {
            setOnClickListener {
                Toast.makeText(this@MainActivity, "正在检查更新...", Toast.LENGTH_SHORT).show()
                UpdateChecker.checkUpdate(this@MainActivity) { info, error ->
                    when {
                        error != null -> {
                            Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                        }
                        info != null -> {
                            showUpdateDialog(info)
                        }
                        else -> {
                            Toast.makeText(this@MainActivity, "当前已是最新版本 ✓", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            setOnTouchListener(ScaleTouchListener())
        }
    }

    private inner class ScaleTouchListener : View.OnTouchListener {
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(150)
                        .setInterpolator(android.view.animation.OvershootInterpolator()).start()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150)
                        .setInterpolator(android.view.animation.OvershootInterpolator()).start()
                    v.performClick()
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }
            return true
        }
    }

    private fun refreshData() {
        DebugLogger.d(TAG, "refreshData() started")
        lifecycleScope.launch {
            val data = WifiCrawl.getWifiData(this@MainActivity)
            if (data != null) {
                DebugLogger.d(TAG, "refreshData: API success, model=${data.model}")
                // 成功 → 清除所有失败计数（无论之前是否 stopped）
                WifiWorker.resetFailureState(this@MainActivity)
                // 切回正常界面
                if (errorStateView.visibility == View.VISIBLE) {
                    errorStateView.visibility = View.GONE
                    mainContentView.visibility = View.VISIBLE
                }

                // 执行一个简单的淡出淡入效果
                val fadeOut = android.view.animation.AlphaAnimation(1.0f, 0.5f).apply { duration = 200 }
                val fadeIn = android.view.animation.AlphaAnimation(0.5f, 1.0f).apply { duration = 300 }
                
                listOf(tvNetSignal, tvTemp, tvCpu, tvMem, tvDaily, tvFlow).forEach { it.startAnimation(fadeOut) }

                val deviceName = data.deviceModel.ifEmpty { data.model }
                tvModel.text = deviceName

                tvNetSignal.text = "${data.netType.ifEmpty { "--" }} | ${data.signal.ifEmpty { "--" }}"
                tvTemp.text = data.temp.ifEmpty { "--" }
                tvCpu.text = data.cpu.ifEmpty { "--" }
                tvMem.text = data.mem.ifEmpty { "--" }
                tvDaily.text = data.dailyFlow.ifEmpty { "--" }
                tvFlow.text = data.flow.ifEmpty { "--" }
                
                tvFirmware.text = data.firmwareVer.ifEmpty { "--" }
                tvPower.text = "${data.batteryCurrent.ifEmpty { "--" }}  /  ${data.batteryVoltage.ifEmpty { "--" }}"
                tvStorage.text = data.internalStorage.ifEmpty { "--" }
                tvClientIp.text = data.clientIp.ifEmpty { "--" }

                listOf(tvNetSignal, tvTemp, tvCpu, tvMem, tvDaily, tvFlow).forEach { it.startAnimation(fadeIn) }

                SPUtil.saveData(this@MainActivity, data)
                BaseWifiWidget.renderAllWidgets(this@MainActivity)
            } else {
                // ====== 前台刷新失败 → 累加失败计数器，与 Worker 共用 ======
                val error = WifiCrawl.lastError
                val sp = this@MainActivity.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)

                // 判断是网络不通还是 API 错误
                val isNetworkError = error.contains("Network error", ignoreCase = true) ||
                        error.contains("timeout", ignoreCase = true) ||
                        error.contains("connect", ignoreCase = true) ||
                        error.contains("refused", ignoreCase = true)

                if (isNetworkError) {
                    val netFails = sp.getInt(WifiWorker.KEY_NETWORK_FAILURE_COUNT, 0) + 1
                    sp.edit().putInt(WifiWorker.KEY_NETWORK_FAILURE_COUNT, netFails).apply()
                    DebugLogger.w(TAG, "refreshData: network error, netFails=$netFails/${WifiWorker.NETWORK_MAX_FAILURES}")

                    if (netFails >= WifiWorker.NETWORK_MAX_FAILURES) {
                        sp.edit()
                            .putBoolean(WifiWorker.KEY_WORKER_STOPPED, true)
                            .putInt(WifiWorker.KEY_API_FAILURE_COUNT, 0)
                            .apply()
                        SPUtil.setWorkerStopReason(this@MainActivity, WifiWorker.REASON_NETWORK)
                        DebugLogger.e(TAG, "refreshData: network threshold reached, showing error view")
                        showErrorStateView()
                        return@launch
                    }
                } else {
                    val apiFails = sp.getInt(WifiWorker.KEY_API_FAILURE_COUNT, 0) + 1
                    sp.edit().putInt(WifiWorker.KEY_API_FAILURE_COUNT, apiFails).apply()
                    DebugLogger.w(TAG, "refreshData: API error, apiFails=$apiFails/${WifiWorker.API_MAX_FAILURES}, error=$error")

                    if (apiFails >= WifiWorker.API_MAX_FAILURES) {
                        sp.edit().putBoolean(WifiWorker.KEY_WORKER_STOPPED, true).apply()
                        SPUtil.setWorkerStopReason(this@MainActivity, WifiWorker.REASON_API)
                        DebugLogger.e(TAG, "refreshData: API threshold reached, showing error view")
                        showErrorStateView()
                        return@launch
                    }
                }

                // 还没达到阈值 → 只显示 Toast 提示
                if (error.contains("401") || error.contains("Unauthorized")) {
                    Toast.makeText(this@MainActivity, "访问受限，请在设置中检查管理口令", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "同步失败: ${error.ifEmpty { "网络超时" }}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 显示错误状态界面（从 checkWorkerFailureState 提取，供前台刷新失败时直接调用）
     */
    private fun showErrorStateView() {
        if (errorStateView.visibility == View.VISIBLE) return // 已显示

        val reason = SPUtil.getWorkerStopReason(this)
        val ip = SPUtil.getDeviceIp(this)
        val port = SPUtil.getDevicePort(this)

        DebugLogger.w(TAG, "showErrorStateView: reason=$reason, target=$ip:$port")

        errorTarget.text = "$ip:$port"

        when (reason) {
            WifiWorker.REASON_NETWORK -> {
                errorTitle.text = "无法与设备通信"
                errorMessage.text = "无法连接到设备 ($ip:$port)，后台刷新已自动暂停以节省电量。\n\n" +
                        "可能原因：\n" +
                        "• 设备未开机或不在同一内网\n" +
                        "• 设备 IP 地址配置有误\n" +
                        "• 设备网络异常"
                errorHint.text = "设备恢复上线后将自动恢复刷新"
                errorIcon.setImageResource(R.drawable.ic_router_off)
            }
            WifiWorker.REASON_API -> {
                errorTitle.text = "连接配置异常"
                errorMessage.text = "设备网络可达，但 API 请求连续失败，后台刷新已自动暂停以节省电量。\n\n" +
                        "可能原因：\n" +
                        "• 设备端口配置错误\n" +
                        "• Token / 管理口令不正确或已更改\n" +
                        "• 设备 API 服务异常"
                errorHint.text = "请修改配置后点击「重新连接」重试"
                errorIcon.setImageResource(R.drawable.ic_router_off)
            }
            else -> {
                errorTitle.text = "连接失败"
                errorMessage.text = "连续多次无法连接到设备，后台刷新已自动暂停以节省电量。\n\n" +
                        "请检查设备状态和连接配置后重试。"
                errorHint.text = "设备恢复上线后将自动恢复刷新"
                errorIcon.setImageResource(R.drawable.ic_router_off)
            }
        }

        mainContentView.visibility = View.GONE
        errorStateView.visibility = View.VISIBLE
        stopAutoRefreshTimer()
    }

    private fun startWorker(minutes: Int) {
        DebugLogger.d(TAG, "startWorker: minutes=$minutes")
        val workManager = WorkManager.getInstance(this)
        if (minutes <= 0) {
            DebugLogger.d(TAG, "startWorker: minutes<=0, cancelling worker")
            workManager.cancelUniqueWork("wifi_crawl")
            return
        }
        // 只在 Worker 当前未 stopped 时才重置 — 如果已 stopped，保持 stopped 状态让用户看到错误界面
        // Worker 内部自己会处理恢复逻辑（ping 通过后自动解除 stopped）
        val wasStopped = WifiWorker.isWorkerStopped(this)
        DebugLogger.d(TAG, "startWorker: wasStopped=$wasStopped")
        if (!wasStopped) {
            WifiWorker.resetFailureState(this)
        } else {
            DebugLogger.d(TAG, "startWorker: worker is stopped, NOT resetting failure state")
        }
        val workRequest = PeriodicWorkRequestBuilder<WifiWorker>(minutes.toLong(), TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork(
            "wifi_crawl",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    /**
     * 检查 Worker 是否因失败被停止，根据失败原因切换为错误状态覆盖层。
     * 失败时主界面信息毫无用处 → 隐藏数据卡片，显示错误信息和操作按钮。
     *
     * 如果 Worker 已恢复，切回正常数据界面。
     */
    private fun checkWorkerFailureState() {
        val stopped = WifiWorker.isWorkerStopped(this)
        val reason = SPUtil.getWorkerStopReason(this)
        val apiFails = this.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
            .getInt("worker_api_failure_count", 0)
        val netFails = this.getSharedPreferences("wifi_data", Context.MODE_PRIVATE)
            .getInt("worker_network_failure_count", 0)
        DebugLogger.d(TAG, "checkWorkerFailureState: stopped=$stopped, reason=$reason, apiFails=$apiFails, netFails=$netFails")

        if (!stopped) {
            // Worker 正常 → 显示主内容
            if (errorStateView.visibility == View.VISIBLE) {
                DebugLogger.i(TAG, "checkWorkerFailureState: stopped=false, switching back to main content")
                errorStateView.visibility = View.GONE
                mainContentView.visibility = View.VISIBLE
            }
            return
        }

        // Worker 已停止 → 如果错误界面已显示则无需重复切换
        if (errorStateView.visibility == View.VISIBLE) {
            DebugLogger.d(TAG, "checkWorkerFailureState: error already visible, skip")
            return
        }

        val ip = SPUtil.getDeviceIp(this)
        val port = SPUtil.getDevicePort(this)

        DebugLogger.w(TAG, "checkWorkerFailureState: showing error view, reason=$reason, target=$ip:$port")

        // 更新连接目标
        errorTarget.text = "$ip:$port"

        when (reason) {
            WifiWorker.REASON_NETWORK -> {
                errorTitle.text = "无法与设备通信"
                errorMessage.text = "无法连接到设备 ($ip:$port)，后台刷新已自动暂停以节省电量。\n\n" +
                        "可能原因：\n" +
                        "• 设备未开机或不在同一内网\n" +
                        "• 设备 IP 地址配置有误\n" +
                        "• 设备网络异常"
                errorHint.text = "设备恢复上线后将自动恢复刷新"
                errorIcon.setImageResource(R.drawable.ic_router_off)
            }
            WifiWorker.REASON_API -> {
                errorTitle.text = "连接配置异常"
                errorMessage.text = "设备网络可达，但 API 请求连续失败，后台刷新已自动暂停以节省电量。\n\n" +
                        "可能原因：\n" +
                        "• 设备端口配置错误\n" +
                        "• Token / 管理口令不正确或已更改\n" +
                        "• 设备 API 服务异常"
                errorHint.text = "请修改配置后点击「重新连接」重试"
                errorIcon.setImageResource(R.drawable.ic_router_off)
            }
            else -> {
                // 未知原因（兼容旧状态）
                errorTitle.text = "连接失败"
                errorMessage.text = "连续多次无法连接到设备，后台刷新已自动暂停以节省电量。\n\n" +
                        "请检查设备状态和连接配置后重试。"
                errorHint.text = "设备恢复上线后将自动恢复刷新"
                errorIcon.setImageResource(R.drawable.ic_router_off)
            }
        }

        // 切换为错误状态界面
        mainContentView.visibility = View.GONE
        errorStateView.visibility = View.VISIBLE
        // 主题色已在 ThemeUtil.applyToMainActivity() 中统一应用
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

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("更新可用")
            .setMessage(message)
            .setPositiveButton("下载更新") { _, _ ->
                downloadAndInstall(info.apkUrl, info.tagName)
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun downloadAndInstall(url: String, tag: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "没有可下载的 APK", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001
                )
                return
            }
        }

        startDownload(url, tag)
    }

    private fun startDownload(url: String, tag: String) {
        val fileName = "UFITOOLS-Widget-$tag.apk"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("UFITOOLS-Widget")
            .setDescription("正在下载 $tag 版本...")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(request)
        Toast.makeText(this, "开始下载...", Toast.LENGTH_SHORT).show()

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(fileName)
                    unregisterReceiver(this)
                    downloadReceiver = null
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(fileName: String) {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )
        if (!file.exists()) {
            Toast.makeText(this, "下载文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } else {
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "请重新点击下载", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        downloadReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }
}
