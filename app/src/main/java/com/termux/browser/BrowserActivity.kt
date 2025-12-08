package com.termux.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView

class BrowserActivity : Activity() {

    companion object {
        @SuppressLint("StaticFieldLeak")
        var instance: BrowserActivity? = null

        var webView: WebView? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        instance = this

        // サービス起動後に終了
        startService(Intent(this, AutomationService::class.java))
        startService(Intent(this, FloatingBubbleService::class.java))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
