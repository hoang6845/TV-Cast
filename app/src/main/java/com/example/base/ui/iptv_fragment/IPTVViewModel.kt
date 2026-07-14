package com.example.base.ui.iptv_fragment

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.base.R
import com.example.base.model.dao.ChannelDao
import com.example.base.model.entity.Channel
import com.example.base.utils.AppConstants
import com.example.base.utils.HttpClientProvider
import com.example.base.utils.fetchM3U
import com.example.base.utils.parseM3U
import dagger.hilt.android.lifecycle.HiltViewModel
import hoang.dqm.codebase.base.viewmodel.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class IPTVViewModel @Inject constructor(
    private val channelDao: ChannelDao
): BaseViewModel() {

    private val okHttpClient by lazy {
        HttpClientProvider.provide(context)
    }

    private val _uiState = MutableStateFlow(IPTVUiState())
    val uiState: StateFlow<IPTVUiState> = _uiState

    private val _refreshSaveState =
        MutableStateFlow<CategoryRefreshSaveState>(CategoryRefreshSaveState.Idle)
    val refreshSaveState: StateFlow<CategoryRefreshSaveState> = _refreshSaveState

    private var allChannels: List<Channel> = emptyList()
    private var isRefreshing = false

    init {
        observeChannels()
    }

    private fun observeChannels() {
        viewModelScope.launch(Dispatchers.IO) {
            channelDao.getAllChannels().collectLatest { channels ->
                allChannels = channels
                rebuildUiState()
            }
        }
    }

    fun selectTab(tab: IPTVTab) {
        _uiState.update {
            it.copy(
                tab = tab,
                selectedFilter = null,
                selectedCategory = null,
                channels = emptyList(),
                selectedChannel = null
            )
        }
        rebuildUiState()
    }

    fun selectFilterMode(mode: IPTVFilterMode) {
        _uiState.update {
            it.copy(
                filterMode = mode,
                selectedFilter = null,
                selectedCategory = null,
                channels = emptyList(),
                selectedChannel = null
            )
        }
        rebuildUiState()
    }

    fun selectFilterOption(option: IPTVFilterOption) {
        _uiState.update {
            it.copy(
                selectedFilter = option.value,
                selectedCategory = null,
                channels = emptyList(),
                selectedChannel = null
            )
        }
        rebuildUiState()
    }

    fun updateChannelSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                channelSearchQuery = query,
                selectedChannel = null
            )
        }
        rebuildUiState()
    }

    fun openCategory(category: IPTVCategoryItem) {
        if (_uiState.value.tab != IPTVTab.GENRES) return

        _uiState.update {
            it.copy(
                selectedCategory = category,
                channels = buildChannelsForCategory(
                    filteredChannels(
                        tab = it.tab,
                        mode = it.filterMode,
                        selectedFilter = it.selectedFilter
                    ),
                    category.name
                ),
                selectedChannel = null
            )
        }
    }

    fun closeCategory() {
        _uiState.update {
            it.copy(
                selectedCategory = null,
                channels = emptyList(),
                selectedChannel = null
            )
        }
    }

    fun selectChannel(channel: Channel) {
        _uiState.update { it.copy(selectedChannel = channel) }
    }

    fun toggleFavourite(channel: Channel) {
        viewModelScope.launch(Dispatchers.IO) {
            channelDao.updateFavourite(channel.id, channel.isFavourite.not())
        }
    }

    fun refreshPlaylist() {
        if (isRefreshing) return

        viewModelScope.launch(Dispatchers.IO) {
            isRefreshing = true
            _refreshSaveState.value = CategoryRefreshSaveState.Loading(progress = 0)

            try {
                _refreshSaveState.value = CategoryRefreshSaveState.Loading(progress = 10)
                val existingChannels = channelDao.getAllChannelsOnce().associateBy { it.id }

                val parsedChannels = mutableListOf<Channel>()
                val sources = AppConstants.CHANNEL_SOURCES
                sources.forEachIndexed { index, (source, url) ->
                    val baseProgress = 15 + ((index * 55) / sources.size.coerceAtLeast(1))
                    _refreshSaveState.value =
                        CategoryRefreshSaveState.Loading(progress = baseProgress)

                    val m3uText = fetchM3U(okHttpClient, url)
                    _refreshSaveState.value =
                        CategoryRefreshSaveState.Loading(progress = (baseProgress + 10).coerceAtMost(75))

                    parsedChannels += parseM3U(m3uText, source)
                }

                _refreshSaveState.value = CategoryRefreshSaveState.Loading(progress = 80)
                val mergedChannels = mergeChannels(parsedChannels, existingChannels)

                _refreshSaveState.value = CategoryRefreshSaveState.Loading(progress = 90)
                channelDao.insertChannels(mergedChannels)

                val categoryCount = buildCategoryItems(mergedChannels).size
                _refreshSaveState.value = CategoryRefreshSaveState.Success(
                    savedChannelCount = mergedChannels.size,
                    categoryCount = categoryCount
                )
                Log.d(
                    "IPTVRefresh",
                    "refreshPlaylist: parsed=${parsedChannels.size}, saved=${mergedChannels.size}"
                )
            } catch (e: Exception) {
                _refreshSaveState.value = CategoryRefreshSaveState.Error(
                    message = e.message ?: getString(R.string.text_something_went_wrong)
                )
            } finally {
                isRefreshing = false
            }
        }
    }

    fun acknowledgeRefreshState() {
        if (_refreshSaveState.value !is CategoryRefreshSaveState.Loading) {
            _refreshSaveState.value = CategoryRefreshSaveState.Idle
        }
    }

    private fun rebuildUiState() {
        val current = _uiState.value
        val filterOptions = buildFilterOptions(current.tab, current.filterMode)
        val selectedFilter = current.selectedFilter
            ?.takeIf { value ->
                filterOptions.any { option ->
                    option.value.equals(value, ignoreCase = true)
                }
            }

        val filtered = filteredChannels(current.tab, current.filterMode, selectedFilter)
        val categories = if (current.tab == IPTVTab.GENRES) {
            buildCategoryItems(filtered)
        } else {
            emptyList()
        }
        val selectedCategory = if (current.tab == IPTVTab.GENRES) {
            current.selectedCategory
                ?.let { selected ->
                    categories.firstOrNull { it.name.equals(selected.name, ignoreCase = true) }
                }
        } else {
            null
        }
        val channels = when (current.tab) {
            IPTVTab.GENRES -> selectedCategory
                ?.let { buildChannelsForCategory(filtered, it.name) }
                .orEmpty()

            IPTVTab.FAVORITES -> buildDirectChannels(filtered)
                .filterByName(current.channelSearchQuery)
        }
        val selectedChannel = current.selectedChannel
            ?.let { channel -> channels.firstOrNull { it.id == channel.id } }

        _uiState.value = current.copy(
            selectedFilter = selectedFilter,
            filterOptions = filterOptions,
            categories = categories,
            selectedCategory = selectedCategory,
            channels = channels,
            selectedChannel = selectedChannel
        )
    }

    private fun filteredChannels(
        tab: IPTVTab,
        mode: IPTVFilterMode,
        selectedFilter: String?
    ): List<Channel> {
        return channelsForTab(tab).filter { channel ->
            selectedFilter == null || when (mode) {
                IPTVFilterMode.COUNTRY -> channel.hasGroup(channel.countries, selectedFilter)
                IPTVFilterMode.LANGUAGE -> channel.hasGroup(channel.languages, selectedFilter)
            }
        }
    }

    private fun channelsForTab(tab: IPTVTab): List<Channel> {
        return when (tab) {
            IPTVTab.GENRES -> allChannels
            IPTVTab.FAVORITES -> allChannels.filter { it.isFavourite }
        }
    }

    private fun buildFilterOptions(
        tab: IPTVTab,
        mode: IPTVFilterMode
    ): List<IPTVFilterOption> {
        val baseChannels = channelsForTab(tab)
        val counters = linkedMapOf<String, GroupCounter>()

        baseChannels.forEach { channel ->
            val groups = when (mode) {
                IPTVFilterMode.COUNTRY -> splitGroups(channel.countries)
                IPTVFilterMode.LANGUAGE -> splitGroups(channel.languages)
            }
            groups.forEach { group ->
                val key = group.lowercase()
                counters.getOrPut(key) { GroupCounter(group) }.channelIds.add(channel.id)
            }
        }

        val allLabel = when (mode) {
            IPTVFilterMode.COUNTRY -> getString(R.string.text_all_countries)
            IPTVFilterMode.LANGUAGE -> getString(R.string.text_all_language)
        }

        val options = counters.values
            .map { counter ->
                IPTVFilterOption(
                    value = counter.label,
                    label = counter.label,
                    channelCount = counter.channelIds.size
                )
            }
            .sortedWith(
                compareBy<IPTVFilterOption> { it.label.lowercase() }
                    .thenByDescending { it.channelCount }
            )

        return listOf(
            IPTVFilterOption(
                value = null,
                label = allLabel,
                channelCount = baseChannels.size
            )
        ) + options
    }

    private fun buildCategoryItems(channels: List<Channel>): List<IPTVCategoryItem> {
        val counters = linkedMapOf<String, GroupCounter>()

        channels.forEach { channel ->
            splitGroups(channel.categories).forEach { category ->
                val key = category.lowercase()
                counters.getOrPut(key) { GroupCounter(category) }.channelIds.add(channel.id)
            }
        }

        return counters.values
            .map { counter ->
                IPTVCategoryItem(
                    name = counter.label,
                    channelCount = counter.channelIds.size
                )
            }
            .sortedWith(
                compareByDescending<IPTVCategoryItem> { it.channelCount }
                    .thenBy { it.name.lowercase() }
            )
    }

    private fun buildChannelsForCategory(
        channels: List<Channel>,
        categoryName: String
    ): List<Channel> {
        return channels
            .filter { channel -> channel.hasGroup(channel.categories, categoryName) }
            .distinctBy { it.id }
            .sortedWith(compareBy { it.name.lowercase() })
    }

    private fun buildDirectChannels(channels: List<Channel>): List<Channel> {
        return channels
            .distinctBy { it.id }
            .sortedWith(compareBy { it.name.lowercase() })
    }

    private fun List<Channel>.filterByName(query: String): List<Channel> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return this
        return filter { channel -> channel.name.contains(normalizedQuery, ignoreCase = true) }
    }

    private fun Channel.hasGroup(value: String?, groupName: String): Boolean {
        return splitGroups(value).any { it.equals(groupName, ignoreCase = true) }
    }

    private fun splitGroups(value: String?): List<String> {
        return value.orEmpty()
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun mergeChannels(
        channels: List<Channel>,
        existingChannels: Map<String, Channel>
    ): List<Channel> {
        val merged = linkedMapOf<String, Channel>()

        channels.forEach { channel ->
            val current = merged[channel.id]
            val existing = existingChannels[channel.id]

            merged[channel.id] = if (current == null) {
                channel.copy(isFavourite = existing?.isFavourite ?: channel.isFavourite)
            } else {
                current.copy(
                    name = current.name.ifBlank { channel.name },
                    logo = current.logo.takeUnless { it.isNullOrBlank() } ?: channel.logo,
                    categories = mergeGroupValues(current.categories, channel.categories),
                    languages = mergeGroupValues(current.languages, channel.languages),
                    countries = mergeGroupValues(current.countries, channel.countries),
                    url = current.url.ifBlank { channel.url },
                    isFavourite = current.isFavourite ||
                        channel.isFavourite ||
                        (existing?.isFavourite == true)
                )
            }
        }

        return merged.values.toList()
    }

    private fun mergeGroupValues(first: String?, second: String?): String? {
        val values = (first.orEmpty().split(";") + second.orEmpty().split(";"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

        return values.joinToString(";").takeIf { it.isNotEmpty() }
    }

    private data class GroupCounter(
        val label: String,
        val channelIds: MutableSet<String> = linkedSetOf()
    )
}
