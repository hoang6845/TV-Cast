package hoang.dqm.codebase.service.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresPermission
import hoang.dqm.codebase.data.EventModel
import hoang.dqm.codebase.event.sendEvent
import hoang.dqm.codebase.utils.isNetworkAvailable

class NetworkStatusReceiver : BroadcastReceiver() {

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context?, intent: Intent?) {
        val isNet = isNetworkAvailable()
        sendEvent(EventModel(EventModel.Companion.EVENT_NETWORK, isNet))
    }
}