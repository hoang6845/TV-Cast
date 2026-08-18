package com.tvchromecast.screenmirroringplus.ui.main

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.tvchromecast.screenmirroringplus.model.dao.ChannelDao
import com.tvchromecast.screenmirroringplus.model.entity.Channel
import com.tvchromecast.screenmirroringplus.utils.AppConstants
import com.tvchromecast.screenmirroringplus.utils.HttpClientProvider
import com.tvchromecast.screenmirroringplus.utils.fetchM3U
import com.tvchromecast.screenmirroringplus.utils.parseM3U
import dagger.hilt.android.lifecycle.HiltViewModel
import hoang.dqm.codebase.ui.vm.BaseMainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val channelDao: ChannelDao
): BaseMainViewModel() {

    private val okHttpClient by lazy {
        HttpClientProvider.provide(context)
    }
    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        viewModelScope.launch(Dispatchers.IO) {
            Log.d("check data", "loadM3U: parsed=${channelDao.countChannels()}, saved=${channelDao.countChannelsWithLanguageOrCountry()}")

            if (channelDao.countChannels() == 0 || channelDao.countChannelsWithLanguageOrCountry() == 0) {
                loadM3UInternal()
            }
        }
    }
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    fun loadM3U() {
        viewModelScope.launch(Dispatchers.IO) {
            loadM3UInternal()
        }
    }

    private suspend fun loadM3UInternal() {
        _loading.value = true
        _error.value = null

        try {
            Log.d("check data", "loadM3U: start")

            val existingChannels = channelDao.getAllChannelsOnce().associateBy { it.id }
            val parsedChannels = AppConstants.CHANNEL_SOURCES.flatMap { (source, url) ->
                val m3uText = fetchM3U(okHttpClient, url)
                parseM3U(m3uText, source)
            }
            Log.d("check data", "loadM3U: start $parsedChannels")

            val mergedChannels = mergeChannels(parsedChannels, existingChannels)
            channelDao.insertChannels(mergedChannels)
            Log.d("check data", "loadM3U: start end load parsed=${parsedChannels.size}, saved=${mergedChannels.size}")
        } catch (e: Exception) {
            _error.value = e.message
            Log.d("check data", "loadM3U: parsed=${e.message}")

        } finally {
            _loading.value = false
            Log.d("check data", "loadM3U: end")

        }
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
                    isFavourite = current.isFavourite || channel.isFavourite || (existing?.isFavourite == true)
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
}
