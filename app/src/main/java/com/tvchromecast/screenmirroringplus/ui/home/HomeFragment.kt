package com.tvchromecast.screenmirroringplus.ui.home

import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.tvchromecast.screenmirroringplus.R
import com.tvchromecast.screenmirroringplus.cast.CastReceiverIds
import com.tvchromecast.screenmirroringplus.databinding.DialogCastMediaChooserBinding
import com.tvchromecast.screenmirroringplus.databinding.FragmentHomeBinding
import com.tvchromecast.screenmirroringplus.databinding.ItemHomeConnectBrowserBinding
import com.tvchromecast.screenmirroringplus.databinding.LayoutHomeHowToConnectSheetBinding
import com.tvchromecast.screenmirroringplus.model.entity.ItemFunc
import com.tvchromecast.screenmirroringplus.ui.cast_media.CastMediaFragment
import com.tvchromecast.screenmirroringplus.ui.intro.ViewPager2Adapter
import com.tvchromecast.screenmirroringplus.utils.AppConstants
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.navigate
import hoang.dqm.codebase.service.session.saveFirst


class HomeFragment : BaseFragment<FragmentHomeBinding, HomeViewModel>() {
    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun inflateBinding(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?
    ): FragmentHomeBinding {
        return FragmentHomeBinding.inflate(inflater, container, false)
    }

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
        adjustInsetsForBottomNavigation(binding.top)
        adjustInsetsForBottomMargin(binding.rvHomeFunc)
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
            navigate(R.id.settingFragment)
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
        val chooserBinding = DialogCastMediaChooserBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(chooserBinding.root)
            .create()

        fun openCastMedia(mode: String) {
            dialog.dismiss()
            navigate(
                R.id.castMediaFragment,
                Bundle().apply {
                    putString(CastMediaFragment.ARG_MODE, mode)
                }
            )
        }

        chooserBinding.optionPhotos.setOnClickListener {
            openCastMedia(CastMediaFragment.MODE_PHOTO)
        }
        chooserBinding.optionVideo.setOnClickListener {
            openCastMedia(CastMediaFragment.MODE_VIDEO)
        }
        chooserBinding.buttonCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(
                resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._280sdp),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    private fun showHowToConnectSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = LayoutHomeHowToConnectSheetBinding.inflate(layoutInflater)
        val mediaRouter = MediaRouter.getInstance(requireContext())
        val castRouteSelector = MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(CastReceiverIds.CUSTOM_RECEIVER)
            )
            .build()
        val descriptions = listOf(
            getString(R.string.text_home_connect_guide_desc),
            getString(R.string.text_home_connect_same_wifi_desc)
        )
        var browserPageBinding: ItemHomeConnectBrowserBinding? = null

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

        fun availableCastRoutes(): List<MediaRouter.RouteInfo> {
            return mediaRouter.routes
                .filter { route ->
                    route.isEnabled &&
                        !route.isDefaultOrBluetooth &&
                        route.matchesSelector(castRouteSelector)
                }
                .distinctBy { route -> route.id.ifBlank { route.name.toString() } }
        }

        fun bindAvailableDevices() {
            val pageBinding = browserPageBinding ?: return
            val routes = availableCastRoutes()
            pageBinding.layoutAvailableDevicesScroll.isVisible = routes.isNotEmpty()
            pageBinding.layoutAvailableDevices.removeAllViews()

            routes.forEachIndexed { index, route ->
                val row = layoutInflater.inflate(
                    R.layout.item_tv_remote_device,
                    pageBinding.layoutAvailableDevices,
                    false
                )
                row.findViewById<TextView>(R.id.device_name).text = route.name
                row.findViewById<TextView>(R.id.device_meta).text =
                    route.description?.toString()?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.text_home_connect_available_to_connect)
                row.setOnClickListener {
                    route.select()
                    dialog.dismiss()
                }
                row.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._66sdp)
                ).apply {
                    topMargin = if (index == 0) 0 else {
                        resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._8sdp)
                    }
                }
                pageBinding.layoutAvailableDevices.addView(row)
            }
        }

        val mediaRouterCallback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                bindAvailableDevices()
            }

            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
                bindAvailableDevices()
            }

            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                bindAvailableDevices()
            }
        }

        sheetBinding.viewPager.adapter = ViewPager2Adapter(
            items = listOf(
                R.layout.item_home_connect_steps,
                R.layout.item_home_connect_browser
            ),
            getLayoutResId = { item, _ -> item },
            bindView = { view, _, position ->
                if (position == HOW_TO_CONNECT_BROWSER_TAB_POSITION) {
                    browserPageBinding = ItemHomeConnectBrowserBinding.bind(view)
                    browserPageBinding?.layoutHelpChip?.setOnClickListener {
                        dialog.dismiss()
                        navigate(R.id.faqFragment)
                    }
                    bindAvailableDevices()
                }
            }
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
        }
        runCatching {
            CastContext.getSharedInstance(requireContext())
                .setReceiverApplicationId(CastReceiverIds.CUSTOM_RECEIVER)
        }
        mediaRouter.addCallback(
            castRouteSelector,
            mediaRouterCallback,
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or
                MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS
        )
        dialog.setOnDismissListener {
            mediaRouter.removeCallback(mediaRouterCallback)
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
        private const val HOW_TO_CONNECT_BROWSER_TAB_POSITION = 1
        private const val BOTTOM_SHEET_HEIGHT_RATIO = 0.8f
    }
}
