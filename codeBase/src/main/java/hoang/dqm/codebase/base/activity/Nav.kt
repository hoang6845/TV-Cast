package hoang.dqm.codebase.base.activity

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import hoang.dqm.codebase.R
import kotlinx.coroutines.launch

fun Fragment.navigate(
    destination: Int, extraData: Bundle? = null, isPop: Boolean = false
) {
    if (!isAdded || view == null) return
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            activity?.navigate(destination, extraData, isPop)
        }
    }
}

fun Fragment.navigateFade(
    destination: Int, extraData: Bundle? = null, isPop: Boolean = false
) {
    if (!isAdded || view == null) return
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            activity?.navigateFade(destination, extraData, isPop)
        }
    }
}

fun FragmentActivity.navigate(
    destination: Int, extraData: Bundle? = null, isPop: Boolean = false, isPopAll: Boolean = false
) {
    try {
        val navController = findNavController(R.id.navHostFragment)
        navController.navigate(destination, extraData, navOptions {
            anim {
                enter = R.anim.slide_in_right
                exit = R.anim.slide_fade_out
                popEnter = R.anim.slide_fade_in
                popExit = R.anim.slide_out_right
            }
            if (isPopAll) {
                popUpTo(navController.graph.startDestinationId) {
                    inclusive = true
                }
            } else if (isPop) {
                navController.currentDestination?.id?.let { currentDestination ->
                    popUpTo(currentDestination) { inclusive = true }
                }
            }
        })
    } catch (ex: Exception) {
        Log.d("NavigateCheck", "Exception in navigate(): ${ex.message}")
        ex.printStackTrace()
    }
}

fun FragmentActivity.navigateFade(
    destination: Int, extraData: Bundle? = null, isPop: Boolean = false, isPopAll: Boolean = false
) {
    try {
        val navController = findNavController(R.id.navHostFragment)
        navController.navigate(destination, extraData, navOptions {
            anim {
                enter = R.anim.fade_in
                exit = R.anim.fade_out
                popEnter = R.anim.fade_in
                popExit = R.anim.fade_out
            }
            if (isPopAll) {
                popUpTo(navController.graph.startDestinationId) {
                    inclusive = true
                }
            } else if (isPop) {
                navController.currentDestination?.id?.let { currentDestination ->
                    popUpTo(currentDestination) { inclusive = true }
                }
            }
        })
    } catch (ex: Exception) {
        Log.d("NavigateCheck", "Exception in navigate(): ${ex.message}")
        ex.printStackTrace()
    }
}


fun FragmentActivity.navigateRight(
    destination: Int, extraData: Bundle? = null, isPop: Boolean = false, isPopAll: Boolean = false
) {
    try {
        val navController = findNavController(R.id.navHostFragment)
        navController.navigate(destination, extraData, navOptions {
            anim {
                enter = R.anim.slide_in_right
                exit = android.R.anim.fade_out
                popEnter = android.R.anim.fade_in
                popExit = R.anim.slide_out_right
            }
            if (isPopAll) {
                popUpTo(navController.graph.startDestinationId) {
                    inclusive = true
                }
            } else if (isPop) {
                navController.currentDestination?.id?.let { currentDestination ->
                    popUpTo(currentDestination) { inclusive = true }
                }
            }
        })
    } catch (ex: Exception) {
        ex.printStackTrace()
    }
}

fun FragmentActivity.navigateLeft(
    destination: Int, extraData: Bundle? = null, isPop: Boolean = false, isPopAll: Boolean = false
) {
    try {
        val navController = findNavController(R.id.navHostFragment)
        navController.navigate(destination, extraData, navOptions {
            anim {
                enter = R.anim.slide_in_left
                exit = android.R.anim.fade_out
                popEnter = android.R.anim.fade_in
                popExit = R.anim.slide_out_left
            }
            if (isPopAll) {
                popUpTo(navController.graph.startDestinationId) {
                    inclusive = true
                }
            } else if (isPop) {
                navController.currentDestination?.id?.let { currentDestination ->
                    popUpTo(currentDestination) { inclusive = true }
                }
            }
        })
    } catch (ex: Exception) {
        ex.printStackTrace()
    }
}

fun Fragment.navigateAnimationOpen(
    destination: Int, extraData: Bundle? = null, isPop: Boolean = false
) {
    if (!isAdded || view == null) return
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            activity?.navigateAnimationOpen(destination, extraData, isPop)
        }
    }
}

fun FragmentActivity.navigateAnimationOpen(
    destination: Int, extraData: Bundle? = null, isPop: Boolean = false, isPopAll: Boolean = false
) {
    try {
        val navController = findNavController(R.id.navHostFragment)
        navController.navigate(destination, extraData, navOptions {
            anim {
                enter = R.anim.slide_in_up
                exit = R.anim.slide_fade_out
                popEnter = R.anim.slide_fade_in
                popExit = R.anim.slide_out_down
            }
            if (isPopAll) {
                popUpTo(navController.graph.startDestinationId) {
                    inclusive = true
                }
            } else if (isPop) {
                navController.currentDestination?.id?.let { currentDestination ->
                    popUpTo(currentDestination) { inclusive = true }
                }
            }
        })
    } catch (ex: Exception) {
        Log.d("NavigateCheck", "Exception in navigate(): ${ex.message}")

        ex.printStackTrace()
    }
}


fun Fragment.popBackStack(destination: Int? = null) {
    if (!isAdded || view == null) return

    if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        activity?.popBackStack(destination)
    }
}

fun FragmentActivity.popBackStack(destination: Int? = null) {
    try {
        val navController = findNavController(R.id.navHostFragment)
        if (destination != null) navController.popBackStack(destination, false)
        else navController.navigateUp()
    } catch (ex: Exception) {
        ex.printStackTrace()
    }
}

fun Fragment.popBackstack(destination: Int, inclusive: Boolean = false): Boolean {
    activity?.let {
        try {
            return findNavController().popBackStack(destination, inclusive)
        } catch (ex: IllegalStateException) {
            ex.printStackTrace()
        } catch (ex: IllegalArgumentException) {
            ex.printStackTrace()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }
    return false
}

fun FragmentActivity.getCurrentFragment(): BaseFragment<*, *>? {
    val navHostFragment: Fragment? = supportFragmentManager.findFragmentById(R.id.navHostFragment)
    navHostFragment?.childFragmentManager?.fragments?.get(0)
    return navHostFragment as? BaseFragment<*, *>
}

fun Fragment.showDialog(dialogFragment: DialogFragment, tag: String? = null) {
    if (isAdded && !isDetached && activity != null) {
        try {
            dialogFragment.show(this.childFragmentManager, tag)
        } catch (ex: IllegalStateException) {
            ex.printStackTrace()
        }
    }
}

fun Fragment.navigateWithIntermediate(
    intermediate: Int,               // B
    destination: Int,                // C
    extraDataIntermediate: Bundle? = null,
    extraDataDestination: Bundle? = null,
    isPopA: Boolean = false          // nếu true, pop A trước
) {
    if (!isAdded || view == null) return

    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                val navController = findNavController()

                // Nếu cần pop A (current fragment) ra
                if (isPopA) {
                    navController.currentDestination?.id?.let { current ->
                        navController.popBackStack(current, true)
                    }
                }

                // 1. Navigate tới B (intermediate) push vào backstack
                navController.navigate(intermediate, extraDataIntermediate, navOptions {
                    launchSingleTop = true
                    anim {
                        enter = R.anim.fade_in
                        exit = R.anim.fade_out
                        popEnter = R.anim.fade_in
                        popExit = R.anim.fade_out
                    }
                })

                // 2. Ngay sau đó navigate tới C
                navController.navigate(destination, extraDataDestination, navOptions {
                    launchSingleTop = true
                    anim {
                        enter = R.anim.fade_in
                        exit = R.anim.fade_out
                        popEnter = R.anim.fade_in
                        popExit = R.anim.fade_out
                    }
                })
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }
}