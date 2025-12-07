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

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var floatingWindow: View? = null
    private var isExpanded = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // WebViewを初期化
        if (BrowserActivity.webView == null) {
            BrowserActivity.webView = createWebView()
        }

        createBubble()
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

        // ヘッダー（閉じるボタン）- グラデーション背景
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                colors = intArrayOf(
                    Color.parseColor("#667eea"),
                    Color.parseColor("#764ba2")
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadii = floatArrayOf(24f, 24f, 24f, 24f, 0f, 0f, 0f, 0f)
            }
            setPadding(24, 20, 24, 20)
        }

        val title = TextView(this).apply {
            text = "🌐 Browser Automation"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val closeButton = Button(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 28f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                // 閉じる時のアニメーション - バブルの位置に向かって縮小
                val bubbleParams = bubbleView?.layoutParams as? WindowManager.LayoutParams
                val bubbleSize = 130f
                val currentWidth = (resources.displayMetrics.widthPixels * 0.95f)
                val currentHeight = (resources.displayMetrics.heightPixels * 0.85f)

                val scaleXEnd = bubbleSize / currentWidth
                val scaleYEnd = bubbleSize / currentHeight

                // バブルの位置を計算
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels
                val bubbleCenterX = screenWidth - (bubbleParams?.x ?: 50) - bubbleSize.toInt() / 2
                val bubbleCenterY = (bubbleParams?.y ?: 200) + bubbleSize.toInt() / 2
                val windowCenterX = screenWidth / 2f
                val windowCenterY = screenHeight / 2f

                // バブルへの移動量
                val translationX = bubbleCenterX - windowCenterX
                val translationY = bubbleCenterY - windowCenterY
                val scaledTranslationX = translationX * (1f - scaleXEnd)
                val scaledTranslationY = translationY * (1f - scaleYEnd)

                floatingWindow?.animate()
                    ?.scaleX(scaleXEnd)
                    ?.scaleY(scaleYEnd)
                    ?.translationX(scaledTranslationX)
                    ?.translationY(scaledTranslationY)
                    ?.alpha(0f)
                    ?.setDuration(250)
                    ?.setInterpolator(AccelerateDecelerateInterpolator())
                    ?.withEndAction { closeFloatingWindow() }
                    ?.start()
            }
        }

        header.addView(title)
        header.addView(closeButton)
        container.addView(header)

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

        // バブルの位置を取得
        val bubbleParams = bubbleView?.layoutParams as? WindowManager.LayoutParams
        val bubbleSize = 130

        // ウィンドウの最終サイズ
        val finalWidth = (resources.displayMetrics.widthPixels * 0.95).toInt()
        val finalHeight = (resources.displayMetrics.heightPixels * 0.85).toInt()

        // バブルの画面上の位置を計算（右上）
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        // バブルの中心座標
        val bubbleCenterX = screenWidth - (bubbleParams?.x ?: 50) - bubbleSize / 2
        val bubbleCenterY = (bubbleParams?.y ?: 200) + bubbleSize / 2

        val windowParams = WindowManager.LayoutParams(
            finalWidth,
            finalHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        floatingWindow = container
        windowManager.addView(container, windowParams)
        isExpanded = true

        // WebViewコンテナを最初は透明に
        webViewContainer.alpha = 0f

        // バブルのサイズから開始（円形→ウィンドウ）
        val scaleXStart = bubbleSize.toFloat() / finalWidth
        val scaleYStart = bubbleSize.toFloat() / finalHeight

        // ウィンドウの最終位置の中心（画面中央）
        val windowCenterX = screenWidth / 2f
        val windowCenterY = screenHeight / 2f

        // バブルの位置からウィンドウの中心への移動量
        val translationX = bubbleCenterX - windowCenterX
        val translationY = bubbleCenterY - windowCenterY

        // スケール時の位置補正（スケールの中心をバブル位置にするため）
        val scaledTranslationX = translationX * (1f - scaleXStart)
        val scaledTranslationY = translationY * (1f - scaleYStart)

        container.alpha = 1f
        container.scaleX = scaleXStart
        container.scaleY = scaleYStart
        container.translationX = scaledTranslationX
        container.translationY = scaledTranslationY

        container.animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(350)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

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
