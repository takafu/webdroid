package com.termux.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class BrowserActivity : Activity() {

    companion object {
        @SuppressLint("StaticFieldLeak")
        var instance: BrowserActivity? = null

        var webView: WebView? = null

        const val EXTRA_FULLSCREEN = "fullscreen"
    }

    private var isFullscreenMode = false
    private var contentLayout: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        instance = this

        // フルスクリーンモードかどうかを確認
        isFullscreenMode = intent?.getBooleanExtra(EXTRA_FULLSCREEN, false) ?: false

        if (isFullscreenMode) {
            setupFullscreenMode()
        } else {
            // 通常モード: サービス起動後に終了
            startService(Intent(this, AutomationService::class.java))
            startService(Intent(this, FloatingBubbleService::class.java))
            finish()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)

        val fullscreen = intent?.getBooleanExtra(EXTRA_FULLSCREEN, false) ?: false
        if (fullscreen && !isFullscreenMode) {
            isFullscreenMode = true
            setupFullscreenMode()
        }
    }

    private fun setupFullscreenMode() {
        // メインレイアウト
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // ヘッダー（バブルに戻るボタン）
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

        // バブルに戻るボタン
        val backToBubbleButton = TextView(this).apply {
            text = "↩ バブルに戻る"
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#33FFFFFF"))
                cornerRadius = 16f
            }
            setPadding(24, 12, 24, 12)
            setOnClickListener {
                returnToBubble()
            }
        }

        header.addView(title)
        header.addView(backToBubbleButton)
        mainLayout.addView(header)

        // WebViewコンテナ
        contentLayout = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor("#f8f9fa"))
        }

        // WebViewを移動
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            contentLayout?.addView(wv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        mainLayout.addView(contentLayout)
        setContentView(mainLayout)
    }

    private fun returnToBubble() {
        // WebViewを切り離す
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
        }

        // FloatingBubbleServiceに通知してウィンドウを再表示
        FloatingBubbleService.returnFromFullscreen()

        // Activityを終了
        isFullscreenMode = false
        finish()
    }

    override fun onBackPressed() {
        if (isFullscreenMode) {
            // フルスクリーンモードではバブルに戻る
            returnToBubble()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isFullscreenMode) {
            instance = null
        }
    }
}
