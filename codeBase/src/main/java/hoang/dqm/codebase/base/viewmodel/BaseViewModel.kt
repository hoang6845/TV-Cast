package hoang.dqm.codebase.base.viewmodel

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hoang.dqm.codebase.base.activity.ActivityManager
import hoang.dqm.codebase.base.activity.FragmentManager
import hoang.dqm.codebase.base.application.getBaseApplication
import hoang.dqm.codebase.utils.AppMonetization
import hoang.dqm.codebase.utils.premium
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

abstract class BaseViewModel: ViewModel(), DefaultLifecycleObserver, CoroutineScope {
    private val viewModelJob = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + viewModelJob

    val isLoading by lazy { MutableLiveData<Boolean>() }
    private var onError: ((err:String?) -> Unit)? = null
    protected val context by lazy {
        getBaseApplication()
    }
    protected val activityTopOrNull by lazy {
        ActivityManager.getTopActivityOrNull()
    }
    protected val fragmentTopOrNull by lazy {
        FragmentManager.getTopFragmentOrNull()
    }

    protected fun getString(id: Int): String {
        return context.getString(id)
    }

    protected fun launchHandler(
        onError: ((err: String?) -> Unit)? = null,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        this.onError = onError
        launch(handler, block = block)
    }

    private val handler = CoroutineExceptionHandler { _, exception ->
        exception.printStackTrace()
        handleError(throwable = exception)
    }

    fun <T> flowOnIO(process: suspend () -> T) = flow {
        emit(process())
    }.flowOn(Dispatchers.IO)

    fun <T> Flow<T>.subscribe(
        onLoading: Boolean = true,
        keepLoading: Boolean = false,
        onComplete: (() -> Unit)? = null,
        onNext: (T) -> Unit
    ) {
        onStart {
            withContext(Dispatchers.Main) {
                if (onLoading) {
                    isLoading.value = true
                }
            }
        }.onEach {
            withContext(Dispatchers.Main) {
                onNext.invoke(it)
                if (!keepLoading) {
                    hideLoading()
                }
            }
        }.catch {
            it.printStackTrace()
            onError?.invoke(it.message)
        }.onCompletion {
            withContext(Dispatchers.Main) {
                if (!keepLoading) {
                    hideLoading()
                }
                onComplete?.invoke()
            }
        }.launchIn(viewModelScope)
    }

    private fun handleError(
        throwable: Throwable? = null, error: String? = null
    ) {
        throwable?.printStackTrace()
        onError?.invoke(throwable?.message)
    }

    protected fun showLoading() {
        launchHandler {
            if (isLoading.value == null || isLoading.value == false) {
                isLoading.value = true
            }
        }
    }

    protected fun hideLoading(isNow: Boolean = false) {
        val value = isLoading.value == true
        if (value) {
            launchHandler {
                if (isNow.not()) {
                    delay(300L)
                }
                isLoading.value = false
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        hideLoading(isNow = true)
        super.onDestroy(owner)
    }

    override fun onCleared() {
        super.onCleared()
        viewModelJob.cancel()
    }

    val isSubscribed: Boolean
        get() = AppMonetization.premium.isSubscribed()

    val hasPurchased by lazy {
        AppMonetization.premium.getSubscribedStateFlow()
    }
}
