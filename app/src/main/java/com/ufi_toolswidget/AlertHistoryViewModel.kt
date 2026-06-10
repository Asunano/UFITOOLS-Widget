package com.ufi_toolswidget

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ufi_toolswidget.db.AlertRecord
import com.ufi_toolswidget.util.AlertHistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class AlertFilter(
    val type: String = "all",
    val readStatus: String = "all"
)

data class PageResult(
    val data: List<AlertRecord>,
    val currentPage: Int,
    val totalPages: Int,
    val totalRecords: Int
)

@OptIn(ExperimentalCoroutinesApi::class)
class AlertHistoryViewModel(application: Application) : AndroidViewModel(application) {

    val filter = MutableStateFlow(AlertFilter())
    val currentPage = MutableStateFlow(1)
    val pageSize = MutableStateFlow(AlertHistoryManager.getPageSize(application))

    /** 刷新触发器 — 每次递增触发重新加载当前页 */
    private val refreshTrigger = MutableStateFlow(0)

    val unreadCount: StateFlow<Int> = AlertHistoryManager.observeUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalCount: StateFlow<Int> = AlertHistoryManager.observeTotalCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val subtitleInfo = totalCount.combine(unreadCount) { t, u -> t to u }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0 to 0)

    /** 当前页数据 + 分页信息 */
    val pageData: StateFlow<PageResult> =
        combine(currentPage, filter, pageSize, refreshTrigger) { page, f, ps, _ ->
            arrayOf(page as Any, f as Any, ps as Any)
        }.mapLatest { arr ->
            @Suppress("UNCHECKED_CAST")
            val page = arr[0] as Int
            val f = arr[1] as AlertFilter
            val ps = arr[2] as Int
            // 数据库查询移到 IO 线程，避免阻塞主线程
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                loadPage(page, f, ps)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            PageResult(emptyList(), 1, 1, 0))

    fun refresh() { refreshTrigger.value++ }

    fun goToPage(p: Int) { currentPage.value = p }
    fun nextPage() {
        val max = pageData.value.totalPages
        if (currentPage.value < max) currentPage.value = currentPage.value + 1
    }
    fun prevPage() {
        if (currentPage.value > 1) currentPage.value = currentPage.value - 1
    }
    fun firstPage() { currentPage.value = 1 }
    fun lastPage() { currentPage.value = pageData.value.totalPages }

    // ── 加载指定页（在 IO 线程执行）──

    private suspend fun loadPage(page: Int, f: AlertFilter, ps: Int): PageResult {
        val total = when {
            f.type == "all" && f.readStatus == "all" ->
                AlertHistoryManager.getTotalCount()
            f.type != "all" && f.readStatus == "all" ->
                AlertHistoryManager.getCountByType(f.type)
            f.type == "all" && f.readStatus != "all" ->
                AlertHistoryManager.getCountByReadStatus(f.readStatus == "read")
            else ->
                AlertHistoryManager.getCountFiltered(f.type, f.readStatus == "read")
        }
        val totalPages = maxOf(1, (total + ps - 1) / ps)
        val safeP = page.coerceIn(1, totalPages)
        if (safeP != page) currentPage.value = safeP
        val offset = (safeP - 1) * ps
        val data = when {
            f.type == "all" && f.readStatus == "all" ->
                AlertHistoryManager.getPage(ps, offset)
            f.type != "all" && f.readStatus == "all" ->
                AlertHistoryManager.getPageByType(f.type, ps, offset)
            f.type == "all" && f.readStatus != "all" ->
                AlertHistoryManager.getPageByReadStatus(f.readStatus == "read", ps, offset)
            else ->
                AlertHistoryManager.getPageFiltered(f.type, f.readStatus == "read", ps, offset)
        }
        return PageResult(data, safeP, totalPages, total)
    }
}
