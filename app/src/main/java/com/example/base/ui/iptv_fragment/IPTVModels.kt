package com.example.base.ui.iptv_fragment

import com.example.base.model.entity.Channel

sealed interface CategoryRefreshSaveState {
    data object Idle : CategoryRefreshSaveState
    data class Loading(val progress: Int) : CategoryRefreshSaveState
    data class Success(
        val savedChannelCount: Int,
        val categoryCount: Int
    ) : CategoryRefreshSaveState
    data class Error(val message: String) : CategoryRefreshSaveState
}

enum class IPTVTab {
    GENRES,
    FAVORITES
}

enum class IPTVFilterMode {
    COUNTRY,
    LANGUAGE
}

data class IPTVFilterOption(
    val value: String?,
    val label: String,
    val channelCount: Int
)

data class IPTVCategoryItem(
    val name: String,
    val channelCount: Int
)

data class IPTVUiState(
    val tab: IPTVTab = IPTVTab.GENRES,
    val filterMode: IPTVFilterMode = IPTVFilterMode.COUNTRY,
    val selectedFilter: String? = null,
    val channelSearchQuery: String = "",
    val filterOptions: List<IPTVFilterOption> = emptyList(),
    val categories: List<IPTVCategoryItem> = emptyList(),
    val selectedCategory: IPTVCategoryItem? = null,
    val channels: List<Channel> = emptyList(),
    val selectedChannel: Channel? = null
)
