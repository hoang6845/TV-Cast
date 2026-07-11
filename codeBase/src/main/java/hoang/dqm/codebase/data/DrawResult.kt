package hoang.dqm.codebase.data

import android.net.Uri
import android.os.Parcelable
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Keep
@Parcelize
data class DrawResult(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("image") val path: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("category") val category: String? = null,
    @SerializedName("point_position") val pointPosition: Int = 1,
    @SerializedName("create_at") val createAt: Long? = System.currentTimeMillis(),
    @SerializedName("is_saved") val isSaved: Boolean = true
) : Parcelable {

    fun getPathSafe(): String {
        return path ?: ""
    }

    fun getUri(): Uri {
        return Uri.parse(getPathSafe())
    }

    fun getCreateAtSafe(): Long {
        return createAt ?: System.currentTimeMillis()
    }
}