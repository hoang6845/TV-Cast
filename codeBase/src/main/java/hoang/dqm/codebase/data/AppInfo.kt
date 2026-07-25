package hoang.dqm.codebase.data

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.fragment.app.Fragment
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
@Keep
data class AppInfo(
    @SerializedName("id")
    val appId: String,
    @SerializedName("app_name")
    val appName: String,
    @SerializedName("icon")
    val icon: Int,
    @SerializedName("is_debug")
    val isDebug: Boolean,
    @SerializedName("raw_git")
    val rawGit: String,
    @SerializedName("policy")
    val policy: String,
    @SerializedName("email_feedback")
    val emailFeedback: String,
    @SerializedName("term")
    val term: String,
    @SerializedName("home_class")
    val homeClass: Class<out Fragment>,
) : Parcelable {
    fun isHomeClass(keyClass: String): Boolean {
        return keyClass == homeClass.name
    }
}