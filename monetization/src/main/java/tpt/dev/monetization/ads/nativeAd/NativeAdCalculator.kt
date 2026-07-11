package tpt.dev.monetization.ads.nativeAd

import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import tpt.dev.monetization.R

object NativeAdCalculator {
    fun populateNativeAdView(
        nativeAd: NativeAd,
        nativeAdView: NativeAdView
    ) {
        nativeAdView.mediaView = nativeAdView.findViewById(R.id.ad_media)
        nativeAdView.headlineView = nativeAdView.findViewById(R.id.ad_headline)
        nativeAdView.bodyView = nativeAdView.findViewById(R.id.ad_body)
        nativeAdView.callToActionView = nativeAdView.findViewById(R.id.ad_call_to_action)
        nativeAdView.iconView = nativeAdView.findViewById(R.id.ad_app_icon)
        nativeAdView.starRatingView = nativeAdView.findViewById(R.id.ad_stars)
        nativeAdView.advertiserView = nativeAdView.findViewById(R.id.ad_advertiser)
        (nativeAdView.headlineView as? TextView)?.text = nativeAd.headline
        if (nativeAd.body == null) {
            nativeAdView.bodyView?.visibility = View.INVISIBLE
        } else {
            nativeAdView.bodyView?.visibility = View.VISIBLE
            (nativeAdView.bodyView as? TextView)?.text = nativeAd.body
        }
        if (nativeAd.callToAction == null) {
            nativeAdView.callToActionView?.visibility = View.INVISIBLE
        } else {
            nativeAdView.callToActionView?.visibility = View.VISIBLE
            (nativeAdView.callToActionView as? TextView)?.text =
                nativeAd.callToAction
            (nativeAdView.callToActionView as? Button)?.text =
                nativeAd.callToAction
        }
        if (nativeAd.icon == null) {
            nativeAdView.iconView?.visibility = View.INVISIBLE
        } else {
            (nativeAdView.iconView as? ImageView)?.setImageDrawable(nativeAd.icon?.drawable)
            nativeAdView.iconView?.visibility = View.VISIBLE
        }
        if (nativeAd.starRating == null) {
            nativeAdView.starRatingView?.visibility = View.INVISIBLE
        } else {
            (nativeAdView.starRatingView as? RatingBar)?.rating =
                nativeAd.starRating?.toFloat() ?: 0F
            nativeAdView.starRatingView?.visibility = View.VISIBLE
        }
        if (nativeAd.advertiser == null) {
            nativeAdView.advertiserView?.visibility = View.GONE
        } else {
            (nativeAdView.advertiserView as? TextView)?.text = nativeAd.advertiser
            nativeAdView.advertiserView?.visibility = View.VISIBLE
        }
        nativeAdView.setNativeAd(nativeAd)
    }
}
