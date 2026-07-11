package hoang.dqm.codebase.utils.pref

import android.annotation.SuppressLint
import android.content.Context
import java.io.Serializable

class LocalCache private constructor(){
    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: LocalCache? = null

        @JvmStatic
        fun getInstance() = instance ?: synchronized(this){
            instance ?: LocalCache().also {
                instance = it
            }
        }
    }

    private val cache: SpUtils by lazy { SpUtils.getDefaultInstance() }
    private var mContext: Context? = null

    fun initContext(base: Context){
        mContext = base
    }

    fun put(key:String, value: Any?){
        val context = mContext?:return
        cache.saveData(context, value, key)
    }

    fun <T> getData(key: String, tClass: Class<T>): T? {
        val context = mContext?:return null
        return cache.getData(context, key, tClass)
    }

    fun <T : Serializable> getData(key: String, tClass: Class<T>): T? {
        val context = mContext ?: return null
        return cache.getData(context, key, tClass)
    }

}