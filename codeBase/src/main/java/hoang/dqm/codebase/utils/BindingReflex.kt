@file:Suppress("UNCHECKED_CAST")

package hoang.dqm.codebase.utils

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.viewbinding.ViewBinding
import java.lang.RuntimeException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.ParameterizedType

object BindingReflex {

    /**
     * ViewBinding
     *
     * @param <V>    ViewBinding
     * @param aClass
     * @param from   layoutInflater
     * @return viewBinding
    </V> */
    fun <V : ViewBinding> reflexViewBinding(aClass: Class<*>, from: LayoutInflater?): V {
        var exception: java.lang.Exception? = null
        try {
            val tClass = findViewBindingClass(aClass)
                ?: throw IllegalStateException("Can not find ViewBinding generic type for ${aClass.name}")
            try {
                val inflate = tClass.getMethod("inflate", LayoutInflater::class.java)
                return inflate.invoke(null, from) as V
            } catch (e: Exception) {
                e.printStackTrace()
                exception = e
            }
        } catch (e: NoSuchMethodException) {
            exception = e
        } catch (e: IllegalAccessException) {
            exception = e
        } catch (e: InvocationTargetException) {
            exception = e
        } catch (e: java.lang.Exception) {
            exception = e
        } finally {
            exception?.printStackTrace()
        }
        throw  exception ?: Throwable("Error binding")
    }

    /**
     * ViewBinding
     */
    fun <V : ViewBinding> reflexViewBinding(
        aClass: Class<*>,
        from: LayoutInflater?,
        viewGroup: ViewGroup?,
        b: Boolean
    ): V {
        var exception: java.lang.Exception? = null
        try {
            val tClass = findViewBindingClass(aClass)
                ?: throw IllegalStateException("Can not find ViewBinding generic type for ${aClass.name}")
            try {
                val inflate = tClass.getDeclaredMethod(
                    "inflate",
                    LayoutInflater::class.java,
                    ViewGroup::class.java,
                    Boolean::class.javaPrimitiveType
                )
                return inflate.invoke(null, from, viewGroup, b) as V
            } catch (e: Exception) {
                e.printStackTrace()
                exception = e
            }
        } catch (e: NoSuchMethodException) {
            exception = e
            e.printStackTrace()
        } catch (e: IllegalAccessException) {
            exception = e
            e.printStackTrace()
        } catch (e: InvocationTargetException) {
            exception = e
            e.printStackTrace()
        } catch (e: java.lang.Exception) {
            exception = e
            e.printStackTrace()
        } finally {
            exception?.printStackTrace()
        }
        throw exception ?: RuntimeException("Error binding")
    }

    private fun findViewBindingClass(aClass: Class<*>): Class<Any>? {
        var currentClass: Class<*>? = aClass
        while (currentClass != null && currentClass != Any::class.java) {
            val genericSuperclass = currentClass.genericSuperclass
            if (genericSuperclass is ParameterizedType) {
                genericSuperclass.actualTypeArguments.forEach { type ->
                    val tClass = type as? Class<*> ?: return@forEach
                    if (ViewBinding::class.java.isAssignableFrom(tClass)) {
                        return tClass as Class<Any>
                    }
                }
            }
            currentClass = currentClass.superclass
        }
        return null
    }

}
