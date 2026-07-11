package com.example.base

import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.base.utils.AdsConstants
import com.example.base.utils.AppConstants
import com.example.base.utils.ThemeManager
import com.makeramen.roundedimageview.BuildConfig
import dagger.hilt.android.HiltAndroidApp
import hoang.dqm.codebase.base.application.BaseApplication
import hoang.dqm.codebase.data.AppInfo
import hoang.dqm.codebase.firebase.SeverRemoteConfig
import hoang.dqm.codebase.utils.AppMonetization
import hoang.dqm.codebase.utils.billing
import hoang.dqm.codebase.utils.premium
import tpt.dev.monetization.ads.AdsManager
import tpt.dev.monetization.subs.model.IAPProduct
import tpt.dev.monetization.subs.model.IAPProductType
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : BaseApplication(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()


    override val appInfo: AppInfo by lazy {
        AppInfo(
            appId = BuildConfig.APPLICATION_ID,
            icon = R.mipmap.ic_launcher,
            appName = ContextCompat
                .getContextForLanguage(this)
                .getString(R.string.app_name),
            rawGit = SeverRemoteConfig.DATA_BASE_URL_GITHUB,
            isDebug = false,
            policy = AppConstants.policy,
            emailFeedback = "",
            term = AppConstants.term,
        )
    }

    override fun onCreate() {
        super.onCreate()
        ThemeManager.applyTheme(this)

        instance = this
        AdsManager.initialize(this, AppMonetization.premium)
        
        AdsManager.getInstance().configure(
            adsConstants = AdsConstants,
            disableAppOpenAdActivities = emptyList(), // Có thể thêm activities không show app open ad
            isAllowShowOpenAd = true
        )

        AppMonetization.billing.configure(
            iapProducts = listOf(
                IAPProduct(
                    productType = IAPProductType.Subscription,
                    productId = getString(hoang.dqm.codebase.R.string.billing_sub_week)
                ),
                IAPProduct(
                    productType = IAPProductType.Subscription,
                    productId = getString(hoang.dqm.codebase.R.string.billing_sub_year)
                )
            )
        )
    }

    companion object {
        lateinit var instance: MainApplication
            private set
    }
}
