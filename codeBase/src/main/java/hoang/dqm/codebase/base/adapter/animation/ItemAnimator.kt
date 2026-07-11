package hoang.dqm.codebase.base.adapter.animation

import android.animation.Animator
import android.view.View

interface ItemAnimator {
    fun animator(view: View): Animator
}
