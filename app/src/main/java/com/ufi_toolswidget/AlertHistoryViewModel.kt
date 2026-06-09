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

data class AlertFilter(
    val type: String = "all",
    val readStatus: String = "all"
)

class AlertHistoryViewModel(application: Application) : AndroidViewModel(application) {

    val filter = MutableStateFlow(AlertFilter())

    /** 每页条数（可从设置页更改） */
    val pageSize = MutableStateFlow(AlertHistoryManager.getPageSize(application))

    val unreadCount: Flow<Int> = AlertHistoryManager.observeUnreadCount()
    val totalCount: Flow<Int> = AlertHistoryManager.observeTotalCount()
    val subtitleInfo: Flow<Pair<Int, Int>> = totalCount.combine(unreadCount) { t, u -> t to u }

    @OptIn(ExperimentalCoroutinesApi::class)
    val alerts: Flow<PagingData<AlertRecord>> =
        filter.combine(pageSize) { f, ps -> f to ps }
            .flatMapLatest { (f, ps) ->
                Pager(
                    config = PagingConfig(
                        pageSize = ps,
                        enablePlaceholders = false,
                        prefetchDistance = ps / 2
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
