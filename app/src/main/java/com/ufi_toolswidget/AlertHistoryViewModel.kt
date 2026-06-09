package com.ufi_toolswidget

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ufi_toolswidget.db.AlertRecord
import com.ufi_toolswidget.util.AlertHistoryManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

/**
 * 筛选状态。
 */
data class AlertFilter(
    val type: String = "all",        // "all" / "daily_flow" / "monthly_flow" / "temp" / "cpu" / "memory" / "battery" / "device_online"
    val readStatus: String = "all"   // "all" / "unread" / "read"
)

/**
 * 警报历史 ViewModel：管理筛选状态 + Paging3 数据流。
 */
class AlertHistoryViewModel(application: Application) : AndroidViewModel(application) {

    val filter = MutableStateFlow(AlertFilter())

    /** 未读数量（Flow，实时响应 Room 变更） */
    val unreadCount: Flow<Int> = AlertHistoryManager.observeUnreadCount()

    /** 总数量（Flow，实时响应 Room 变更） */
    val totalCount: Flow<Int> = AlertHistoryManager.observeTotalCount()

    /** 副标题信息（total + unread 合并，任一变化即发射） */
    val subtitleInfo: Flow<Pair<Int, Int>> = totalCount.combine(unreadCount) { total, unread ->
        total to unread
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val alerts: Flow<PagingData<AlertRecord>> = filter
        .flatMapLatest { f ->
            Pager(
                config = PagingConfig(
                    pageSize = 20,
                    enablePlaceholders = false,
                    prefetchDistance = 10
                ),
                pagingSourceFactory = {
                    when {
                        f.type == "all" && f.readStatus == "all" ->
                            AlertHistoryManager.getAllPaged()
                        f.type != "all" && f.readStatus == "all" ->
                            AlertHistoryManager.getPagedByType(f.type)
                        f.type == "all" && f.readStatus != "all" ->
                            AlertHistoryManager.getPagedByReadStatus(f.readStatus == "read")
                        else ->
                            AlertHistoryManager.getPagedFiltered(f.type, f.readStatus == "read")
                    }
                }
            ).flow
        }
        .cachedIn(viewModelScope)
}
