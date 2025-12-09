package io.github.takafu.webdroid

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream

class FloatingBubbleService : Service() {

    companion object {
        private var instance: FloatingBubbleService? = null
        private const val ACTION_RESTORE_OVERLAYS = "io.github.takafu.webdroid.RESTORE_OVERLAYS"
        private const val NOTIFICATION_CHANNEL_ID = "browser_restore"
        private const val RESTORE_NOTIFICATION_ID = 2

        // UserAgent presets
        const val UA_MODE_DEFAULT = "default"
        const val UA_MODE_GOOGLE_LOGIN = "google-login"
        const val UA_MODE_CUSTOM = "custom"

        // Desktop Chrome (default) - for full-featured web apps
        private const val UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        // Android Chrome without "wv" token - bypasses Google's WebView detection
        // Google checks for: 1) "wv" in UA string, 2) window.chrome object existence
        // This UA combined with window.chrome injection allows Google login in WebView
        private const val UA_GOOGLE_LOGIN = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        // Current UA mode
        private var currentUaMode = UA_MODE_DEFAULT
        private var customUa: String? = null

        // Close window and return to bubble mode
        fun minimizeWindow() {
            instance?.minimizeToToBubble()
        }

        // Get current UA mode
        fun getUaMode(): String = currentUaMode

        // Get current UA string
        fun getCurrentUa(): String = when (currentUaMode) {
            UA_MODE_GOOGLE_LOGIN -> UA_GOOGLE_LOGIN
            UA_MODE_CUSTOM -> customUa ?: UA_DESKTOP
            else -> UA_DESKTOP
        }

        // Set UA mode
        fun setUaMode(mode: String, custom: String? = null): Boolean {
            return when (mode) {
                UA_MODE_DEFAULT, UA_MODE_GOOGLE_LOGIN -> {
                    currentUaMode = mode
                    customUa = null
                    instance?.applyCurrentUa()
                    true
                }
                UA_MODE_CUSTOM -> {
                    if (custom != null) {
                        currentUaMode = mode
                        customUa = custom
                        instance?.applyCurrentUa()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        // Check if google-login mode (for conditional JS injection)
        fun isGoogleLoginMode(): Boolean = currentUaMode == UA_MODE_GOOGLE_LOGIN
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var trashView: View? = null
    private var trashParams: WindowManager.LayoutParams? = null
    private var stashView: View? = null  // Top zone to hide browser
    private var stashParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var floatingWindow: View? = null
    private var floatingWindowParams: WindowManager.LayoutParams? = null
    private var hiddenWebViewContainer: FrameLayout? = null  // Holds WebView in bubble state
    private var isExpanded = false
    private var isAnimating = false  // Animation in progress flag

    // Drag variables (member vars for closure capture)
    private var windowStartX = 0
    private var windowStartY = 0

    // Saved window position/size for restoration
    private var savedWindowX: Float? = null
    private var savedWindowY: Float? = null
    private var savedWindowWidth: Int? = null
    private var savedWindowHeight: Int? = null

    // Auth button (shown only when login form detected)
    private var authButton: View? = null
    private var hasLoginForm = false  // Login form detected flag

    // Custom velocity tracking (more stable than VelocityTracker)
    private data class TouchSample(val x: Float, val y: Float, val time: Long)
    private val touchHistory = mutableListOf<TouchSample>()
    private val TOUCH_HISTORY_SIZE = 10  // Keep last 10 samples
    private val VELOCITY_TIME_WINDOW = 100L  // Use samples from last 100ms

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Initialize WebView
        if (BrowserActivity.webView == null) {
            BrowserActivity.webView = createWebView()
        }

        createBubble()
        createHiddenWebViewContainer()
    }

    private fun createHiddenWebViewContainer() {
        // Hidden container to hold WebView even in bubble state
        val container = FrameLayout(this).apply {
            alpha = 0.02f  // Nearly transparent (2%) - needed to maintain rendering
        }

        // Add WebView
        BrowserActivity.webView?.let { webView ->
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            container.addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        val params = WindowManager.LayoutParams(
            1080,  // Large enough for screenshots
            1920,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,  // Not touchable
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        hiddenWebViewContainer = container
        windowManager.addView(container, params)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("FloatingBubble", "onStartCommand: action=${intent?.action}")
        // Handle restore action from notification
        if (intent?.action == ACTION_RESTORE_OVERLAYS) {
            android.util.Log.d("FloatingBubble", "Restoring overlays from notification")
            restoreOverlays()
            // Dismiss the notification
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(RESTORE_NOTIFICATION_ID)
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubble() {
        // Create bubble with gradient, shadow, and animation
        // Icon: Feather Icons "globe" (https://feathericons.com/)
        val bubble = ImageView(this).apply {
            setImageResource(R.drawable.ic_globe)
            scaleType = ImageView.ScaleType.CENTER_INSIDE

            // Padding for proper icon sizing
            val padding = 28
            setPadding(padding, padding, padding, padding)

            // Gradient background (purple to blue)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(
                    Color.parseColor("#667eea"), // Light purple
                    Color.parseColor("#764ba2")  // Deep purple
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }

            // Add elevation (shadow)
            elevation = 16f

            // Start pulse animation
            startPulseAnimation()
        }

        val params = WindowManager.LayoutParams(
            130,
            130,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 50
            y = 200
        }
        bubbleParams = params  // Save to member variable

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var velocityTracker: VelocityTracker? = null
        var isDragging = false

        // Create trash (same style as bubble)
        // Icon: Feather Icons "trash-2" (https://feathericons.com/)
        val trashSize = 130
        val trash = ImageView(this).apply {
            setImageResource(R.drawable.ic_trash)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val padding = 28
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(
                    Color.parseColor("#5a5a5a"),  // Gray
                    Color.parseColor("#3a3a3a")   // Dark gray
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
            elevation = 16f
            alpha = 0f  // Initially hidden
            scaleX = 0.5f
            scaleY = 0.5f
        }

        val trashLayoutParams = WindowManager.LayoutParams(
            trashSize,
            trashSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        trashView = trash
        trashParams = trashLayoutParams
        windowManager.addView(trash, trashLayoutParams)

        // Create stash zone at top (to hide browser temporarily)
        // Icon: Feather Icons "eye-off" (hide browser)
        val stashSize = 130
        val stash = ImageView(this).apply {
            setImageResource(R.drawable.ic_eye_off)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val padding = 28
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(
                    Color.parseColor("#667eea"),  // Purple (same as header)
                    Color.parseColor("#764ba2")
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
            elevation = 16f
            alpha = 0f  // Initially hidden
            scaleX = 0.5f
            scaleY = 0.5f
        }

        val stashLayoutParams = WindowManager.LayoutParams(
            stashSize,
            stashSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        stashView = stash
        stashParams = stashLayoutParams
        windowManager.addView(stash, stashLayoutParams)

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        bubble.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false

                    // Initialize VelocityTracker
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)

                    // Initialize custom touch history
                    touchHistory.clear()
                    touchHistory.add(TouchSample(event.rawX, event.rawY, System.currentTimeMillis()))

                    // Scale down animation on tap
                    v.animate()
                        .scaleX(0.85f)
                        .scaleY(0.85f)
                        .setDuration(100)
                        .start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)

                    // Add to custom touch history
                    touchHistory.add(TouchSample(event.rawX, event.rawY, System.currentTimeMillis()))
                    if (touchHistory.size > TOUCH_HISTORY_SIZE) {
                        touchHistory.removeAt(0)
                    }

                    val deltaX = Math.abs(event.rawX - initialTouchX)
                    val deltaY = Math.abs(event.rawY - initialTouchY)

                    // Detect drag start
                    if (!isDragging && (deltaX > 10 || deltaY > 10)) {
                        isDragging = true
                        // Show trash and stash zones
                        trash.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(200)
                            .start()
                        stash.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(200)
                            .start()
                    }

                    params.x = initialX + (initialTouchX - event.rawX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(v, params)

                    // Check distance to trash
                    if (isDragging) {
                        val bubbleCenterX = screenWidth - params.x - 65  // Bubble center X
                        val bubbleCenterY = params.y + 65  // Bubble center Y
                        val trashCenterX = screenWidth / 2
                        val trashCenterY = screenHeight - 100 - 65  // Trash center Y

                        val distance = Math.sqrt(
                            Math.pow((bubbleCenterX - trashCenterX).toDouble(), 2.0) +
                            Math.pow((bubbleCenterY - trashCenterY).toDouble(), 2.0)
                        )

                        // Scale up trash when near (instant switch)
                        val isNearTrash = distance < 200
                        if (isNearTrash && trash.scaleX < 1.2f) {
                            trash.animate()
                                .scaleX(1.3f)
                                .scaleY(1.3f)
                                .setDuration(50)
                                .start()
                        } else if (!isNearTrash && trash.scaleX > 1.1f) {
                            trash.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(50)
                                .start()
                        }

                        // Check distance to stash zone (top)
                        val stashCenterX = screenWidth / 2
                        val stashCenterY = 100 + 65  // Stash center Y (top)
                        val distanceToStash = Math.sqrt(
                            Math.pow((bubbleCenterX - stashCenterX).toDouble(), 2.0) +
                            Math.pow((bubbleCenterY - stashCenterY).toDouble(), 2.0)
                        )

                        // Scale up stash when near
                        val isNearStash = distanceToStash < 200
                        if (isNearStash && stash.scaleX < 1.2f) {
                            stash.animate()
                                .scaleX(1.3f)
                                .scaleY(1.3f)
                                .setDuration(50)
                                .start()
                        } else if (!isNearStash && stash.scaleX > 1.1f) {
                            stash.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(50)
                                .start()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Calculate velocity from custom touch history
                    val currentTime = System.currentTimeMillis()
                    val recentSamples = touchHistory.filter {
                        currentTime - it.time <= VELOCITY_TIME_WINDOW
                    }

                    val (velocityX, velocityY) = if (recentSamples.size >= 2) {
                        val oldest = recentSamples.first()
                        val newest = recentSamples.last()
                        val dt = (newest.time - oldest.time).coerceAtLeast(1L) / 1000f
                        val vx = (newest.x - oldest.x) / dt
                        val vy = (newest.y - oldest.y) / dt
                        Pair(vx, vy)
                    } else {
                        Pair(0f, 0f)
                    }

                    velocityTracker?.recycle()
                    velocityTracker = null
                    touchHistory.clear()

                    // Restore original size
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .start()

                    // Check if dropped on trash or stash
                    val bubbleCenterX = screenWidth - params.x - 65
                    val bubbleCenterY = params.y + 65
                    val trashCenterX = screenWidth / 2
                    val trashCenterY = screenHeight - 100 - 65
                    val stashCenterX = screenWidth / 2
                    val stashCenterY = 100 + 65

                    val distanceToTrash = Math.sqrt(
                        Math.pow((bubbleCenterX - trashCenterX).toDouble(), 2.0) +
                        Math.pow((bubbleCenterY - trashCenterY).toDouble(), 2.0)
                    )
                    val distanceToStash = Math.sqrt(
                        Math.pow((bubbleCenterX - stashCenterX).toDouble(), 2.0) +
                        Math.pow((bubbleCenterY - stashCenterY).toDouble(), 2.0)
                    )

                    if (isDragging && distanceToTrash < 200) {
                        // Animate into trash
                        animateToTrash(v, params, trash, stash, screenWidth, screenHeight)
                    } else if (isDragging && distanceToStash < 200) {
                        // Stash browser - hide and show notification
                        animateToStash(v, params, trash, stash, screenWidth)
                    } else if (!isDragging) {
                        // Tap - open window
                        v.animate()
                            .scaleX(1.1f)
                            .scaleY(1.1f)
                            .setDuration(100)
                            .withEndAction {
                                v.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(100)
                                    .start()
                            }
                            .start()
                        openFloatingWindow()
                    } else {
                        // Fling animation (keep trash/stash visible)
                        applyFlingAnimation(v, params, trash, stash, velocityX, velocityY, screenWidth, screenHeight)
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = bubble
        windowManager.addView(bubble, params)
    }

    // Animation when sucked into trash
    private fun animateToTrash(
        bubble: View,
        params: WindowManager.LayoutParams,
        trash: View,
        stash: View,
        screenWidth: Int,
        screenHeight: Int
    ) {
        // Hide stash zone
        stash.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(150).start()
        // Trash center coordinates (bottom center of screen)
        val trashCenterX = screenWidth / 2
        val trashCenterY = screenHeight - 100 - 65

        // Current bubble center coordinates
        val startX = screenWidth - params.x - 65
        val startY = params.y + 65

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                // Move bubble toward trash center
                val newCenterX = startX + (trashCenterX - startX) * t
                val newCenterY = startY + (trashCenterY - startY) * t
                params.x = (screenWidth - newCenterX - 65).toInt()
                params.y = (newCenterY - 65).toInt()
                bubble.scaleX = 1f - t * 0.5f
                bubble.scaleY = 1f - t * 0.5f
                try {
                    windowManager.updateViewLayout(bubble, params)
                } catch (e: Exception) {}
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(a: android.animation.Animator) {}
                override fun onAnimationEnd(a: android.animation.Animator) {
                    // Trash reacts and disappears
                    trash.animate()
                        .scaleX(1.3f)
                        .scaleY(1.3f)
                        .setDuration(100)
                        .withEndAction {
                            trash.animate()
                                .scaleX(0f)
                                .scaleY(0f)
                                .alpha(0f)
                                .setDuration(150)
                                .start()
                        }
                        .start()

                    bubble.animate()
                        .alpha(0f)
                        .scaleX(0f)
                        .scaleY(0f)
                        .setDuration(100)
                        .withEndAction { stopSelf() }
                        .start()
                }
                override fun onAnimationCancel(a: android.animation.Animator) {}
                override fun onAnimationRepeat(a: android.animation.Animator) {}
            })
            start()
        }
    }

    // Animation when stashed (hide browser)
    private fun animateToStash(
        bubble: View,
        params: WindowManager.LayoutParams,
        trash: View,
        stash: View,
        screenWidth: Int
    ) {
        // Hide trash zone
        trash.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(150).start()

        // Stash center coordinates (top center of screen)
        val stashCenterX = screenWidth / 2
        val stashCenterY = 100 + 65

        // Current bubble center coordinates
        val startX = screenWidth - params.x - 65
        val startY = params.y + 65

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                // Move bubble toward stash center
                val newCenterX = startX + (stashCenterX - startX) * t
                val newCenterY = startY + (stashCenterY - startY) * t
                params.x = (screenWidth - newCenterX - 65).toInt()
                params.y = (newCenterY - 65).toInt()
                bubble.scaleX = 1f - t * 0.5f
                bubble.scaleY = 1f - t * 0.5f
                try {
                    windowManager.updateViewLayout(bubble, params)
                } catch (e: Exception) {}
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(a: android.animation.Animator) {}
                override fun onAnimationEnd(a: android.animation.Animator) {
                    // Stash reacts and disappears
                    stash.animate()
                        .scaleX(1.3f)
                        .scaleY(1.3f)
                        .setDuration(100)
                        .withEndAction {
                            stash.animate()
                                .scaleX(0f)
                                .scaleY(0f)
                                .alpha(0f)
                                .setDuration(150)
                                .start()
                        }
                        .start()

                    bubble.animate()
                        .alpha(0f)
                        .scaleX(0f)
                        .scaleY(0f)
                        .setDuration(100)
                        .withEndAction {
                            // Hide all overlays and show notification
                            stashOverlays()
                        }
                        .start()
                }
                override fun onAnimationCancel(a: android.animation.Animator) {}
                override fun onAnimationRepeat(a: android.animation.Animator) {}
            })
            start()
        }
    }

    // Hide all overlays and show restore notification
    private fun stashOverlays() {
        try { floatingWindow?.let { windowManager.removeView(it) } } catch (e: Exception) {}
        try { bubbleView?.let { windowManager.removeView(it) } } catch (e: Exception) {}
        try { trashView?.let { windowManager.removeView(it) } } catch (e: Exception) {}
        try { stashView?.let { windowManager.removeView(it) } } catch (e: Exception) {}
        try { hiddenWebViewContainer?.let { windowManager.removeView(it) } } catch (e: Exception) {}
        showRestoreNotification()
    }

    // Fling animation with trash detection
    private fun applyFlingAnimation(
        view: View,
        params: WindowManager.LayoutParams,
        trash: View,
        stash: View,
        velocityX: Float,
        velocityY: Float,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val friction = 0.92f
        var vx = -velocityX / 30f
        var vy = velocityY / 30f
        val trashCenterX = screenWidth / 2
        val trashCenterY = screenHeight - 100 - 65
        val stashCenterX = screenWidth / 2
        val stashCenterY = 100 + 65

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                vx *= friction
                vy *= friction

                params.x = (params.x + vx).toInt().coerceIn(0, screenWidth - 130)
                params.y = (params.y + vy).toInt().coerceIn(0, screenHeight - 130)

                val bubbleCenterX = screenWidth - params.x - 65
                val bubbleCenterY = params.y + 65

                // Check distance to trash
                val distanceToTrash = Math.sqrt(
                    Math.pow((bubbleCenterX - trashCenterX).toDouble(), 2.0) +
                    Math.pow((bubbleCenterY - trashCenterY).toDouble(), 2.0)
                )

                // Check distance to stash
                val distanceToStash = Math.sqrt(
                    Math.pow((bubbleCenterX - stashCenterX).toDouble(), 2.0) +
                    Math.pow((bubbleCenterY - stashCenterY).toDouble(), 2.0)
                )

                // Scale trash when near
                val isNearTrash = distanceToTrash < 200
                if (isNearTrash && trash.scaleX < 1.2f) {
                    trash.animate().scaleX(1.3f).scaleY(1.3f).setDuration(50).start()
                } else if (!isNearTrash && trash.scaleX > 1.1f) {
                    trash.animate().scaleX(1f).scaleY(1f).setDuration(50).start()
                }

                // Scale stash when near
                val isNearStash = distanceToStash < 200
                if (isNearStash && stash.scaleX < 1.2f) {
                    stash.animate().scaleX(1.3f).scaleY(1.3f).setDuration(50).start()
                } else if (!isNearStash && stash.scaleX > 1.1f) {
                    stash.animate().scaleX(1f).scaleY(1f).setDuration(50).start()
                }

                // Suck into trash when close enough
                if (distanceToTrash < 120) {
                    cancel()
                    animateToTrash(view, params, trash, stash, screenWidth, screenHeight)
                    return@addUpdateListener
                }

                // Suck into stash when close enough
                if (distanceToStash < 120) {
                    cancel()
                    animateToStash(view, params, trash, stash, screenWidth)
                    return@addUpdateListener
                }

                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) {
                    cancel()
                }
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(a: android.animation.Animator) {}
                override fun onAnimationEnd(a: android.animation.Animator) {
                    // Hide trash and stash after fling ends
                    trash.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(200).start()
                    stash.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(200).start()
                }
                override fun onAnimationCancel(a: android.animation.Animator) {}
                override fun onAnimationRepeat(a: android.animation.Animator) {}
            })
        }
        animator.start()
    }

    // Pulse animation (gives a lively feel)
    private fun View.startPulseAnimation() {
        val scaleUp = ObjectAnimator.ofFloat(this, "scaleX", 1f, 1.08f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleUpY = ObjectAnimator.ofFloat(this, "scaleY", 1f, 1.08f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
        AnimatorSet().apply {
            playTogether(scaleUp, scaleUpY)
            start()
        }
    }

    private fun openFloatingWindow() {
        if (floatingWindow != null) return

        // Hide bubble immediately (for seamless transformation)
        bubbleView?.visibility = View.INVISIBLE

        // Wrapper (full screen size, clipping disabled)
        val wrapper = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Rounded corners for the whole container
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 24f
            }
            elevation = 24f
            clipToOutline = true  // Clip children to rounded outline
        }

        // Header with minimize button - gradient background
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                colors = intArrayOf(
                    Color.parseColor("#667eea"),
                    Color.parseColor("#764ba2")
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadii = floatArrayOf(24f, 24f, 24f, 24f, 0f, 0f, 0f, 0f)
            }
            val padding = 16
            setPadding(padding, padding, padding, padding)
        }

        val title = TextView(this).apply {
            text = "Browser Automation"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        // Auth button (password manager) - shown only when login form detected
        // Icon: Feather Icons "lock" (https://feathericons.com/)
        val authBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_lock)
            val btnSize = 64
            val padding = 16
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginEnd = 8
            }
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FFFFFF"))
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            // Initial visibility based on hasLoginForm flag
            visibility = if (hasLoginForm) View.VISIBLE else View.GONE
            setOnClickListener {
                showAuthDialog()
            }
        }
        authButton = authBtn  // Save to member variable

        // Minimize button
        // Icon: Feather Icons "minus" (https://feathericons.com/)
        val minimizeButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_minus)
            val btnSize = 64
            val padding = 16
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FFFFFF"))
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener {
                // Ignore during animation
                if (isAnimating) return@setOnClickListener

                val params = floatingWindowParams ?: return@setOnClickListener
                val wrapper = floatingWindow ?: return@setOnClickListener

                isAnimating = true

                // Close animation - shrink toward bubble position
                val bubbleParams = bubbleView?.layoutParams as? WindowManager.LayoutParams
                val bubbleSize = 130f

                // Current container size
                val currentWidth = container.width.toFloat()
                val currentHeight = container.height.toFloat()

                // Save window position/size from windowParams
                savedWindowX = params.x.toFloat()
                savedWindowY = params.y.toFloat()
                savedWindowWidth = currentWidth.toInt()
                savedWindowHeight = currentHeight.toInt()

                if (currentWidth <= 0 || currentHeight <= 0) {
                    isAnimating = false
                    closeFloatingWindow()
                    return@setOnClickListener
                }

                // Expand wrapper to full screen (for animation)
                // 1. Set container translation (maintain visual position)
                container.translationX = params.x.toFloat()
                container.translationY = params.y.toFloat()

                // 2. Make wrapper full screen
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels
                params.x = 0
                params.y = 0
                params.width = screenWidth
                params.height = screenHeight
                windowManager.updateViewLayout(wrapper, params)

                val scaleXEnd = bubbleSize / currentWidth
                val scaleYEnd = bubbleSize / currentHeight

                // Calculate bubble position
                val bubbleCenterX = screenWidth - (bubbleParams?.x ?: 50) - bubbleSize / 2f
                val bubbleCenterY = (bubbleParams?.y ?: 200) + bubbleSize / 2f

                // Translation to move to bubble center (calculate top-left from center pivot)
                val targetTranslationX = bubbleCenterX - currentWidth / 2f
                val targetTranslationY = bubbleCenterY - currentHeight / 2f

                // Set pivot to center
                container.pivotX = currentWidth / 2f
                container.pivotY = currentHeight / 2f

                container.animate()
                    .scaleX(scaleXEnd)
                    .scaleY(scaleYEnd)
                    .translationX(targetTranslationX)
                    .translationY(targetTranslationY)
                    .alpha(0f)
                    .setDuration(250)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .setListener(null)  // Clear open animation listener
                    .withEndAction {
                        isAnimating = false
                        closeFloatingWindow()
                    }
                    .start()
            }
        }

        header.addView(title)
        header.addView(authBtn)
        header.addView(minimizeButton)
        container.addView(header)

        // Title bar drag handling
        var dragStartX = 0f
        var dragStartY = 0f

        header.setOnTouchListener { _, event ->
            // Disable drag during animation
            if (isAnimating) return@setOnTouchListener false

            val params = floatingWindowParams ?: return@setOnTouchListener false
            val wrapper = floatingWindow ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    windowStartX = params.x
                    windowStartY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = windowStartX + (event.rawX - dragStartX).toInt()
                    params.y = windowStartY + (event.rawY - dragStartY).toInt()
                    windowManager.updateViewLayout(wrapper, params)
                    true
                }
                else -> false
            }
        }

        // WebView container with inner padding
        val webViewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.parseColor("#f8f9fa"))
        }

        // Create or reuse WebView
        if (BrowserActivity.webView == null) {
            BrowserActivity.webView = createWebView()
        }

        BrowserActivity.webView?.let { webView ->
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webViewContainer.addView(webView)
        }

        container.addView(webViewContainer)

        // Resize handle (bottom right) - same gradient as header
        val resizeHandle = View(this).apply {
            background = GradientDrawable().apply {
                colors = intArrayOf(
                    Color.parseColor("#667eea"),
                    Color.parseColor("#764ba2")
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                // Bottom right corner radius (match container)
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 24f, 24f, 0f, 0f)
            }
            layoutParams = LinearLayout.LayoutParams(72, 40).apply {
                gravity = Gravity.END
            }
        }
        container.addView(resizeHandle)

        // Get bubble position
        val bubbleParams = bubbleView?.layoutParams as? WindowManager.LayoutParams
        val bubbleSize = 130

        // Screen size
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        // Window size (restore if saved, otherwise default)
        val finalWidth = savedWindowWidth ?: (screenWidth * 0.95).toInt()
        val finalHeight = savedWindowHeight ?: (screenHeight * 0.45).toInt()

        // Bubble center coordinates
        val bubbleCenterX = screenWidth - (bubbleParams?.x ?: 50) - bubbleSize / 2
        val bubbleCenterY = (bubbleParams?.y ?: 200) + bubbleSize / 2

        // Container position (restore if saved, otherwise top center)
        val margin = (screenWidth * 0.025).toInt()  // 2.5% margin on sides
        val initialX = savedWindowX ?: margin.toFloat()
        val initialY = savedWindowY ?: margin.toFloat()

        // Set container size (position managed via translation)
        container.layoutParams = FrameLayout.LayoutParams(finalWidth, finalHeight)

        // Add container to wrapper
        wrapper.addView(container)

        // Add wrapper to WindowManager at full screen size
        val windowParams = WindowManager.LayoutParams(
            screenWidth,
            screenHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            windowAnimations = 0

            // Set PRIVATE_FLAG_NO_MOVE_ANIMATION via reflection
            try {
                val privateFlagsField = WindowManager.LayoutParams::class.java.getField("privateFlags")
                val noAnimField = WindowManager.LayoutParams::class.java.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
                val currentFlags = privateFlagsField.getInt(this)
                val noAnimFlag = noAnimField.getInt(this)
                privateFlagsField.setInt(this, currentFlags or noAnimFlag)
            } catch (e: Exception) {
                android.util.Log.w("FloatingBubble", "Failed to set PRIVATE_FLAG_NO_MOVE_ANIMATION: ${e.message}")
            }
        }

        floatingWindow = wrapper
        floatingWindowParams = windowParams
        windowManager.addView(wrapper, windowParams)
        isExpanded = true

        // Re-check login form detection (in case page was already loaded)
        detectLoginForm(BrowserActivity.webView)

        // Release focus on tap outside window
        wrapper.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                // Release focus (add FLAG_NOT_FOCUSABLE)
                windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                windowManager.updateViewLayout(wrapper, windowParams)
            }
            false
        }

        // Get focus on WebView tap
        BrowserActivity.webView?.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Get focus (remove FLAG_NOT_FOCUSABLE)
                val hasFocusFlag = (windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
                if (hasFocusFlag) {
                    windowParams.flags = windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    windowManager.updateViewLayout(wrapper, windowParams)
                }
                // Request focus for WebView
                view.requestFocus()
            }
            // Return false to continue WebView touch handling
            false
        }

        // Local function: shrink wrapper to UI size (when open animation ends)
        val shrinkWrapperToUI = {
            val winX = container.translationX.toInt()
            val winY = container.translationY.toInt()
            val winW = container.width
            val winH = container.height

            // Reset translation first, then update wrapper
            container.translationX = 0f
            container.translationY = 0f
            windowParams.x = winX
            windowParams.y = winY
            windowParams.width = winW
            windowParams.height = winH
            windowManager.updateViewLayout(wrapper, windowParams)

            // Default 85% opacity
            container.alpha = 0.85f
        }

        // Resize handle drag handling
        var resizeStartX = 0f
        var resizeStartY = 0f
        var startWidth = 0
        var startHeight = 0

        resizeHandle.setOnTouchListener { _, event ->
            // Disable resize during animation
            if (isAnimating) return@setOnTouchListener false

            val params = floatingWindowParams ?: return@setOnTouchListener false
            val wrapper = floatingWindow ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartX = event.rawX
                    resizeStartY = event.rawY
                    startWidth = container.width
                    startHeight = container.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - resizeStartX
                    val deltaY = event.rawY - resizeStartY

                    // Set minimum size
                    val minWidth = 300
                    val minHeight = 400

                    val newWidth = maxOf(minWidth, (startWidth + deltaX).toInt())
                    val newHeight = maxOf(minHeight, (startHeight + deltaY).toInt())

                    // Update both container and wrapper size
                    container.layoutParams = FrameLayout.LayoutParams(newWidth, newHeight)
                    params.width = newWidth
                    params.height = newHeight
                    windowManager.updateViewLayout(wrapper, params)
                    true
                }
                else -> false
            }
        }

        // WebView container initially transparent
        webViewContainer.alpha = 0f

        // Start from bubble size (circle -> window)
        val scaleXStart = bubbleSize.toFloat() / finalWidth
        val scaleYStart = bubbleSize.toFloat() / finalHeight

        // Container final center coordinates (translation based)
        val finalCenterX = initialX + finalWidth / 2f
        val finalCenterY = initialY + finalHeight / 2f

        // Set pivot to container center
        container.pivotX = finalWidth / 2f
        container.pivotY = finalHeight / 2f

        // Start position: align with bubble center
        val startTranslationX = bubbleCenterX - finalWidth / 2f
        val startTranslationY = bubbleCenterY - finalHeight / 2f

        // Cancel previous ViewPropertyAnimator (close animation listener may remain)
        container.animate().cancel()
        container.animate().setListener(null)

        container.alpha = 1f
        container.scaleX = scaleXStart
        container.scaleY = scaleYStart
        container.translationX = startTranslationX
        container.translationY = startTranslationY

        // Set animation in progress flag
        isAnimating = true

        // Start animation after layout is finalized
        wrapper.post {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 350
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animator ->
                    val fraction = animator.animatedValue as Float
                    container.translationX = startTranslationX + (initialX - startTranslationX) * fraction
                    container.translationY = startTranslationY + (initialY - startTranslationY) * fraction
                    container.scaleX = scaleXStart + (1f - scaleXStart) * fraction
                    container.scaleY = scaleYStart + (1f - scaleYStart) * fraction
                }
                addListener(object : android.animation.Animator.AnimatorListener {
                    override fun onAnimationStart(animation: android.animation.Animator) {}
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        shrinkWrapperToUI()
                        isAnimating = false
                    }
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        isAnimating = false
                    }
                    override fun onAnimationRepeat(animation: android.animation.Animator) {}
                })
                start()
            }
        }

        // Fade in WebView from midpoint (after 175ms)
        webViewContainer.postDelayed({
            webViewContainer.animate()
                .alpha(1f)
                .setDuration(175)
                .start()
        }, 175)
    }

    private fun createWebView(): WebView {
        return WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true

                // Desktop mode settings
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false

                // Force desktop layout with large viewport
                layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.NORMAL

                // Use current UA mode (default: desktop Chrome)
                userAgentString = getCurrentUa()

                // Other settings
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true

                // Allow mixed content
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                // Make it more like a real browser
                setSupportMultipleWindows(false)
                setGeolocationEnabled(false)

                // Cache settings
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

                // Save form data (may be needed for password manager integration)
                @Suppress("DEPRECATION")
                setSaveFormData(true)
                @Suppress("DEPRECATION")
                setSavePassword(true)
            }

            // Enable debugging
            WebView.setWebContentsDebuggingEnabled(true)

            // Focus settings
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus(View.FOCUS_DOWN)

            // WebViewClient setup
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    AutomationService.onPageEvent("page_started", url ?: "")

                    // Hide auth button when page starts loading
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        authButton?.visibility = View.GONE
                    }

                    // Inject JavaScript to avoid WebView detection (only in google-login mode)
                    // Google checks for: 1) navigator.webdriver, 2) window.chrome object
                    if (isGoogleLoginMode()) {
                        view?.evaluateJavascript("""
                            (function() {
                                // Hide webdriver property
                                Object.defineProperty(navigator, 'webdriver', {
                                    get: () => undefined
                                });

                                // Create fake window.chrome object (real Chrome has this)
                                if (!window.chrome) {
                                    window.chrome = {
                                        runtime: {
                                            connect: function() {},
                                            sendMessage: function() {}
                                        },
                                        loadTimes: function() {},
                                        csi: function() {},
                                        app: {}
                                    };
                                }
                            })();
                        """.trimIndent(), null)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    AutomationService.onPageEvent("page_finished", url ?: "")

                    // Detect login form and toggle auth button visibility
                    detectLoginForm(view)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    AutomationService.onPageEvent("error", error?.description?.toString() ?: "Unknown error")
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    android.util.Log.d("FloatingBubble", "doUpdateVisitedHistory: url=$url, isReload=$isReload")
                    // Re-detect login form on URL change (for SPA-style navigation)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        android.util.Log.d("FloatingBubble", "doUpdateVisitedHistory: detecting login form")
                        detectLoginForm(view)
                    }, 500)  // Small delay for page to render
                }
            }

            // WebChromeClient setup
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                    message?.let {
                        AutomationService.onConsoleMessage(
                            "${it.message()} (${it.sourceId()}:${it.lineNumber()})"
                        )
                    }
                    return true
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    AutomationService.onProgressChanged(newProgress)
                }
            }

            loadUrl("about:blank")
        }
    }

    private fun closeFloatingWindow() {
        // Move WebView back to hidden container
        BrowserActivity.webView?.let { webView ->
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            hiddenWebViewContainer?.addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        // Remove window (called after animation completes)
        floatingWindow?.let { window ->
            // Set size to 0 before removing to prevent flash
            val params = window.layoutParams as? WindowManager.LayoutParams
            params?.let {
                it.width = 0
                it.height = 0
                windowManager.updateViewLayout(window, it)
            }

            // Remove on next frame
            window.post {
                windowManager.removeView(window)
                floatingWindow = null
            }
        }
        isExpanded = false

        // Show bubble again
        bubbleView?.visibility = View.VISIBLE
    }

    // Close window and return to bubble mode (run on main thread)
    private fun minimizeToToBubble() {
        if (!isExpanded) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            closeFloatingWindow()
        }
    }

    // Show auth dialog
    private fun showAuthDialog() {
        // Get current URL
        val currentUrl = BrowserActivity.webView?.url ?: ""

        // Save window position/size before minimizing
        floatingWindowParams?.let { params ->
            savedWindowX = params.x.toFloat()
            savedWindowY = params.y.toFloat()
        }
        floatingWindow?.let { window ->
            val container = (window as? FrameLayout)?.getChildAt(0)
            container?.let {
                savedWindowWidth = it.width
                savedWindowHeight = it.height
            }
        }

        // Temporarily remove all overlays from WindowManager (so Bitwarden can receive touches)
        try {
            floatingWindow?.let {
                windowManager.removeView(it)
                android.util.Log.d("FloatingBubble", "Removed floatingWindow")
            }
        } catch (e: Exception) {
            android.util.Log.e("FloatingBubble", "Failed to remove floatingWindow: ${e.message}")
        }
        try {
            bubbleView?.let {
                windowManager.removeView(it)
                android.util.Log.d("FloatingBubble", "Removed bubbleView")
            }
        } catch (e: Exception) {
            android.util.Log.e("FloatingBubble", "Failed to remove bubbleView: ${e.message}")
        }
        try {
            trashView?.let {
                windowManager.removeView(it)
                android.util.Log.d("FloatingBubble", "Removed trashView")
            }
        } catch (e: Exception) {
            android.util.Log.e("FloatingBubble", "Failed to remove trashView: ${e.message}")
        }
        try {
            stashView?.let {
                windowManager.removeView(it)
                android.util.Log.d("FloatingBubble", "Removed stashView")
            }
        } catch (e: Exception) {
            android.util.Log.e("FloatingBubble", "Failed to remove stashView: ${e.message}")
        }
        try {
            hiddenWebViewContainer?.let {
                windowManager.removeView(it)
                android.util.Log.d("FloatingBubble", "Removed hiddenWebViewContainer")
            }
        } catch (e: Exception) {
            android.util.Log.e("FloatingBubble", "Failed to remove hiddenWebViewContainer: ${e.message}")
        }

        // Set callbacks
        AuthDialogActivity.onCredentialsEntered = { username, password ->
            // Inject credentials into WebView
            injectCredentials(username, password)
        }

        // Show notification to restore overlays when dialog closes
        AuthDialogActivity.onDialogClosed = {
            showRestoreNotification()
        }

        // Launch AuthDialogActivity (pass URL)
        val intent = android.content.Intent(this, AuthDialogActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("url", currentUrl)
        }
        startActivity(intent)
    }

    // Show floating window again
    private fun reopenFloatingWindow() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            bubbleView?.visibility = View.INVISIBLE
            floatingWindow?.visibility = View.VISIBLE
        }
    }

    // Re-add overlays (after auth dialog closes)
    private fun restoreOverlays() {
        android.util.Log.d("FloatingBubble", "restoreOverlays called")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.util.Log.d("FloatingBubble", "restoreOverlays: running on main thread")
            // Re-add hiddenWebViewContainer
            hiddenWebViewContainer?.let { container ->
                android.util.Log.d("FloatingBubble", "restoreOverlays: adding hiddenWebViewContainer")
                val params = WindowManager.LayoutParams(
                    1080,
                    1920,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                }
                try {
                    windowManager.addView(container, params)
                    android.util.Log.d("FloatingBubble", "restoreOverlays: hiddenWebViewContainer added")
                } catch (e: Exception) {
                    android.util.Log.e("FloatingBubble", "restoreOverlays: failed to add hiddenWebViewContainer: ${e.message}")
                }
            } ?: android.util.Log.w("FloatingBubble", "restoreOverlays: hiddenWebViewContainer is null")

            // Re-add trashView
            trashView?.let { trash ->
                trashParams?.let { params ->
                    try {
                        windowManager.addView(trash, params)
                    } catch (e: Exception) {}
                }
            }

            // Re-add stashView
            stashView?.let { stash ->
                stashParams?.let { params ->
                    try {
                        windowManager.addView(stash, params)
                    } catch (e: Exception) {}
                }
            }

            // Re-add bubbleView
            bubbleView?.let { bubble ->
                bubbleParams?.let { params ->
                    // Reset position to default
                    params.x = 50
                    params.y = 200
                    // Show bubble if window is not open, hide if window is open
                    bubble.visibility = if (floatingWindow == null) View.VISIBLE else View.INVISIBLE
                    bubble.alpha = 1f
                    bubble.scaleX = 1f
                    bubble.scaleY = 1f
                    try {
                        windowManager.addView(bubble, params)
                    } catch (e: Exception) {}
                }
            }

            // Re-add floatingWindow (if it was open)
            floatingWindow?.let { window ->
                floatingWindowParams?.let { params ->
                    // Restore saved position/size
                    savedWindowX?.let { params.x = it.toInt() }
                    savedWindowY?.let { params.y = it.toInt() }
                    savedWindowWidth?.let { params.width = it }
                    savedWindowHeight?.let { params.height = it }

                    window.visibility = View.VISIBLE
                    try {
                        windowManager.addView(window, params)
                    } catch (e: Exception) {}
                }
            }
        }
    }

    // Show notification to restore browser window
    private fun showRestoreNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel (required for Android O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Browser Restore",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Tap to restore browser window"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Create intent to restore overlays
        val restoreIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_RESTORE_OVERLAYS
        }
        val pendingIntent = PendingIntent.getService(
            this,
            0,
            restoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build notification
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            android.app.Notification.Builder(this)
        }.apply {
            setSmallIcon(R.drawable.ic_globe)
            setContentTitle("Browser Hidden")
            setContentText("Tap to restore browser window")
            setContentIntent(pendingIntent)
            setAutoCancel(true)
        }.build()

        notificationManager.notify(RESTORE_NOTIFICATION_ID, notification)
    }

    // Inject credentials into WebView
    private fun injectCredentials(username: String, password: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val escapedUsername = username.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
            val escapedPassword = password.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

            val script = """
                (function() {
                    // Find username/email field
                    var usernameSelectors = [
                        'input[type="text"][name*="user"]',
                        'input[type="text"][name*="login"]',
                        'input[type="text"][name*="email"]',
                        'input[type="email"]',
                        'input[name="login"]',
                        'input[name="username"]',
                        'input[name="email"]',
                        'input[id*="user"]',
                        'input[id*="login"]',
                        'input[id*="email"]',
                        'input[autocomplete="username"]',
                        'input[autocomplete="email"]',
                        'input[type="text"]:not([type="password"])'
                    ];

                    var usernameField = null;
                    for (var i = 0; i < usernameSelectors.length; i++) {
                        usernameField = document.querySelector(usernameSelectors[i]);
                        if (usernameField) break;
                    }

                    // Find password field
                    var passwordField = document.querySelector('input[type="password"]');

                    // Set value and trigger events
                    function setValueAndTrigger(field, value) {
                        if (!field) return;
                        field.focus();
                        field.value = value;
                        field.dispatchEvent(new Event('input', { bubbles: true }));
                        field.dispatchEvent(new Event('change', { bubbles: true }));
                    }

                    if (usernameField && '$escapedUsername') {
                        setValueAndTrigger(usernameField, '$escapedUsername');
                    }
                    if (passwordField && '$escapedPassword') {
                        setValueAndTrigger(passwordField, '$escapedPassword');
                    }

                    return {
                        usernameFound: !!usernameField,
                        passwordFound: !!passwordField
                    };
                })();
            """.trimIndent()

            BrowserActivity.webView?.evaluateJavascript(script) { result ->
                android.util.Log.d("FloatingBubble", "Credentials injected: $result")
            }
        }
    }

    // Detect login form and toggle auth button visibility
    private fun detectLoginForm(view: WebView?) {
        android.util.Log.d("FloatingBubble", "detectLoginForm called, view=$view, authButton=$authButton")
        val script = """
            (function() {
                // Check for password field
                var passwordField = document.querySelector('input[type="password"]');
                if (passwordField) return true;

                // Check for login form-like elements
                var loginIndicators = [
                    'input[name*="login"]',
                    'input[name*="user"]',
                    'input[name*="email"]',
                    'input[autocomplete="username"]',
                    'input[autocomplete="email"]',
                    'form[action*="login"]',
                    'form[action*="signin"]',
                    'form[action*="auth"]'
                ];

                for (var i = 0; i < loginIndicators.length; i++) {
                    if (document.querySelector(loginIndicators[i])) return true;
                }

                return false;
            })();
        """.trimIndent()

        view?.evaluateJavascript(script) { result ->
            android.util.Log.d("FloatingBubble", "detectLoginForm result: $result")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                hasLoginForm = (result == "true")
                android.util.Log.d("FloatingBubble", "detectLoginForm: hasLoginForm=$hasLoginForm, setting authButton visibility")
                if (hasLoginForm) {
                    authButton?.visibility = View.VISIBLE
                } else {
                    authButton?.visibility = View.GONE
                }
            }
        }
    }

    // Apply current UA to WebView (called when UA mode changes)
    private fun applyCurrentUa() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            BrowserActivity.webView?.settings?.userAgentString = getCurrentUa()
        }
    }

    fun captureScreenshot(): String? {
        val webView = BrowserActivity.webView ?: return null

        val bitmap = Bitmap.createBitmap(
            webView.width,
            webView.height,
            Bitmap.Config.ARGB_8888
        )

        val canvas = android.graphics.Canvas(bitmap)
        webView.draw(canvas)

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val bytes = outputStream.toByteArray()

        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove hidden container
        hiddenWebViewContainer?.let {
            it.removeAllViews()
            windowManager.removeView(it)
        }
        hiddenWebViewContainer = null

        floatingWindow?.let {
            (it as? LinearLayout)?.let { layout ->
                (layout.getChildAt(1) as? FrameLayout)?.removeAllViews()
            }
            windowManager.removeView(it)
        }
        bubbleView?.let { windowManager.removeView(it) }
        trashView?.let { windowManager.removeView(it) }
        stashView?.let { windowManager.removeView(it) }
        BrowserActivity.webView?.destroy()
        BrowserActivity.webView = null
    }
}
