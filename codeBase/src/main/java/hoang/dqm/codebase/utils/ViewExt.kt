@file:Suppress("DEPRECATION", "unused")

package hoang.dqm.codebase.utils

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Point
import android.os.Build
import android.text.SpannableString
import android.text.style.UnderlineSpan
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.core.graphics.createBitmap
import com.permissionx.guolindev.PermissionX
import hoang.dqm.codebase.R
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.application.getBaseApplication
import hoang.dqm.codebase.service.session.isFirstScene
import hoang.dqm.codebase.service.session.setFirstScene
import hoang.dqm.codebase.service.sound.AppMusicPlayer

fun BaseFragment<*, *>.trackingScreen() {
    if (isFirstScene(clazz = this)) {
        setFirstScene(clazz = this, isFirst = false)
    }
}

fun View?.removeParentViewGroup() {
    this?.let { view ->
        try {
            (view.parent as? ViewGroup)?.removeView(view)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun TextView.underLineText() {
    val content = SpannableString(text)
    content.setSpan(UnderlineSpan(), 0, content.length, 0)
    text = content
}

fun Context.getScreenWidth(): Int {
    val wm = this.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val display = wm.defaultDisplay
    val size = Point()
    display.getSize(size)
    return size.x
}

fun Context.getScreenHeight(): Int {
    val wm = this.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val display = wm.defaultDisplay
    val size = Point()
    display.getSize(size)
    return size.y
}

fun Activity.getScreenWidth(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val windowMetrics: WindowMetrics = windowManager.currentWindowMetrics
        val insets: Insets =
            windowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        windowMetrics.bounds.width() - insets.left - insets.right
    } else {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getMetrics(displayMetrics)
        displayMetrics.widthPixels
    }
}

fun Activity.getScreenHeight(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val windowMetrics: WindowMetrics = windowManager.currentWindowMetrics
        val insets: Insets =
            windowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        windowMetrics.bounds.height() - insets.top - insets.bottom
    } else {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getMetrics(displayMetrics)
        displayMetrics.heightPixels
    }
}

fun <T : View> T.click(action: (T) -> Unit) {
    setOnClickListener {
        action(this)
    }
}

fun <T : View> T.singleClick(interval: Long = 300L, action: ((T) -> Unit)?) {
    setOnClickListener(SingleClickListener(interval, action))
}


fun <T : View> singleClick(vararg view: T, interval: Long = 300L, action: ((T) -> Unit)?) {
    view.forEach {
        it.setOnClickListener(SingleClickListener(interval, action))
    }
}

class SingleClickListener<T : View>(
    private val interval: Long = 300L, private var clickFunc: ((T) -> Unit)?
) : View.OnClickListener {
    private var lastClickTime = 0L

    override fun onClick(v: View) {
        val nowTime = System.currentTimeMillis()
        if (nowTime - lastClickTime > interval) {
            v.scaleAnimation {
                if (clickFunc != null) {
                    clickFunc?.invoke(v as T)
                }
            }
            lastClickTime = nowTime
        }
//        AppMusicPlayer.playFxMusic(R.raw.sound_click)
    }
}

fun AppCompatEditText.getInputText(): String? {
    return when (text.toString()) {
        "null" -> null
        else -> text.toString()
    }
}

@SuppressLint("InlinedApi")
fun Activity.fullStatusBar() {
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
    window.statusBarColor = Color.TRANSPARENT
    window.decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
}


@SuppressLint("InternalInsetResource", "DiscouragedApi")
fun navigationBarHeight(context: Context): Int {
    return try {
        val resources = context.resources
        val identifier = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (identifier <= 0) {
            0
        } else resources.getDimensionPixelSize(identifier)
    } catch (unused: Exception) {
        0
    }
}


fun isLGDevice(): Boolean {
    return "LG-M700" == Build.MODEL
}


/**
 * dp to px
 */
val Float.dpToPxF: Float
    get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this, Resources.getSystem().displayMetrics
    )

val Int.dpToPxF: Float
    get() = this.toFloat().dpToPxF

val Float.dpToPx: Int
    get() = this.dpToPxF.toInt()

val Int.dpToPx: Int
    get() = this.dpToPxF.toInt()

fun Context.dpToPx(dp: Int): Int {
    val displayMetrics: DisplayMetrics = resources.displayMetrics
    return (Resources.getSystem().displayMetrics.density * dp).roundToInt().toInt()
}


fun View.scaleAnimation(
    duration: Long = 200, scaleFactor: Float = 1.05f, onAnimationEnd: (() -> Unit)? = null
) {
    val scaleX = ObjectAnimator.ofFloat(this, View.SCALE_X, 1f, scaleFactor, 1f)
    val scaleY = ObjectAnimator.ofFloat(this, View.SCALE_Y, 1f, scaleFactor, 1f)

    val animatorSet = AnimatorSet().apply {
        playTogether(scaleX, scaleY)
        this.duration = duration
    }
    animatorSet.start()
    animatorSet.addListener(object : Animator.AnimatorListener {
        override fun onAnimationStart(animation: Animator) {

        }

        override fun onAnimationEnd(animation: Animator) {
            onAnimationEnd?.invoke()
        }

        override fun onAnimationCancel(animation: Animator) {
        }

        override fun onAnimationRepeat(animation: Animator) {
        }

    })
    animatorSet.start()
}

fun zoomAnimation(
    anim: Boolean = true, view: View, zoomIn: Boolean, onAnimationEnd: (() -> Unit)? = null
) {
    try {
        if (anim.not()) {
            onAnimationEnd?.invoke()
            return
        } else {
            val startScale = if (zoomIn) 0.2f else 1.0f
            val endScale = if (zoomIn) 1.0f else 0f
            val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, startScale, endScale)
            val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, startScale, endScale)
            val animatorSet = AnimatorSet()
            animatorSet.playTogether(scaleX, scaleY)
            animatorSet.duration = if (zoomIn) 800 else 500
            animatorSet.start()
            animatorSet.doOnEnd {
                onAnimationEnd?.invoke()
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}


fun zoomAnimation(
    anim: Boolean = true, vararg views: View, zoomIn: Boolean, onAnimationEnd: (() -> Unit)? = null
) {
    if (anim.not()) {
        onAnimationEnd?.invoke()
        return
    } else {
        try {
            val startScale = if (zoomIn) 0.2f else 1.0f
            val endScale = if (zoomIn) 1.0f else 0f
            val objAnim = views.map { view ->
                val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, startScale, endScale)
                val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, startScale, endScale)
                AnimatorSet().apply {
                    playTogether(scaleX, scaleY)
                }
            }

            val animatorSet = AnimatorSet()
            animatorSet.playTogether(*objAnim.toTypedArray())
            animatorSet.duration = if (zoomIn) 800 else 500
            animatorSet.start()
            animatorSet.doOnEnd {
                onAnimationEnd?.invoke()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onAnimationEnd?.invoke()
        }
    }

}


fun startInfiniteRotationAnimation(
    view: View,
    doOnStart: (ObjectAnimator) -> Unit,
    doEnd: () -> Unit
) {
    try {
        val rotationAnimation = ObjectAnimator.ofFloat(view, "rotation", 0f, 360f).apply {
            duration = 4000
            repeatCount = 1
            interpolator = LinearInterpolator()
        }
        rotationAnimation.start()
        rotationAnimation.doOnStart {
            doOnStart.invoke(rotationAnimation)
        }
        rotationAnimation.doOnEnd {
            doEnd.invoke()
        }
        rotationAnimation.doOnCancel {
            doEnd.invoke()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun View.startInfiniteTranslateAnimation(
    xTranslation: Float = 360f,
    doOnEnd: (() -> Unit)? = null
): ObjectAnimator? {
    return try {
        val rotationAnimation =
            ObjectAnimator.ofFloat(this, View.TRANSLATION_X, 0f, xTranslation).apply {
                duration = 1000
                repeatCount = 10
                interpolator = AccelerateInterpolator()
            }
        rotationAnimation.start()
        rotationAnimation.doOnEnd {
            doOnEnd?.invoke()
        }
        rotationAnimation
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun startBlinkAnimation(view: View, onAnimation: ((ObjectAnimator) -> Unit)?) {
    val blinkAnimation = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f).apply {
        duration = 300
        repeatMode = ValueAnimator.RESTART
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateInterpolator()
    }
    blinkAnimation.start()
    onAnimation?.invoke(blinkAnimation)
}

fun <T> Fragment.collectLatestFlow(flow: Flow<T>, collectLatest: suspend (T) -> Unit) {
    lifecycleScope.launch(CoroutineExceptionHandler { _, throwable -> throwable.printStackTrace() }) {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collectLatest(collectLatest)
        }
    }
}

fun Fragment.launchCoroutineWithHandler(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    errorBlock: (() -> Unit)? = null,
    body: suspend (CoroutineScope) -> Unit
): Job {
    return lifecycleScope.launch(dispatcher + CoroutineExceptionHandler { _, throwable ->
        throwable.printStackTrace()
        errorBlock?.invoke()
    }) {
        body.invoke(this)
    }
}

fun View.viewToImage(): Bitmap? {
    try {
        val returnedBitmap = createBitmap(this.width, this.height)
        val canvas = Canvas(returnedBitmap)
        this.layout(this.left, this.top, this.right, this.bottom)
        this.draw(canvas)
        return returnedBitmap
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}

fun getColorFromRes(res: Int): Int {
    return ContextCompat.getColor(getBaseApplication(), res)
}

fun SeekBar.addListener(
    onProgressChanged: (progress: Int, fromUser: Boolean) -> Unit,
    onStartTrackingTouch: () -> Unit,
    onStopTrackingTouch: () -> Unit,
    min: Int = 0
) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            val progressResult = if (progress == 0) min else progress
            onProgressChanged(progressResult, fromUser)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            onStartTrackingTouch.invoke()
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            onStopTrackingTouch.invoke()
        }
    })
}

fun updateSizeWithRatio(
    ratioView: View,
    topView: View,
    bottomView: View,
    ratioWidth: Float,
    ratioHeight: Float,
    extraMarginX: Float = 0f,
    extraMarginY: Float = 0f
) {
    val parent = ratioView.parent as View

    val topAnchorY = topView.y + topView.height + extraMarginY
    val bottomAnchorY = bottomView.y - bottomView.height - extraMarginY

    val availableHeight = bottomAnchorY - topAnchorY

    val availableWidth = parent.width - extraMarginX.toInt()

    var newWidth = availableWidth
    var newHeight = newWidth * (ratioHeight / ratioWidth)

    if (newHeight > availableHeight) {
        newHeight = availableHeight
        newWidth = (newHeight * (ratioWidth / ratioHeight)).toInt()
    }

    val finalW = newWidth.toInt()
    val finalH = newHeight.toInt()

    val params = ratioView.layoutParams
    params.width = finalW
    params.height = finalH
    ratioView.layoutParams = params
}

/***
 * We only need WRITE_EXTERNAL_STORAGE permission for Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
 ***/

fun Fragment.checkWriteExternalStoragePermission(): Boolean {
    return PermissionX.isGranted(
        getBaseApplication(),
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
}

/***
 * We only need WRITE_EXTERNAL_STORAGE permission for Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
 ***/

fun Fragment.requestStoragePermission(requestSuccessRunnable: Runnable) {
    PermissionX.init(this).permissions(
        listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    ).onExplainRequestReason { scope, deniedList ->
        scope.showRequestReasonDialog(
            deniedList,
            getString(R.string.request_reason_write_storage_text),
            getString(R.string.request_again_text),
            getString(R.string.title_cancel)
        )
    }.onForwardToSettings { scope, deniedList ->
        scope.showForwardToSettingsDialog(
            deniedList,
            getString(R.string.grant_manually_in_settings_notification),
            getString(R.string.i_got_it_text), getString(R.string.title_cancel)
        )
    }.request { allGranted, _, _ ->
        if (allGranted) {
            requestSuccessRunnable.run()
        } else {
            Toast.makeText(
                getBaseApplication(),
                getString(R.string.permission_denied_text),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

fun <T> AppCompatActivity.collectLatestFlow(flow: Flow<T>, collectLatest: suspend (T) -> Unit) {
    lifecycleScope.launch(CoroutineExceptionHandler { _, throwable -> throwable.printStackTrace() }) {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collectLatest(collectLatest)
        }
    }
}

fun CoroutineScope.launchCoroutineWithHandler(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    errorBlock: (() -> Unit)? = null,
    body: suspend (CoroutineScope) -> Unit
): Job {
    return this.launch(dispatcher + CoroutineExceptionHandler { _, throwable ->
        throwable.printStackTrace()
        errorBlock?.invoke()
    }) {
        body.invoke(this)
    }
}

fun Fragment.callSafeFragment(action: () -> Unit) {
    try {
        if (isAdded && view != null) {
            action()
        }
    } catch (ex: Exception) {
        ex.printStackTrace()
    }
}


fun Fragment.checkPermissionStorage() {
    PermissionX.init(this)
        .permissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        .request { allGranted, _, deniedList ->
            if (allGranted) {
                Log.d("Permission", "WRITE_EXTERNAL_STORAGE granted")
            } else {
                Log.e("Permission", "Denied: $deniedList")
            }
        }
}

@SuppressLint("ClickableViewAccessibility")
fun View.setDraggableWithClick(onClick: () -> Unit) {
    var dX = 0f
    var dY = 0f
    var startX = 0f
    var startY = 0f
    var isDragging = false
    val parentView = this.parent as? ViewGroup
    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop // ngưỡng nhận diện drag

    this.setOnTouchListener { view, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dX = view.x - event.rawX
                dY = view.y - event.rawY
                startX = event.rawX
                startY = event.rawY
                isDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - startX
                val dy = event.rawY - startY
                if (dx * dx + dy * dy > touchSlop * touchSlop) {
                    isDragging = true
                }

                if (isDragging) {
                    val newX = event.rawX + dX
                    val newY = event.rawY + dY

                    parentView?.let { parent ->
                        val maxX = parent.width - view.width
                        val maxY = parent.height - view.height

                        val clampedX = newX.coerceIn(0f, maxX.toFloat())
                        val clampedY = newY.coerceIn(0f, maxY.toFloat())

                        view.animate()
                            .x(clampedX)
                            .y(clampedY)
                            .setDuration(0)
                            .start()
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    this.scaleAnimation {
                        if (onClick != null) {
                            onClick()
                        }
                    }
                    AppMusicPlayer.playFxMusic(R.raw.sound_click)
                }
            }
        }
        true
    }
}


