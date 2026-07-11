package hoang.dqm.codebase.ui.features.main

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.isVisible
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.viewbinding.ViewBinding
import hoang.dqm.codebase.utils.singleClick
import hoang.dqm.codebase.R
import hoang.dqm.codebase.base.activity.BaseActivity
import hoang.dqm.codebase.base.viewmodel.BaseViewModel
import hoang.dqm.codebase.event.subscribeEventNetwork
import hoang.dqm.codebase.utils.openSettingNetWork

abstract class BaseMainActivity<VB : ViewBinding, VM : BaseViewModel> : BaseActivity<VB, VM>(){
    abstract val graphResId: Int
    protected var navController: NavController? = null
    override fun initView() {
        (supportFragmentManager.findFragmentById(R.id.navHostFragment) as? NavHostFragment)?.let { navHostFragment ->
            val graph = navHostFragment.navController.navInflater.inflate(graphResId = graphResId)
            navHostFragment.navController.graph = graph
            navController = navHostFragment.navController
        }
    }

    override fun initListener() {
        subscribeEventNetwork { online ->
            runOnUiThread {
                findViewById<View>(R.id.layoutNoInternet).isVisible = online.not()
            }
        }
        findViewById<View>(R.id.buttonSetting).singleClick { openSettingNetWork() }
    }

    override fun initData() {

    }
}