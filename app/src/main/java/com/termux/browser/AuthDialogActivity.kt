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
 * Custom EditText that passes webDomain to the Autofill framework
 * Allows password managers to recognize the domain
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
        // Callbacks
        var onCredentialsEntered: ((username: String, password: String) -> Unit)? = null
        var onDialogClosed: (() -> Unit)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make background semi-transparent
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.5f)

        // Main container
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                // Cancel on background tap
                finish()
            }
        }

        // Dialog body
        val dialog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 24f
            }
            elevation = 24f
            val padding = 32
            setPadding(padding, padding, padding, padding)

            // Consume click event (don't propagate to background)
            setOnClickListener { }
        }

        val dialogParams = FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = (resources.displayMetrics.heightPixels * 0.1).toInt()  // 10% from top
        }

        // Get URL and extract domain
        val url = intent?.getStringExtra("url") ?: ""
        val domain = try {
            android.net.Uri.parse(url).host ?: url
        } catch (e: Exception) {
            url
        }

        // Title
        val title = TextView(this).apply {
            text = "Enter Credentials"
            textSize = 18f
            setTextColor(Color.parseColor("#333333"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        dialog.addView(title)

        // Domain display
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

        // Description
        val description = TextView(this).apply {
            text = "Tap input fields to launch password manager"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        dialog.addView(description)

        // Username input
        val usernameLabel = TextView(this).apply {
            text = "Username / Email"
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, 8)
        }
        dialog.addView(usernameLabel)

        // Use WebDomainEditText so password manager can recognize the domain
        val usernameInput = WebDomainEditText(this, domain).apply {
            hint = "username@example.com"
            textSize = 16f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#f5f5f5"))
                cornerRadius = 12f
                setStroke(2, Color.parseColor("#e0e0e0"))
            }
            setPadding(24, 20, 24, 20)

            // Autofill settings
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

        // Password input
        val passwordLabel = TextView(this).apply {
            text = "Password"
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

            // Autofill settings
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

        // Button container
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        // Cancel button
        val cancelButton = TextView(this).apply {
            text = "Cancel"
            textSize = 16f
            setTextColor(Color.parseColor("#666666"))
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                finish()
            }
        }
        buttonContainer.addView(cancelButton)

        // Submit button
        val submitButton = TextView(this).apply {
            text = "Fill Form"
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
