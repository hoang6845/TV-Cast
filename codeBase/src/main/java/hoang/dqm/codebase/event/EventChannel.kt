package hoang.dqm.codebase.event

import hoang.dqm.codebase.data.EventModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private var externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private var singleChannel = Channel<Any>(Channel.UNLIMITED)
private var purchaseEventFlow: SharedFlow<Any> =
    singleChannel.receiveAsFlow().shareIn(externalScope, SharingStarted.WhileSubscribed())

fun <T> sendEvent(data: T) {
    CoroutineScope(Dispatchers.IO).launch {
        (data as? Any)?.let { singleChannel.send(it) }
    }
}

@Suppress("UNCHECKED_CAST")
fun <T> subscribeEvent(onDone: (data: T) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        purchaseEventFlow.distinctUntilChanged().onEach { result ->
            withContext(Dispatchers.Main) {
                (result as? T)?.let {
                    onDone(it)
                }
            }
        }.catch { throwable ->
            throwable.printStackTrace()
        }.launchIn(this)
    }
}

fun <T> subscribeEventByKey(key: String, onDone: (data: T) -> Unit) {
    subscribeEvent<T> { data ->
        val eventModel = data as? EventModel<*>
        if (eventModel != null) {
            if (eventModel.eventKey == key) {
                CoroutineScope(Dispatchers.Main).launch {
                    onDone.invoke(data)
                }
            }
        }
    }
}

fun subscribeEventNetwork(onDone: (data: Boolean) -> Unit) {
    subscribeEventByKey<EventModel<Boolean>>(EventModel.EVENT_NETWORK) { data ->
        CoroutineScope(Dispatchers.Main).launch {
            onDone.invoke(data.data)
        }
    }
}

fun subscribeEventNetworkDelay(delayDuration: Long = 5000, onDone: (data: Boolean) -> Unit) {
    subscribeEventByKey<EventModel<Boolean>>(EventModel.EVENT_NETWORK) { data ->
        CoroutineScope(Dispatchers.Main).launch {
            delay(delayDuration)
            onDone.invoke(data.data)
        }
    }
}