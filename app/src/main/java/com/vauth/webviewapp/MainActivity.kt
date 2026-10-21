package com.vauth.webviewapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Advanced WebView app - Edge-to-edge + Dynamic status bar + Better Splash
 * Based on Vauth/android-web
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_URL = "https://example.com"
    }

    private var webView: WebView? = null
    private var container: FrameLayout? = null
    private var loadingOverlay: FrameLayout? = null

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var geolocationCallback: GeolocationPermissions.Callback? = null
    private var geolocationOrigin: String? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var isDestroyed = false
    private var isFirstPageLoad = true

    // File chooser launcher
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val clipData = data?.clipData
                val uris = if (clipData != null) {
                    (0 until clipData.itemCount).map {
                        clipData.getItemAt(it).uri
                    }.toTypedArray()
                } else {
                    data?.data?.let { arrayOf(it) } ?: arrayOf()
                }
                fileUploadCallback?.onReceiveValue(uris)
            } else {
                fileUploadCallback?.onReceiveValue(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling file chooser result", e)
            fileUploadCallback?.onReceiveValue(null)
        } finally {
            fileUploadCallback = null
        }
    }

    // Permission request launcher for geolocation
    private val geolocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        try {
            val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            geolocationCallback?.invoke(geolocationOrigin, locationGranted, false)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling geolocation permission result", e)
            geolocationCallback?.invoke(geolocationOrigin, false, false)
        } finally {
            geolocationCallback = null
            geolocationOrigin = null
        }
    }

    // Permission request launcher for camera/microphone
    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        try {
            val allGranted = permissions.values.all { it }

            pendingPermissionRequest?.let { request ->
                if (allGranted) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling media permission result", e)
            pendingPermissionRequest?.deny()
        } finally {
            pendingPermissionRequest = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // ===== EDGE-TO-EDGE SETUP =====
            WindowCompat.setDecorFitsSystemWindows(window, false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            // Start with fully transparent bars
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT

            // ===== CREATE LAYOUT =====
            container = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            // Create WebView
            webView = WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            // ===== IMPROVED LOADING / SPLASH SCREEN =====
            loadingOverlay = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.parseColor("#0F0F0F")) // Clean dark background
                visibility = View.VISIBLE

                // App name text in the center
                val appNameText = TextView(context).apply {
                    text = try {
                        // Try to get the real app name
                        packageManager.getApplicationLabel(applicationInfo).toString()
                    } catch (e: Exception) {
                        "Loading..."
                    }
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER
                    }
                }
                addView(appNameText)
            }

            container?.addView(webView)
            container?.addView(loadingOverlay)

            setContentView(container)

            // ===== Proper insets so content never goes under status bar =====
            container?.let { root ->
                ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    webView?.setPadding(0, systemBars.top, 0, systemBars.bottom)
                    insets
                }
            }

            setupBackPressHandler()
            setupWebView()

            // Load the configured website URL
            val websiteUrl = BuildConfig.WEBSITE_URL

            if (websiteUrl.isNullOrBlank()) {
                Log.e(TAG, "Website URL is null or blank, using default")
                webView?.loadUrl(DEFAULT_URL)
                Toast.makeText(this, "Configuration error, using default URL", Toast.LENGTH_LONG).show()
            } else {
                webView?.loadUrl(websiteUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate", e)
            Toast.makeText(this, "Failed to initialize app: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun setupBackPressHandler() {
        try {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    try {
                        if (webView?.canGoBack() == true) {
                            webView?.goBack()
                        } else {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling back press", e)
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up back press handler", e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        try {
            val settings = webView?.settings ?: return

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                setGeolocationEnabled(true)
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                userAgentString = "$userAgentString WebViewApp/1.0"
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring WebView settings", e)
        }

        webView?.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                try {
                    super.onPageFinished(view, url)

                    // Smooth fade-out of the splash screen
                    if (isFirstPageLoad) {
                        isFirstPageLoad = false
                        fadeOutLoadingOverlay()
                    }

                    // ===== DYNAMIC STATUS BAR COLOR =====
                    view?.evaluateJavascript(
                        """
                        (function() {
                            var bg = window.getComputedStyle(document.body).backgroundColor;
                            if (!bg || bg === 'rgba(0, 0, 0, 0)' || bg === 'transparent') {
                                bg = window.getComputedStyle(document.documentElement).backgroundColor;
                            }
                            return bg;
                        })();
                        """.trimIndent()
                    ) { result ->
                        try {
                            if (result != null && result != "null") {
                                val colorString = result.replace("\"", "").trim()
                                val color = parseCssColor(colorString)
                                applyStatusBarColor(color)
                            } else {
                                applyStatusBarColor(null)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error applying status bar color", e)
                            applyStatusBarColor(null)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onPageFinished", e)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                try {
                    val url = request?.url?.toString() ?: return false

                    if (url.startsWith("tel:") || url.startsWith("mailto:") ||
                        url.startsWith("sms:") || url.startsWith("whatsapp:")
                    ) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Cannot open this link", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }

                    if (url.startsWith("intent:")) {
                        try {
                            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                            startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Cannot parse intent URL: $url", e)
                        }
                        return true
                    }

                    return false
                } catch (e: Exception) {
                    Log.e(TAG, "Error in shouldOverrideUrlLoading", e)
                    return false
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                try {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true && isFirstPageLoad) {
                        isFirstPageLoad = false
                        fadeOutLoadingOverlay()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onReceivedError", e)
                }
            }
        }

        webView?.webChromeClient = object : WebChromeClient() {

            // File upload
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                try {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback

                    val intent = fileChooserParams?.createIntent()
                    if (intent != null) {
                        fileChooserLauncher.launch(intent)
                    } else {
                        val chooserIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                        fileChooserLauncher.launch(chooserIntent)
                    }
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing file chooser", e)
                    filePathCallback?.onReceiveValue(null)
                    return false
                }
            }

            // Geolocation
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                try {
                    geolocationOrigin = origin
                    geolocationCallback = callback

                    val permissions = arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )

                    if (hasPermissions(permissions)) {
                        callback?.invoke(origin, true, false)
                    } else {
                        geolocationPermissionLauncher.launch(permissions)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling geolocation permission", e)
                    callback?.invoke(origin, false, false)
                }
            }

            // Popups
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                try {
                    if (resultMsg == null) return false
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

                    val newWebView = WebView(this@MainActivity)
                    transport.webView = newWebView
                    resultMsg.sendToTarget()

                    newWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            webView?.loadUrl(request?.url?.toString() ?: "")
                            return true
                        }
                    }
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating window", e)
                    return false
                }
            }

            // Fullscreen video
            private var customView: View? = null
            private var customViewCallback: CustomViewCallback? = null

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                try {
                    customView = view
                    customViewCallback = callback
                    container?.addView(view)
                    webView?.visibility = View.GONE
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing custom view", e)
                }
            }

            override fun onHideCustomView() {
                try {
                    container?.removeView(customView)
                    webView?.visibility = View.VISIBLE
                    customViewCallback?.onCustomViewHidden()
                    customView = null
                    customViewCallback = null
                } catch (e: Exception) {
                    Log.e(TAG, "Error hiding custom view", e)
                }
            }

            // Camera / Microphone
            override fun onPermissionRequest(request: PermissionRequest?) {
                try {
                    request?.let { permRequest ->
                        val permissions = mutableListOf<String>()

                        permRequest.resources.forEach { resource ->
                            when (resource) {
                                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                                    permissions.add(Manifest.permission.CAMERA)
                                }
                                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                                    permissions.add(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }

                        if (permissions.isEmpty()) {
                            permRequest.grant(permRequest.resources)
                        } else if (hasPermissions(permissions.toTypedArray())) {
                            permRequest.grant(permRequest.resources)
                        } else {
                            pendingPermissionRequest = permRequest
                            mediaPermissionLauncher.launch(permissions.toTypedArray())
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling permission request", e)
                    request?.deny()
                }
            }
        }
    }

    // ===== Smooth fade-out of splash =====
    private fun fadeOutLoadingOverlay() {
        val overlay = loadingOverlay ?: return

        val fadeOut = AlphaAnimation(1f, 0f).apply {
            duration = 400 // milliseconds
            fillAfter = true
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}
                override fun onAnimationRepeat(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    overlay.visibility = View.GONE
                }
            })
        }
        overlay.startAnimation(fadeOut)
    }

    // ===== DYNAMIC STATUS BAR HELPERS =====
    private fun parseCssColor(cssColor: String): Int? {
        return try {
            when {
                cssColor.startsWith("rgb") -> {
                    val values = cssColor
                        .replace("rgba(", "")
                        .replace("rgb(", "")
                        .replace(")", "")
                        .split(",")
                        .map { it.trim().toFloatOrNull() ?: 0f }

                    if (values.size >= 3) {
                        val r = values[0].toInt().coerceIn(0, 255)
                        val g = values[1].toInt().coerceIn(0, 255)
                        val b = values[2].toInt().coerceIn(0, 255)
                        val a = if (values.size >= 4) values[3] else 1f

                        if (a < 0.35f) return null
                        Color.rgb(r, g, b)
                    } else null
                }
                cssColor.startsWith("#") -> Color.parseColor(cssColor)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun applyStatusBarColor(color: Int?) {
        try {
            if (color == null) {
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
                return
            }

            val luminance = (0.299 * Color.red(color) +
                    0.587 * Color.green(color) +
                    0.114 * Color.blue(color)) / 255

            val isLightBackground = luminance > 0.55

            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = isLightBackground
                isAppearanceLightNavigationBars = isLightBackground
            }

            if (luminance > 0.18) {
                window.statusBarColor = color
                window.navigationBarColor = color
            } else {
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying status bar color", e)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
    }

    private fun hasPermissions(permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isDestroyed) {
            try {
                webView?.onResume()
            } catch (e: Exception) {
                Log.e(TAG, "Error in onResume", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isDestroyed) {
            try {
                webView?.onPause()
            } catch (e: Exception) {
                Log.e(TAG, "Error in onPause", e)
            }
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        try {
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = null
            geolocationCallback?.invoke(geolocationOrigin, false, false)
            geolocationCallback = null
            pendingPermissionRequest?.deny()
            pendingPermissionRequest = null

            CookieManager.getInstance().flush()

            webView?.let { wv ->
                wv.loadUrl("about:blank")
                wv.onPause()
                wv.removeAllViews()
                container?.removeView(wv)
                wv.destroy()
            }
            webView = null
            loadingOverlay = null
            container = null
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
        super.onDestroy()
    }
}
