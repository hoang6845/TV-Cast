package tpt.dev.monetization.ads.nativeAd

import androidx.annotation.IntDef
import com.google.android.gms.ads.nativead.NativeAd

sealed class ItemOrAd<out T> {
    data class Item<T>(
        val item: T
    ) : ItemOrAd<T>()

    data class Ad(
        val nativeAd: NativeAd
    ) : ItemOrAd<Nothing>()

    @ItemOrAdViewType
    val viewType: Int
        get() = when (this) {
            is Ad -> VIEW_TYPE_AD
            is Item -> VIEW_TYPE_ITEM
        }

    companion object {
        const val VIEW_TYPE_ITEM = 1
        const val VIEW_TYPE_AD = 2

        @IntDef(
            value = [
                VIEW_TYPE_ITEM,
                VIEW_TYPE_AD
            ]
        )
        @Retention(AnnotationRetention.SOURCE)
        annotation class ItemOrAdViewType
    }
}
