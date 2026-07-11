package hoang.dqm.codebase.data


import android.os.Parcelable
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

@Keep
@Parcelize
data class EventModel<T>(
    @SerializedName("event_key")
    val eventKey: String = "",
    @SerializedName("data")
    val data: @RawValue T
) : Parcelable {

    companion object {
        const val EVENT_NETWORK = "EVENT_NETWORK"
    }
}