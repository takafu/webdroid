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
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
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

        bubble.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    // タップ時に縮小アニメーション
                    v.animate()
                        .scaleX(0.85f)
                        .scaleY(0.85f)
                        .setDuration(100)
                        .start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (initialTouchX - event.rawX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 元のサイズに戻す
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .start()

                    if (Math.abs(event.rawX - initialTouchX) < 10 &&
                        Math.abs(event.rawY - initialTouchY) < 10) {
                        // リップルエフェクト風アニメーション
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
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = bubble
        windowManager.addView(bubble, params)
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
            }

            // WebViewClient設定
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    AutomationService.onPageEvent("page_started", url ?: "")

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
        BrowserActivity.webView?.destroy()
        BrowserActivity.webView = null
    }
}
