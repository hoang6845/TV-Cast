package com.example.base.ui.home

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.example.base.R
import com.example.base.databinding.FragmentHomeBinding
import com.example.base.databinding.LayoutHomeHowToConnectSheetBinding
import com.example.base.model.entity.ItemFunc
import com.example.base.ui.cast_media.CastMediaFragment
import com.example.base.ui.intro.ViewPager2Adapter
import com.example.base.utils.AppConstants
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        adjustInsetsForBottomNavigation(binding.icSetting)
        setUpAdapter()
    }

    override fun initListener() {
        binding.btnHowToConnect.setOnClickListener {
            showHowToConnectSheet()
        }

        binding.icHelp.setOnClickListener {
            navigate(R.id.faqFragment)
        }

        binding.icSetting.setOnClickListener {

        }
    }

    override fun initData() {
    }

    fun setUpAdapter() {
        adapter.onClickItem { item, position ->
            when (item.type) {
                AppConstants.TYPE_MIRROR -> navigate(R.id.screenMirroringFragment)
                AppConstants.TYPE_CAST_MEDIA -> showCastMediaChooser()
                AppConstants.TYPE_CAMERA_CAST -> navigate(R.id.cameraCastFragment)
                AppConstants.TYPE_TRY_TV_REMOTE -> navigate(R.id.tvRemoteFragment)
                AppConstants.TYPE_CAST_YOUTUBE -> navigate(R.id.castYoutubeFragment)
                AppConstants.TYPE_CAST_WEB -> navigate(R.id.castWebFragment)
                AppConstants.TYPE_MOVIE_ADVISOR -> navigate(R.id.movieAdvisorFragment)
                AppConstants.TYPE_IPTV -> navigate(R.id.iptvFragment)

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

    private fun showCastMediaChooser() {
        MaterialAlertDialogBuilder(
            requireContext(),
            R.style.ThemeOverlay_App_CastDialog
        )
            .setTitle(R.string.text_cast_media)
            .setItems(
                arrayOf(
                    getString(R.string.text_cast_photos),
                    getString(R.string.text_cast_video)
                )
            ) { _, which ->
                val mode = if (which == 0) {
                    CastMediaFragment.MODE_PHOTO
                } else {
                    CastMediaFragment.MODE_VIDEO
                }
                navigate(
                    R.id.castMediaFragment,
                    Bundle().apply {
                        putString(CastMediaFragment.ARG_MODE, mode)
                    }
                )
            }
            .show()
    }

    private fun showHowToConnectSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = LayoutHomeHowToConnectSheetBinding.inflate(layoutInflater)
        val descriptions = listOf(
            getString(R.string.text_home_connect_guide_desc),
            getString(R.string.text_home_connect_same_wifi_desc)
        )
        val autoSlideHandler = Handler(Looper.getMainLooper())
        var slideForward = true

        fun updateDots(position: Int) {
            sheetBinding.layoutDots.removeAllViews()
            repeat(descriptions.size) { index ->
                sheetBinding.layoutDots.addView(createDot(index == position))
            }
        }

        val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                sheetBinding.tvDescription.text = descriptions[position]
                updateDots(position)
            }
        }

        val autoSlideRunnable = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return

                val currentItem = sheetBinding.viewPager.currentItem
                val nextItem = if (currentItem == 0 && slideForward) {
                    1
                } else {
                    slideForward = !slideForward
                    0
                }

                sheetBinding.viewPager.setCurrentItem(nextItem, true)
                autoSlideHandler.postDelayed(this, AUTO_SLIDE_INTERVAL_MS)
            }
        }

        sheetBinding.viewPager.adapter = ViewPager2Adapter(
            items = listOf(
                R.layout.item_home_connect_steps,
                R.layout.item_home_connect_browser
            ),
            getLayoutResId = { item, _ -> item },
            bindView = { _, _, _ -> }
        )
        sheetBinding.viewPager.offscreenPageLimit = 2
        sheetBinding.viewPager.registerOnPageChangeCallback(pageChangeCallback)
        updateDots(0)

        dialog.setContentView(sheetBinding.root)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener

            bottomSheet.setBackgroundColor(Color.TRANSPARENT)
            bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                height = (resources.displayMetrics.heightPixels * BOTTOM_SHEET_HEIGHT_RATIO).toInt()
            }

            BottomSheetBehavior.from(bottomSheet).apply {
                peekHeight =
                    (resources.displayMetrics.heightPixels * BOTTOM_SHEET_HEIGHT_RATIO).toInt()
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }

            autoSlideHandler.postDelayed(autoSlideRunnable, AUTO_SLIDE_INTERVAL_MS)
        }
        dialog.setOnDismissListener {
            autoSlideHandler.removeCallbacksAndMessages(null)
            sheetBinding.viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        }
        dialog.show()
    }

    private fun createDot(isSelected: Boolean): View {
        return View(requireContext()).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isSelected) Color.WHITE else Color.parseColor("#757579"))
            }
            layoutParams = LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._7sdp),
                resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._7sdp)
            ).apply {
                marginStart = resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._3sdp)
                marginEnd = resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._3sdp)
            }
        }
    }

    companion object {
        private const val AUTO_SLIDE_INTERVAL_MS = 2500L
        private const val BOTTOM_SHEET_HEIGHT_RATIO = 0.8f
    }
}
