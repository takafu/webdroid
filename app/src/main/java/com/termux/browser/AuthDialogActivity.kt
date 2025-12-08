package com.termux.browser

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewStructure
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * webDomainをAutofillフレームワークに渡すカスタムEditText
 * パスワードマネージャーがドメインを認識できるようになる
 */
class WebDomainEditText(context: Context, private val webDomain: String) : EditText(context) {
    override fun onProvideAutofillStructure(structure: ViewStructure, flags: Int) {
        super.onProvideAutofillStructure(structure, flags)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && webDomain.isNotEmpty()) {
            structure.setWebDomain(webDomain)
        }
    }
}

class AuthDialogActivity : Activity() {

    companion object {
        // コールバック用
        var onCredentialsEntered: ((username: String, password: String) -> Unit)? = null
        var onDialogClosed: (() -> Unit)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 背景を半透明に
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.5f)

        // メインコンテナ
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                // 背景タップでキャンセル
                finish()
            }
        }

        // ダイアログ本体
        val dialog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 24f
            }
            elevation = 24f
            val padding = 32
            setPadding(padding, padding, padding, padding)

            // クリックイベントを消費（背景に伝播させない）
            setOnClickListener { }
        }

        val dialogParams = FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = (resources.displayMetrics.heightPixels * 0.1).toInt()  // 画面上部10%の位置
        }

        // URLを取得してドメインを抽出
        val url = intent?.getStringExtra("url") ?: ""
        val domain = try {
            android.net.Uri.parse(url).host ?: url
        } catch (e: Exception) {
            url
        }

        // タイトル
        val title = TextView(this).apply {
            text = "🔐 認証情報を入力"
            textSize = 18f
            setTextColor(Color.parseColor("#333333"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        dialog.addView(title)

        // ドメイン表示
        if (domain.isNotEmpty()) {
            val domainLabel = TextView(this).apply {
                text = "🌐 $domain"
                textSize = 14f
                setTextColor(Color.parseColor("#667eea"))
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#f0f0ff"))
                    cornerRadius = 8f
                }
                setPadding(16, 8, 16, 8)
            }
            val domainParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                bottomMargin = 16
            }
            dialog.addView(domainLabel, domainParams)
        }

        // 説明
        val description = TextView(this).apply {
            text = "入力欄をタップするとパスワードマネージャーが起動します"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        dialog.addView(description)

        // ユーザー名入力
        val usernameLabel = TextView(this).apply {
            text = "ユーザー名 / メール"
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, 8)
        }
        dialog.addView(usernameLabel)

        // WebDomainEditTextを使用してパスワードマネージャーにドメインを認識させる
        val usernameInput = WebDomainEditText(this, domain).apply {
            hint = "username@example.com"
            textSize = 16f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#f5f5f5"))
                cornerRadius = 12f
                setStroke(2, Color.parseColor("#e0e0e0"))
            }
            setPadding(24, 20, 24, 20)

            // Autofill設定
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                setAutofillHints(View.AUTOFILL_HINT_USERNAME, View.AUTOFILL_HINT_EMAIL_ADDRESS)
            }
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val usernameParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 24
        }
        dialog.addView(usernameInput, usernameParams)

        // パスワード入力
        val passwordLabel = TextView(this).apply {
            text = "パスワード"
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, 8)
        }
        dialog.addView(passwordLabel)

        val passwordInput = WebDomainEditText(this, domain).apply {
            hint = "••••••••"
            textSize = 16f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#f5f5f5"))
                cornerRadius = 12f
                setStroke(2, Color.parseColor("#e0e0e0"))
            }
            setPadding(24, 20, 24, 20)

            // Autofill設定
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
            }
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val passwordParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 32
        }
        dialog.addView(passwordInput, passwordParams)

        // ボタンコンテナ
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        // キャンセルボタン
        val cancelButton = TextView(this).apply {
            text = "キャンセル"
            textSize = 16f
            setTextColor(Color.parseColor("#666666"))
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                finish()
            }
        }
        buttonContainer.addView(cancelButton)

        // 入力ボタン
        val submitButton = TextView(this).apply {
            text = "フォームに入力"
            textSize = 16f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                colors = intArrayOf(
                    Color.parseColor("#667eea"),
                    Color.parseColor("#764ba2")
                )
                cornerRadius = 12f
            }
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                val username = usernameInput.text.toString()
                val password = passwordInput.text.toString()

                if (username.isNotEmpty() || password.isNotEmpty()) {
                    onCredentialsEntered?.invoke(username, password)
                }
                finish()
            }
        }
        buttonContainer.addView(submitButton)

        dialog.addView(buttonContainer)

        container.addView(dialog, dialogParams)
        setContentView(container)
    }

    override fun onDestroy() {
        super.onDestroy()
        onDialogClosed?.invoke()
        onCredentialsEntered = null
        onDialogClosed = null
    }
}
