package com.tvchromecast.screenmirroringplus.ui.intro

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.tvchromecast.screenmirroringplus.R
import com.tvchromecast.screenmirroringplus.databinding.FragmentIntroBinding
import com.tvchromecast.screenmirroringplus.model.entity.SlideItem
import com.tvchromecast.screenmirroringplus.utils.AppConstants
import com.tvchromecast.screenmirroringplus.utils.Common
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.navigateWithIntermediate
import tpt.dev.monetization.ads.nativeAd.view.ViewNativeAd


class IntroFragment : BaseFragment<FragmentIntroBinding, IntroViewModel>() {
    override val viewModelClass: Class<IntroViewModel>
        get() = IntroViewModel::class.java

    override fun inflateBinding(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?
    ): FragmentIntroBinding {
        return FragmentIntroBinding.inflate(inflater, container, false)
    }

    private lateinit var slides: List<SlideItem>
    private lateinit var introAdapter: ViewPager2Adapter<SlideItem>

    private var closeCountdownTimer: CountDownTimer? = null

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (position == 2) {
                binding.viewPager2.post {
                    startFullNativeCountdown(position)
                }
            } else {
                closeCountdownTimer?.cancel()
                closeCountdownTimer = null
            }
        }
    }

    @SuppressLint("CutPasteId")
    override fun initView() {
//        adjustInsetsForBottomNavigation(binding.viewPager2)
        adjustInsetsForBottomMargin(binding.viewPager2)

        slides = listOf(
            SlideItem(
                getString(R.string.text_welcome),
                getString(R.string.text_mirror_your_screen_to_any_tv_wirelessly_and_enjoy_your_content_on_the_big_screen), R.drawable.intro_1),
            SlideItem(getString(R.string.text_let_s_find_your_tv),
                getString(R.string.text_allow_local_network_access_to_connect_to_your_tv_devices_on_the_same_wi_fi_network), R.drawable.intro_2),
            SlideItem(getString(R.string.text_mirror_phone_screen),
                getString(R.string.text_cast_from_phone_in_real_time_games_web_and_more), R.drawable.intro_3),
            SlideItem(
                getString(R.string.text_hd_quality_streaming),
                getString(R.string.text_stream_your_photos_videos_and_apps_in_stunning_hd_quality_to_your_tv), R.drawable.intro_4),
        )

        introAdapter = ViewPager2Adapter(
            items = slides,

            getLayoutResId = { _, position ->
                when (position) {
//                    0 -> R.layout.item_intro_ad
//                    1 -> R.layout.item_intro
//                    2 -> R.layout.full_native
//                    3 -> R.layout.item_intro
//                    4 -> R.layout.item_intro_ad
//                    else -> R.layout.item_intro
                    0 -> R.layout.item_intro
                    1 -> R.layout.item_intro
                    2 -> R.layout.item_intro
                    3 -> R.layout.item_intro
                    4 -> R.layout.item_intro_ad
                    else -> R.layout.item_intro
                }
            },

            bindView = { view, item, position ->
                when (position) {
//                    0 -> bindIntroType2(view, item, position)
//                    1 -> bindIntroType1(view, item, position)
//                    2 -> bindIntroType3(view, item, position)
//                    3 -> bindIntroType1(view, item, position)
//                    4 -> bindIntroType2(view, item, position)
//                    else -> bindIntroType3(view, item, position)
                    0 -> bindIntroType1(view, item, position)
                    1 -> bindIntroType1(view, item, position)
                    2 -> bindIntroType1(view, item, position)
                    3 -> bindIntroType1(view, item, position)
                    4 -> bindIntroType1(view, item, position)
                    else -> bindIntroType1(view, item, position)
                }
            }
        )

        binding.viewPager2.adapter = introAdapter
        binding.viewPager2.offscreenPageLimit = 4
    }

    @SuppressLint("CutPasteId")
    private fun bindIntroType1(view: View, item: SlideItem, position: Int) {
        view.findViewById<TextView>(R.id.title_1).isVisible = false
        view.findViewById<TextView>(R.id.des_1).isVisible = false
        view.findViewById<TextView>(R.id.title_2).isVisible = false
        view.findViewById<TextView>(R.id.des_2).isVisible = false
        view.findViewById<TextView>(R.id.title_3).isVisible = position != 0
        view.findViewById<TextView>(R.id.des_3).isVisible = position != 0
//        view.findViewById<TextView>(R.id.title_3).text = buildSpannedString {
//            color(Color.parseColor("#ffffff")) {
//                append(getString(R.string.text_start))
//            }
//            append(" ")
//            color(Color.parseColor("#E6CB95")) {
//                append(getString(R.string.text__3_day_free))
//            }
//            append(" ")
//            color(Color.parseColor("#ffffff")) {
//                append(getString(R.string.text_trial))
//            }
//        }
        view.findViewById<TextView>(R.id.title_3).text = item.title
        view.findViewById<TextView>(R.id.des_3).text = item.description
        val button = view.findViewById<TextView>(R.id.btn_save)
        button.text = if (position == 0) {
            getString(R.string.text_get_started)
        } else {
            getString(R.string.text_continue)
        }
        view.findViewById<ImageView>(R.id.img_intro).setImageResource(item.imageRes)
        bindIntroFooter(view, position)
        button.setOnClickListener {
            handleNext(position)
        }
    }

    private fun bindIntroFooter(view: View, position: Int) {
        val legalText: TextView? = view.findViewById(R.id.tv_intro_terms_policy)
        val dotsLayout: LinearLayout? = view.findViewById(R.id.layout_intro_dots)
        val showLegal = position == 0

        legalText?.isVisible = showLegal
        dotsLayout?.isVisible = !showLegal

        if (showLegal) {
            dotsLayout?.removeAllViews()
            legalText?.let(::bindLegalText)
        } else {
            dotsLayout?.let { bindDots(it, position) }
        }
    }

    private fun bindLegalText(textView: TextView) {
        val termsText = getString(R.string.text_term_conditions)
        val policyText = getString(R.string.text_policy)
        val fullText = getString(R.string.text_intro_terms_policy, termsText, policyText)
        val spannable = SpannableString(fullText).apply {
            setIntroLink(termsText, AppConstants.term)
            setIntroLink(policyText, AppConstants.policy)
        }

        textView.text = spannable
        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.highlightColor = Color.TRANSPARENT
    }

    private fun SpannableString.setIntroLink(label: String, url: String) {
        val start = toString().indexOf(label)
        if (start < 0) return

        setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    Common.openWebView(widget.context, url)
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = Color.WHITE
                    ds.isUnderlineText = false
                }
            },
            start,
            start + label.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun bindDots(layout: LinearLayout, position: Int) {
        layout.removeAllViews()
        repeat(slides.size) { index ->
            layout.addView(createDot(index == position))
        }
    }

    private fun createDot(isSelected: Boolean): View {
        return View(requireContext()).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isSelected) Color.WHITE else Color.parseColor("#7C7C82"))
            }
            layoutParams = LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._4sdp),
                resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._4sdp)
            ).apply {
                marginStart = resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._2sdp)
                marginEnd = resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._2sdp)
            }
        }
    }

    private fun bindIntroType2(view: View, item: SlideItem, position: Int) {
        view.findViewById<TextView>(R.id.title_1).isVisible = position == 1
        view.findViewById<TextView>(R.id.des_1).isVisible = position == 1
        view.findViewById<TextView>(R.id.title_2).isVisible = position == 3
        view.findViewById<TextView>(R.id.des_2).isVisible = position == 3
        view.findViewById<TextView>(R.id.title_3).isVisible = position == 4
        view.findViewById<TextView>(R.id.des_3).isVisible = position == 4
//        view.findViewById<TextView>(R.id.title_3).text = buildSpannedString {
//            color(Color.parseColor("#ffffff")) {
//                append(getString(R.string.text_start))
//            }
//            append(" ")
//            color(Color.parseColor("#E6CB95")) {
//                append(getString(R.string.text__3_day_free))
//            }
//            append(" ")
//            color(Color.parseColor("#ffffff")) {
//                append(getString(R.string.text_trial))
//            }
//        }
        view.findViewById<TextView>(R.id.btn_save).text = getString(R.string.text_next)
        view.findViewById<ImageView>(R.id.img_intro).setImageResource(item.imageRes)
        view.findViewById<TextView>(R.id.btn_save).setOnClickListener {
            handleNext(position)
        }

//        if (position == 0) {
//            view.findViewById<ViewNativeAd>(R.id.viewNativeAd)?.let { nativeAdView ->
//                loadSingleNative(
//                    nativeAdView,
//                    hoang.dqm.codebase.R.string.ads_native_intro1,
//                    false
//                )
//            }
//        } else {
//            view.findViewById<ViewNativeAd>(R.id.viewNativeAd)?.let { nativeAdView ->
//                loadSingleNative(
//                    nativeAdView,
//                    hoang.dqm.codebase.R.string.ads_native_intro2,
//                    false
//                )
//            }
//        }
    }

    private fun bindIntroType3(view: View, item: SlideItem, position: Int) {
        val closeView = view.findViewById<ImageView>(R.id.ivClose)
        val countdownView = view.findViewById<TextView>(R.id.tvCloseCountdown)

        closeView?.visibility = View.INVISIBLE
        countdownView?.visibility = View.GONE

        closeView?.setOnClickListener {
            closeCountdownTimer?.cancel()
            closeCountdownTimer = null
            handleNext(position)
        }

//        view.findViewById<ViewNativeAd>(R.id.viewNativeAd)?.let { nativeAdView ->
//            loadSingleNative(nativeAdView, hoang.dqm.codebase.R.string.ads_native_intro_full_id, false)
//        }
    }

    private fun startFullNativeCountdown(position: Int) {
        closeCountdownTimer?.cancel()
        closeCountdownTimer = null

        val view = introAdapter.getBoundView(position) ?: return

        val closeView = view.findViewById<ImageView>(R.id.ivClose) ?: return
        val countdownView = view.findViewById<TextView>(R.id.tvCloseCountdown) ?: return

        closeView.visibility = View.INVISIBLE
        countdownView.visibility = View.VISIBLE
        countdownView.text = "4s"

        closeCountdownTimer = object : CountDownTimer(4000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val remainingSeconds = (millisUntilFinished / 1000L).toInt()

                if (remainingSeconds > 0) {
                    countdownView.text = "${remainingSeconds}s"
                }
            }

            override fun onFinish() {
                if (!isAdded) return

                countdownView.visibility = View.GONE
                closeView.visibility = View.VISIBLE
            }
        }.start()
    }

    private fun handleNext(position: Int) {
        if (position < slides.lastIndex) {
            binding.viewPager2.currentItem = position + 1
        } else {
            val bundle = Bundle().apply {
                putBoolean("isFromSplash", true)
            }

            navigateWithIntermediate(
                R.id.homeFragment,
                R.id.IAPFragment,
                bundle,
                bundle,
                isPopA = true
            )
        }
    }

    override fun initListener() {
        binding.viewPager2.registerOnPageChangeCallback(pageChangeCallback)
    }

    override fun initData() {
    }

    override fun onDestroyView() {
        closeCountdownTimer?.cancel()
        closeCountdownTimer = null
        binding.viewPager2.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroyView()
    }
}
