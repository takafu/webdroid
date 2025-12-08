package com.termux.browser

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
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

        // ウィンドウを閉じてバブルモードに戻す
        fun minimizeWindow() {
            instance?.minimizeToToBubble()
        }
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var trashView: View? = null
    private var trashParams: WindowManager.LayoutParams? = null
    private var floatingWindow: View? = null
    private var floatingWindowParams: WindowManager.LayoutParams? = null
    private var hiddenWebViewContainer: FrameLayout? = null  // バブル状態でWebViewを保持
    private var isExpanded = false
    private var isAnimating = false  // アニメーション中フラグ

    // ドラッグ用の変数（クロージャでキャプチャするためメンバー変数に）
    private var windowStartX = 0
    private var windowStartY = 0

    // ウィンドウの位置・サイズを保存（復元用）
    private var savedWindowX: Float? = null
    private var savedWindowY: Float? = null
    private var savedWindowWidth: Int? = null
    private var savedWindowHeight: Int? = null

    // 認証ボタン（ログインフォーム検出時のみ表示）
    private var authButton: View? = null
    private var hasLoginForm = false  // ログインフォームが検出されたかのフラグ

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // WebViewを初期化
        if (BrowserActivity.webView == null) {
            BrowserActivity.webView = createWebView()
        }

        createBubble()
        createHiddenWebViewContainer()
    }

    private fun createHiddenWebViewContainer() {
        // バブル状態でもWebViewを保持するための隠しコンテナ
        val container = FrameLayout(this).apply {
            alpha = 0.02f  // ほぼ透明（2%）- 描画を維持するために必要
        }

        // WebViewを追加
        BrowserActivity.webView?.let { webView ->
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            container.addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        val params = WindowManager.LayoutParams(
            1080,  // スクリーンショット用に十分なサイズ
            1920,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,  // タッチ不可
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

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubble() {
        // バブル（丸いぽっち）を作成 - グラデーション＋シャドウ＋アニメーション
        val bubble = TextView(this).apply {
            text = "🌐"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false

            // 自動サイズ調整 - 原理的にはみ出ないように
            setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM)
            // 最小〜最大サイズの範囲を指定（単位: sp）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setAutoSizeTextTypeUniformWithConfiguration(
                    20, // 最小サイズ (sp)
                    60, // 最大サイズ (sp)
                    1,  // ステップ (sp)
                    android.util.TypedValue.COMPLEX_UNIT_SP
                )
            }

            // パディングを設定してバブルの境界内に収める
            val padding = 12
            setPadding(padding, padding, padding, padding)

            // グラデーション背景（紫→青のモダンなグラデーション）
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(
                    Color.parseColor("#667eea"), // 明るい紫
                    Color.parseColor("#764ba2")  // 深い紫
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }

            // エレベーション（影）を追加
            elevation = 16f

            // パルスアニメーションを開始
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

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var velocityTracker: VelocityTracker? = null
        var isDragging = false

        // ゴミ箱を作成（バブルと同じ描画方法）
        val trashSize = 130
        val trash = TextView(this).apply {
            text = "🗑️"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setAutoSizeTextTypeUniformWithConfiguration(20, 60, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            val padding = 12
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(
                    Color.parseColor("#5a5a5a"),  // グレー
                    Color.parseColor("#3a3a3a")   // ダークグレー
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
            elevation = 16f
            alpha = 0f  // 初期は非表示
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

                    // VelocityTracker初期化
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)

                    // タップ時に縮小アニメーション
                    v.animate()
                        .scaleX(0.85f)
                        .scaleY(0.85f)
                        .setDuration(100)
                        .start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)

                    val deltaX = Math.abs(event.rawX - initialTouchX)
                    val deltaY = Math.abs(event.rawY - initialTouchY)

                    // ドラッグ開始判定
                    if (!isDragging && (deltaX > 10 || deltaY > 10)) {
                        isDragging = true
                        // ゴミ箱を表示
                        trash.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(200)
                            .start()
                    }

                    params.x = initialX + (initialTouchX - event.rawX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(v, params)

                    // ゴミ箱との距離チェック
                    if (isDragging) {
                        val bubbleCenterX = screenWidth - params.x - 65  // バブルの中心X
                        val bubbleCenterY = params.y + 65  // バブルの中心Y
                        val trashCenterX = screenWidth / 2
                        val trashCenterY = screenHeight - 100 - 65  // ゴミ箱の中心Y

                        val distance = Math.sqrt(
                            Math.pow((bubbleCenterX - trashCenterX).toDouble(), 2.0) +
                            Math.pow((bubbleCenterY - trashCenterY).toDouble(), 2.0)
                        )

                        // 近づくとゴミ箱を拡大（即座に切り替え）
                        val isNear = distance < 200
                        if (isNear && trash.scaleX < 1.2f) {
                            trash.animate()
                                .scaleX(1.3f)
                                .scaleY(1.3f)
                                .setDuration(50)
                                .start()
                        } else if (!isNear && trash.scaleX > 1.1f) {
                            trash.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(50)
                                .start()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velocityX = velocityTracker?.xVelocity ?: 0f
                    val velocityY = velocityTracker?.yVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null

                    // 元のサイズに戻す
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .start()

                    // ゴミ箱にドロップされたかチェック
                    val bubbleCenterX = screenWidth - params.x - 65
                    val bubbleCenterY = params.y + 65
                    val trashCenterX = screenWidth / 2
                    val trashCenterY = screenHeight - 100 - 65

                    val distance = Math.sqrt(
                        Math.pow((bubbleCenterX - trashCenterX).toDouble(), 2.0) +
                        Math.pow((bubbleCenterY - trashCenterY).toDouble(), 2.0)
                    )

                    if (isDragging && distance < 200) {
                        // ゴミ箱に吸い込まれる
                        animateToTrash(v, params, trash, screenWidth, screenHeight)
                    } else if (!isDragging) {
                        // タップ - ウィンドウを開く
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
                        // 慣性アニメーション（ゴミ箱は表示したまま）
                        applyFlingAnimation(v, params, trash, velocityX, velocityY, screenWidth, screenHeight)
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = bubble
        windowManager.addView(bubble, params)
    }

    // ゴミ箱に吸い込まれるアニメーション
    private fun animateToTrash(
        bubble: View,
        params: WindowManager.LayoutParams,
        trash: View,
        screenWidth: Int,
        screenHeight: Int
    ) {
        // ゴミ箱の中心座標（画面下部中央）
        val trashCenterX = screenWidth / 2
        val trashCenterY = screenHeight - 100 - 65

        // バブルの現在の中心座標
        val startX = screenWidth - params.x - 65
        val startY = params.y + 65

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                // バブルをゴミ箱中心に向かって移動
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
                    // ゴミ箱が反応して消える
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

    // 慣性アニメーション（ゴミ箱判定付き）
    private fun applyFlingAnimation(
        view: View,
        params: WindowManager.LayoutParams,
        trash: View,
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

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                vx *= friction
                vy *= friction

                params.x = (params.x + vx).toInt().coerceIn(0, screenWidth - 130)
                params.y = (params.y + vy).toInt().coerceIn(0, screenHeight - 130)

                // ゴミ箱との距離チェック
                val bubbleCenterX = screenWidth - params.x - 65
                val bubbleCenterY = params.y + 65
                val distance = Math.sqrt(
                    Math.pow((bubbleCenterX - trashCenterX).toDouble(), 2.0) +
                    Math.pow((bubbleCenterY - trashCenterY).toDouble(), 2.0)
                )

                // ゴミ箱に近づいたらスケール変更（即座に切り替え）
                val isNear = distance < 200
                if (isNear && trash.scaleX < 1.2f) {
                    trash.animate()
                        .scaleX(1.3f)
                        .scaleY(1.3f)
                        .setDuration(50)
                        .start()
                } else if (!isNear && trash.scaleX > 1.1f) {
                    trash.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(50)
                        .start()
                }

                // ゴミ箱に十分近づいたら吸い込み
                if (distance < 120) {
                    cancel()
                    animateToTrash(view, params, trash, screenWidth, screenHeight)
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
                    // 慣性終了後にゴミ箱を非表示
                    trash.animate()
                        .alpha(0f)
                        .scaleX(0.5f)
                        .scaleY(0.5f)
                        .setDuration(200)
                        .start()
                }
                override fun onAnimationCancel(a: android.animation.Animator) {}
                override fun onAnimationRepeat(a: android.animation.Animator) {}
            })
        }
        animator.start()
    }

    // パルスアニメーション（生きている感じを演出）
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

        // バブルを即座に非表示（シームレスな変形のため）
        bubbleView?.visibility = View.INVISIBLE

        // ラッパー（画面全体サイズ、クリッピング無効）
        val wrapper = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 全体を角丸に
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 24f
            }
            elevation = 24f
            clipToOutline = true  // 子要素も角丸の境界でクリップ
        }

        // ヘッダー（ミニマイズボタン）- グラデーション背景
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
            text = "🌐 Browser Automation"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        // 認証ボタン（パスワードマネージャー連携）- ログインフォーム検出時のみ表示
        val authBtn = TextView(this).apply {
            text = "🔐"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            val btnSize = 48
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginEnd = 8
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FFFFFF"))
            }
            // hasLoginFormフラグに基づいて初期表示を決定
            visibility = if (hasLoginForm) View.VISIBLE else View.GONE
            setOnClickListener {
                showAuthDialog()
            }
        }
        authButton = authBtn  // メンバー変数に保存

        // ミニマイズボタン（丸の中に小さな丸）
        val minimizeButton = View(this).apply {
            val btnSize = 56
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            // 外側の半透明丸 + 内側の白丸をレイヤーで描画
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#33FFFFFF"))
                },
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
            )).apply {
                // 内側の丸を中央に小さく配置
                val inset = 20
                setLayerInset(1, inset, inset, inset, inset)
            }
            setOnClickListener {
                // アニメーション中は無視
                if (isAnimating) return@setOnClickListener

                val params = floatingWindowParams ?: return@setOnClickListener
                val wrapper = floatingWindow ?: return@setOnClickListener

                isAnimating = true

                // 閉じる時のアニメーション - バブルの位置に向かって縮小
                val bubbleParams = bubbleView?.layoutParams as? WindowManager.LayoutParams
                val bubbleSize = 130f

                // containerの現在のサイズ
                val currentWidth = container.width.toFloat()
                val currentHeight = container.height.toFloat()

                // ウィンドウの位置・サイズを保存（windowParamsから取得）
                savedWindowX = params.x.toFloat()
                savedWindowY = params.y.toFloat()
                savedWindowWidth = currentWidth.toInt()
                savedWindowHeight = currentHeight.toInt()

                if (currentWidth <= 0 || currentHeight <= 0) {
                    isAnimating = false
                    closeFloatingWindow()
                    return@setOnClickListener
                }

                // wrapperをフルスクリーンに戻す（アニメーション用）
                // 1. containerのtranslationを設定（見た目位置を維持）
                container.translationX = params.x.toFloat()
                container.translationY = params.y.toFloat()

                // 2. wrapperをフルスクリーンに
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels
                params.x = 0
                params.y = 0
                params.width = screenWidth
                params.height = screenHeight
                windowManager.updateViewLayout(wrapper, params)

                val scaleXEnd = bubbleSize / currentWidth
                val scaleYEnd = bubbleSize / currentHeight

                // バブルの位置を計算
                val bubbleCenterX = screenWidth - (bubbleParams?.x ?: 50) - bubbleSize / 2f
                val bubbleCenterY = (bubbleParams?.y ?: 200) + bubbleSize / 2f

                // バブルの中心に移動するためのtranslation（pivotが中心なので、左上座標を計算）
                val targetTranslationX = bubbleCenterX - currentWidth / 2f
                val targetTranslationY = bubbleCenterY - currentHeight / 2f

                // pivotを中心に設定
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
                    .setListener(null)  // 開くアニメのリスナーをクリア
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

        // タイトルバーのドラッグ処理
        var dragStartX = 0f
        var dragStartY = 0f

        header.setOnTouchListener { _, event ->
            // アニメーション中はドラッグ無効
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

        // WebViewコンテナ - 内側にパディング
        val webViewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.parseColor("#f8f9fa"))
        }

        // WebViewを作成または再利用
        if (BrowserActivity.webView == null) {
            BrowserActivity.webView = createWebView()
        }

        BrowserActivity.webView?.let { webView ->
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webViewContainer.addView(webView)
        }

        container.addView(webViewContainer)

        // リサイズハンドル（右下角）
        val resizeHandle = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#667eea"))
                cornerRadius = 8f
            }
            layoutParams = LinearLayout.LayoutParams(56, 56).apply {
                gravity = Gravity.END
            }
        }
        container.addView(resizeHandle)

        // バブルの位置を取得
        val bubbleParams = bubbleView?.layoutParams as? WindowManager.LayoutParams
        val bubbleSize = 130

        // 画面サイズ
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        // ウィンドウのサイズ（保存されていれば復元、なければデフォルト）
        val finalWidth = savedWindowWidth ?: (screenWidth * 0.95).toInt()
        val finalHeight = savedWindowHeight ?: (screenHeight * 0.45).toInt()

        // バブルの中心座標
        val bubbleCenterX = screenWidth - (bubbleParams?.x ?: 50) - bubbleSize / 2
        val bubbleCenterY = (bubbleParams?.y ?: 200) + bubbleSize / 2

        // containerの位置（保存されていれば復元、なければ上部中央に配置）
        val margin = (screenWidth * 0.025).toInt()  // 左右に2.5%の余白
        val initialX = savedWindowX ?: margin.toFloat()
        val initialY = savedWindowY ?: margin.toFloat()

        // containerのサイズを設定（位置はtranslationで管理）
        container.layoutParams = FrameLayout.LayoutParams(finalWidth, finalHeight)

        // wrapperにcontainerを追加
        wrapper.addView(container)

        // wrapperを画面全体サイズでWindowManagerに追加
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

            // PRIVATE_FLAG_NO_MOVE_ANIMATION をリフレクションで設定
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

        // ウィンドウ外タップでフォーカス解放
        wrapper.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                // フォーカスを解放（FLAG_NOT_FOCUSABLEを追加）
                windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                windowManager.updateViewLayout(wrapper, windowParams)
            }
            false
        }

        // WebViewタップでフォーカス取得
        BrowserActivity.webView?.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // フォーカスを取得（FLAG_NOT_FOCUSABLEを削除）
                val hasFocusFlag = (windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
                if (hasFocusFlag) {
                    windowParams.flags = windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    windowManager.updateViewLayout(wrapper, windowParams)
                }
                // WebViewにフォーカスをリクエスト
                view.requestFocus()
            }
            // falseを返してWebViewのタッチ処理を継続
            false
        }

        // ローカル関数：wrapperをUIサイズに縮小（開くアニメ完了時）
        val shrinkWrapperToUI = {
            val winX = container.translationX.toInt()
            val winY = container.translationY.toInt()
            val winW = container.width
            val winH = container.height

            // translationを先にリセットしてからwrapperを更新
            container.translationX = 0f
            container.translationY = 0f
            windowParams.x = winX
            windowParams.y = winY
            windowParams.width = winW
            windowParams.height = winH
            windowManager.updateViewLayout(wrapper, windowParams)

            // デフォルトは85%透過
            container.alpha = 0.85f
        }

        // リサイズハンドルのドラッグ処理
        var resizeStartX = 0f
        var resizeStartY = 0f
        var startWidth = 0
        var startHeight = 0

        resizeHandle.setOnTouchListener { _, event ->
            // アニメーション中はリサイズ無効
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

                    // 最小サイズを設定
                    val minWidth = 300
                    val minHeight = 400

                    val newWidth = maxOf(minWidth, (startWidth + deltaX).toInt())
                    val newHeight = maxOf(minHeight, (startHeight + deltaY).toInt())

                    // containerとwrapper両方のサイズを更新
                    container.layoutParams = FrameLayout.LayoutParams(newWidth, newHeight)
                    params.width = newWidth
                    params.height = newHeight
                    windowManager.updateViewLayout(wrapper, params)
                    true
                }
                else -> false
            }
        }

        // WebViewコンテナを最初は透明に
        webViewContainer.alpha = 0f

        // バブルのサイズから開始（円形→ウィンドウ）
        val scaleXStart = bubbleSize.toFloat() / finalWidth
        val scaleYStart = bubbleSize.toFloat() / finalHeight

        // containerの最終中心座標（translationベース）
        val finalCenterX = initialX + finalWidth / 2f
        val finalCenterY = initialY + finalHeight / 2f

        // pivotをcontainerの中心に設定
        container.pivotX = finalWidth / 2f
        container.pivotY = finalHeight / 2f

        // 開始位置：バブルの中心に合わせる
        val startTranslationX = bubbleCenterX - finalWidth / 2f
        val startTranslationY = bubbleCenterY - finalHeight / 2f

        // 前のViewPropertyAnimatorをキャンセル（閉じるアニメのリスナーが残っている可能性）
        container.animate().cancel()
        container.animate().setListener(null)

        container.alpha = 1f
        container.scaleX = scaleXStart
        container.scaleY = scaleYStart
        container.translationX = startTranslationX
        container.translationY = startTranslationY

        // アニメーション中フラグを立てる
        isAnimating = true

        // レイアウト確定後にアニメーション開始
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

        // WebViewを中間（175ms後）からフェードイン
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

                // デスクトップモード設定
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false

                // ビューポートを大きく設定してデスクトップレイアウトを強制
                layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.NORMAL

                // デスクトップUserAgent（最新Chrome）
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

                // その他の設定
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true

                // Mixed Contentを許可
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                // より本物のブラウザに近づける
                setSupportMultipleWindows(false)
                setGeolocationEnabled(false)

                // キャッシュ設定
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

                // フォームデータ保存（パスワードマネージャー連携に必要な場合がある）
                @Suppress("DEPRECATION")
                setSaveFormData(true)
                @Suppress("DEPRECATION")
                setSavePassword(true)
            }

            // デバッグ有効化
            WebView.setWebContentsDebuggingEnabled(true)

            // フォーカス設定
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus(View.FOCUS_DOWN)

            // WebViewClient設定
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    AutomationService.onPageEvent("page_started", url ?: "")

                    // ページ読み込み開始時に認証ボタンを非表示
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        authButton?.visibility = View.GONE
                    }

                    // WebView検出を回避するJavaScriptを注入
                    view?.evaluateJavascript("""
                        Object.defineProperty(navigator, 'webdriver', {
                            get: () => undefined
                        });
                    """.trimIndent(), null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    AutomationService.onPageEvent("page_finished", url ?: "")

                    // ログインフォームを検出して認証ボタンの表示/非表示を切り替え
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
            }

            // WebChromeClient設定
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
        // WebViewを隠しコンテナに戻す
        BrowserActivity.webView?.let { webView ->
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            hiddenWebViewContainer?.addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        // ウィンドウを削除（アニメーション完了後に呼ばれる）
        floatingWindow?.let { window ->
            // 削除前にサイズを0にしてフラッシュを防ぐ
            val params = window.layoutParams as? WindowManager.LayoutParams
            params?.let {
                it.width = 0
                it.height = 0
                windowManager.updateViewLayout(window, it)
            }

            // 次のフレームで削除
            window.post {
                windowManager.removeView(window)
                floatingWindow = null
            }
        }
        isExpanded = false

        // バブルを再表示（シンプルに）
        bubbleView?.visibility = View.VISIBLE
    }

    // 外部からウィンドウを閉じてバブルモードに戻す（メインスレッドで実行）
    private fun minimizeToToBubble() {
        if (!isExpanded) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            closeFloatingWindow()
        }
    }

    // 認証ダイアログを表示
    private fun showAuthDialog() {
        // 現在のURLを取得
        val currentUrl = BrowserActivity.webView?.url ?: ""

        // ウィンドウの位置・サイズを保存してミニマイズ
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

        // 全てのオーバーレイをWindowManagerから一時的に削除（Bitwardenのタッチを妨げないように）
        try {
            floatingWindow?.let { windowManager.removeView(it) }
        } catch (e: Exception) {}
        try {
            bubbleView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {}
        try {
            trashView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {}
        try {
            hiddenWebViewContainer?.let { windowManager.removeView(it) }
        } catch (e: Exception) {}

        // コールバックを設定
        AuthDialogActivity.onCredentialsEntered = { username, password ->
            // WebViewに認証情報を注入
            injectCredentials(username, password)
        }

        // ダイアログ終了時にオーバーレイを再追加
        AuthDialogActivity.onDialogClosed = {
            restoreOverlays()
        }

        // AuthDialogActivityを起動（URLを渡す）
        val intent = android.content.Intent(this, AuthDialogActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("url", currentUrl)
        }
        startActivity(intent)
    }

    // フローティングウィンドウを再表示
    private fun reopenFloatingWindow() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            bubbleView?.visibility = View.INVISIBLE
            floatingWindow?.visibility = View.VISIBLE
        }
    }

    // オーバーレイを再追加（認証ダイアログ終了後）
    private fun restoreOverlays() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            // hiddenWebViewContainerを再追加
            hiddenWebViewContainer?.let { container ->
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
                } catch (e: Exception) {}
            }

            // trashViewを再追加
            trashView?.let { trash ->
                trashParams?.let { params ->
                    try {
                        windowManager.addView(trash, params)
                    } catch (e: Exception) {}
                }
            }

            // bubbleViewを再追加（非表示状態で）
            bubbleView?.let { bubble ->
                val bubbleParams = WindowManager.LayoutParams(
                    130, 130,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = 50
                    y = 200
                }
                bubble.visibility = View.INVISIBLE
                try {
                    windowManager.addView(bubble, bubbleParams)
                } catch (e: Exception) {}
            }

            // floatingWindowを再追加
            floatingWindow?.let { window ->
                floatingWindowParams?.let { params ->
                    // 保存した位置・サイズを復元
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

    // WebViewに認証情報を注入
    private fun injectCredentials(username: String, password: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val escapedUsername = username.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
            val escapedPassword = password.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

            val script = """
                (function() {
                    // ユーザー名/メールフィールドを探す
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

                    // パスワードフィールドを探す
                    var passwordField = document.querySelector('input[type="password"]');

                    // 値を設定してイベントを発火
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

    // ログインフォームを検出して認証ボタンの表示/非表示を切り替え
    private fun detectLoginForm(view: WebView?) {
        val script = """
            (function() {
                // パスワードフィールドがあるかチェック
                var passwordField = document.querySelector('input[type="password"]');
                if (passwordField) return true;

                // ログインフォームっぽい要素をチェック
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
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                hasLoginForm = (result == "true")
                if (hasLoginForm) {
                    authButton?.visibility = View.VISIBLE
                } else {
                    authButton?.visibility = View.GONE
                }
            }
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
        // 隠しコンテナを削除
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
        BrowserActivity.webView?.destroy()
        BrowserActivity.webView = null
    }
}
