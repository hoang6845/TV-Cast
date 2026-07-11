package com.example.base.ui.home

import androidx.recyclerview.widget.GridLayoutManager
import com.example.base.R
import com.example.base.databinding.FragmentHomeBinding
import com.example.base.model.entity.ItemFunc
import com.example.base.utils.AppConstants
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.navigate


class HomeFragment : BaseFragment<FragmentHomeBinding, HomeViewModel>() {
    private val listItem: List<ItemFunc> by lazy {
        listOf(
            ItemFunc(
                type = AppConstants.TYPE_MIRROR,
                iconRes = R.drawable.ic_mirror,
                title = getString(R.string.text_mirror_screen),
                description = getString(R.string.text_display_the_content_from_phone_to_tv)
            ),
            ItemFunc(
                type = AppConstants.TYPE_CAST_MEDIA,
                iconRes = R.drawable.ic_media,
                title = getString(R.string.text_cast_media),
                description = getString(R.string.text_view_favorite_photos_and_videos_on_tv)
            ),
            ItemFunc(
                type = AppConstants.TYPE_CAMERA_CAST,
                iconRes = R.drawable.ic_tv_cast,
                title = getString(R.string.text_camera_cast),
                description = getString(R.string.text_mirror_your_camera_to_your_tv)
            ),
            ItemFunc(
                type = AppConstants.TYPE_TRY_TV_REMOTE,
                iconRes = R.drawable.ic_tv_remote,
                title = getString(R.string.text_try_tv_remote),
                description = getString(R.string.text_use_your_phone_as_a_tv_remote)
            ),
            ItemFunc(
                type = AppConstants.TYPE_CAST_YOUTUBE,
                iconRes = R.drawable.ic_cast_youtube,
                title = getString(R.string.text_cast_youtube),
                description = getString(R.string.text_watch_youtube_videos_on_your_tv)
            ),
            ItemFunc(
                type = AppConstants.TYPE_CAST_WEB,
                iconRes = R.drawable.ic_cast_web,
                title = getString(R.string.text_cast_web),
                description = getString(R.string.text_browse_and_mirror_videos_from_websites)
            ),
            ItemFunc(
                type = AppConstants.TYPE_MOVIE_ADVISOR,
                iconRes = R.drawable.ic_movie_advisor,
                title = getString(R.string.text_movie_advisor),
                description = getString(R.string.text_find_movies_or_series_to_watch)
            ),
            ItemFunc(
                type = AppConstants.TYPE_IPTV,
                iconRes = R.drawable.ic_iptv,
                title = getString(R.string.text_watch_iptv),
                description = getString(R.string.text_1000_channels_in_high_quality)
            )
        )
    }

    private val adapter: HomeFuncAdapter by lazy {
        HomeFuncAdapter()
    }
    override fun initView() {
        setUpAdapter()
    }

    override fun initListener() {
        binding.icSetting.setOnClickListener {

        }
    }

    override fun initData() {
    }

    fun setUpAdapter(){
        adapter.onClickItem { item, position ->
            when (item.type){
                AppConstants.TYPE_MIRROR -> {}
                AppConstants.TYPE_CAST_MEDIA -> {}
                AppConstants.TYPE_CAMERA_CAST -> {}
                AppConstants.TYPE_TRY_TV_REMOTE -> {}
                AppConstants.TYPE_CAST_YOUTUBE -> navigate(R.id.castYoutubeFragment)
                AppConstants.TYPE_CAST_WEB -> {}
                AppConstants.TYPE_MOVIE_ADVISOR -> {}
                AppConstants.TYPE_IPTV -> {}

            }
        }

        adapter.setList(listItem)
        binding.rvHomeFunc.adapter = adapter
        binding.rvHomeFunc.layoutManager = GridLayoutManager(
            requireContext(),
            2,
            GridLayoutManager.VERTICAL,
            false
        )

    }
}
