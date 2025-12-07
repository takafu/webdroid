package com.termux.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

class AutomationService : Service() {

    companion object {
        private const val TAG = "AutomationService"
        private const val PORT = 8765
        private var server: AutomationServer? = null
        private val gson = Gson()

        // イベントコールバック
        fun onPageEvent(event: String, data: String) {
            Log.d(TAG, "Page event: $event - $data")
        }

        fun onConsoleMessage(message: String) {
            Log.d(TAG, "Console: $message")
        }

        fun onProgressChanged(progress: Int) {
            // プログレス更新
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(1, createNotification())
        }

        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "browser_automation",
                "Browser Automation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "HTTP server for browser automation"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "browser_automation")
                .setContentTitle("Browser Automation")
                .setContentText("HTTP Server running on port $PORT")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Browser Automation")
                .setContentText("HTTP Server running on port $PORT")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }

    private fun startServer() {
        try {
            server = AutomationServer()
            server?.start()
            Log.i(TAG, "HTTP Server started on port $PORT")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start server", e)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    inner class AutomationServer : NanoHTTPD(PORT) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method

            Log.d(TAG, "Request: $method $uri")

            return when {
                uri == "/navigate" && method == Method.POST -> handleNavigate(session)
                uri == "/execute" && method == Method.POST -> handleExecute(session)
                uri == "/eval" && method == Method.POST -> handleEval(session)
                uri == "/screenshot" && method == Method.GET -> handleScreenshot()
                uri == "/back" && method == Method.POST -> handleBack()
                uri == "/forward" && method == Method.POST -> handleForward()
                uri == "/refresh" && method == Method.POST -> handleRefresh()
                uri == "/url" && method == Method.GET -> handleGetUrl()
                uri == "/title" && method == Method.GET -> handleGetTitle()
                uri == "/html" && method == Method.GET -> handleGetHtml()
                uri == "/ping" && method == Method.GET -> handlePing()
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json",
                    """{"error":"Not found"}"""
                )
            }
        }

        private fun handleNavigate(session: IHTTPSession): Response {
            val params = parseBody(session)
            val url = params["url"] as? String

            return if (url != null) {
                runOnMainThread {
                    BrowserActivity.webView?.loadUrl(url)
                }
                successResponse("Navigating to $url")
            } else {
                errorResponse("Missing 'url' parameter")
            }
        }

        private fun handleExecute(session: IHTTPSession): Response {
            val params = parseBody(session)
            val script = params["script"] as? String

            return if (script != null) {
                runOnMainThread {
                    BrowserActivity.webView?.evaluateJavascript(script, null)
                }
                successResponse("Script executed")
            } else {
                errorResponse("Missing 'script' parameter")
            }
        }

        private fun handleEval(session: IHTTPSession): Response {
            val params = parseBody(session)
            val script = params["script"] as? String

            return if (script != null) {
                var result: String? = null
                val lock = Object()

                runOnMainThread {
                    BrowserActivity.webView?.evaluateJavascript(script) { value ->
                        synchronized(lock) {
                            result = value
                            lock.notify()
                        }
                    }
                }

                // 結果を待つ
                synchronized(lock) {
                    try {
                        lock.wait(5000) // 5秒タイムアウト
                    } catch (e: InterruptedException) {
                        return errorResponse("Timeout")
                    }
                }

                successResponse(result ?: "null", "result" to result)
            } else {
                errorResponse("Missing 'script' parameter")
            }
        }

        private fun handleScreenshot(): Response {
            var screenshot: String? = null
            val lock = Object()

            runOnMainThread {
                synchronized(lock) {
                    screenshot = BrowserActivity.instance?.captureScreenshot()
                    lock.notify()
                }
            }

            synchronized(lock) {
                try {
                    lock.wait(5000)
                } catch (e: InterruptedException) {
                    return errorResponse("Timeout")
                }
            }

            return if (screenshot != null) {
                successResponse("Screenshot captured", "screenshot" to screenshot)
            } else {
                errorResponse("Failed to capture screenshot")
            }
        }

        private fun handleBack(): Response {
            runOnMainThread {
                BrowserActivity.webView?.goBack()
            }
            return successResponse("Navigated back")
        }

        private fun handleForward(): Response {
            runOnMainThread {
                BrowserActivity.webView?.goForward()
            }
            return successResponse("Navigated forward")
        }

        private fun handleRefresh(): Response {
            runOnMainThread {
                BrowserActivity.webView?.reload()
            }
            return successResponse("Page refreshed")
        }

        private fun handleGetUrl(): Response {
            var url: String? = null
            val lock = Object()

            runOnMainThread {
                synchronized(lock) {
                    url = BrowserActivity.webView?.url
                    lock.notify()
                }
            }

            synchronized(lock) {
                try {
                    lock.wait(1000)
                } catch (e: InterruptedException) {
                    return errorResponse("Timeout")
                }
            }

            return successResponse(url ?: "about:blank", "url" to url)
        }

        private fun handleGetTitle(): Response {
            var title: String? = null
            val lock = Object()

            runOnMainThread {
                synchronized(lock) {
                    title = BrowserActivity.webView?.title
                    lock.notify()
                }
            }

            synchronized(lock) {
                try {
                    lock.wait(1000)
                } catch (e: InterruptedException) {
                    return errorResponse("Timeout")
                }
            }

            return successResponse(title ?: "", "title" to title)
        }

        private fun handleGetHtml(): Response {
            var html: String? = null
            val lock = Object()

            runOnMainThread {
                BrowserActivity.webView?.evaluateJavascript(
                    "document.documentElement.outerHTML"
                ) { value ->
                    synchronized(lock) {
                        html = value
                        lock.notify()
                    }
                }
            }

            synchronized(lock) {
                try {
                    lock.wait(5000)
                } catch (e: InterruptedException) {
                    return errorResponse("Timeout")
                }
            }

            return successResponse("HTML retrieved", "html" to html)
        }

        private fun handlePing(): Response {
            return successResponse("pong", "status" to "ok")
        }

        private fun parseBody(session: IHTTPSession): Map<String, Any> {
            val files = mutableMapOf<String, String>()
            try {
                session.parseBody(files)
                val postData = files["postData"] ?: return emptyMap()
                return gson.fromJson(postData, Map::class.java) as Map<String, Any>
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse body", e)
                return emptyMap()
            }
        }

        private fun successResponse(message: String, vararg data: Pair<String, Any?>): Response {
            val result = mutableMapOf<String, Any?>(
                "success" to true,
                "message" to message
            )
            data.forEach { (key, value) ->
                result[key] = value
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(result)
            )
        }

        private fun errorResponse(message: String): Response {
            val result = mapOf(
                "success" to false,
                "error" to message
            )
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                gson.toJson(result)
            )
        }

        private fun runOnMainThread(action: () -> Unit) {
            Handler(Looper.getMainLooper()).post(action)
        }
    }
}
