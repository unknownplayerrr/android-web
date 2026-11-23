
package com.vauth.webviewapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_URL = "https://example.com"

        // Glass status bar color
        private const val STATUS_BAR_COLOR = "#660A0014"
    }

    private var webView: WebView? = null
    private var container: FrameLayout? = null
    private var loadingOverlay: FrameLayout? = null
    private var statusBarOverlay: View? = null

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    private var geolocationCallback: GeolocationPermissions.Callback? = null
    private var geolocationOrigin: String? = null

    private var pendingPermissionRequest: PermissionRequest? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var isDestroyed = false
    private var isFirstPageLoad = true

    // =========================================================
    // FILE CHOOSER
    // =========================================================

    private val fileChooserLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            val callback = fileUploadCallback
            fileUploadCallback = null

            try {
                if (result.resultCode == Activity.RESULT_OK) {

                    val data = result.data
                    val clipData = data?.clipData

                    val uris = when {
                        clipData != null -> {
                            (0 until clipData.itemCount)
                                .map { clipData.getItemAt(it).uri }
                                .toTypedArray()
                        }

                        data?.data != null -> {
                            arrayOf(data.data!!)
                        }

                        cameraImageUri != null -> {
                            arrayOf(cameraImageUri!!)
                        }

                        else -> emptyArray()
                    }

                    callback?.onReceiveValue(
                        uris.takeIf { it.isNotEmpty() }
                    )

                } else {
                    callback?.onReceiveValue(null)
                }

            } catch (e: Exception) {
                Log.e(TAG, "File chooser error", e)
                callback?.onReceiveValue(null)

            } finally {
                if (result.resultCode != Activity.RESULT_OK) {
                    cameraImageUri = null
                }
            }
        }

    // =========================================================
    // GEOLOCATION PERMISSIONS
    // =========================================================

    private val geolocationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val granted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            geolocationCallback?.invoke(
                geolocationOrigin,
                granted,
                false
            )

            geolocationCallback = null
            geolocationOrigin = null
        }

    // =========================================================
    // CAMERA AND MICROPHONE PERMISSIONS
    // =========================================================

    private val mediaPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val request = pendingPermissionRequest
            pendingPermissionRequest = null

            try {
                if (request == null) return@registerForActivityResult

                val allGranted = permissions.values.all { it }

                if (allGranted) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Media permission error", e)
                request?.deny()
            }
        }

    // =========================================================
    // ACTIVITY CREATION
    // =========================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setupEdgeToEdge()
            createLayout()

            setupInsets()
            setupBackPressHandler()
            setupWebView()

            val websiteUrl = BuildConfig.WEBSITE_URL
                .trim()
                .ifBlank { DEFAULT_URL }

            webView?.loadUrl(websiteUrl)

        } catch (e: Exception) {
            Log.e(TAG, "Initialization error", e)

            Toast.makeText(
                this,
                "Failed to initialize app",
                Toast.LENGTH_LONG
            ).show()

            finish()
        }
    }

    // =========================================================
    // EDGE-TO-EDGE
    // =========================================================

    private fun setupEdgeToEdge() {

        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes =
                window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
        }

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        WindowInsetsControllerCompat(
            window,
            window.decorView
        ).apply {

            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    // =========================================================
    // CREATE APP LAYOUT
    // =========================================================

    private fun createLayout() {

        container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // WebView
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            setBackgroundColor(Color.TRANSPARENT)
        }

        // Status bar glass overlay
        statusBarOverlay = View(this).apply {

            setBackgroundColor(
                Color.parseColor(STATUS_BAR_COLOR)
            )

            elevation =
                10f * resources.displayMetrics.density

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                Gravity.TOP
            )
        }

        // Splash screen
        loadingOverlay = FrameLayout(this).apply {

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            setBackgroundColor(
                Color.parseColor("#0A0014")
            )

            visibility = View.VISIBLE

            val iconSize =
                (88 * resources.displayMetrics.density).toInt()

            val appIcon = ImageView(context).apply {

                layoutParams = FrameLayout.LayoutParams(
                    iconSize,
                    iconSize,
                    Gravity.CENTER
                )

                try {
                    setImageDrawable(
                        packageManager.getApplicationIcon(applicationInfo)
                    )
                } catch (e: Exception) {
                    setImageResource(
                        android.R.drawable.sym_def_app_icon
                    )
                }

                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            addView(appIcon)
        }

        // Layer order matters
        container?.apply {

            addView(webView)
            addView(statusBarOverlay)
            addView(loadingOverlay)
        }

        setContentView(container)
    }

    // =========================================================
    // SYSTEM BAR INSETS
    // =========================================================

    private fun setupInsets() {

        container?.let { root ->

            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->

                val statusBars = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars()
                )

                val navigationBars = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars()
                )

                val density =
                    resources.displayMetrics.density

                // Extra space below time/network/battery icons
                val extraTop = (6 * density).toInt()

                // Keep website controls clear of system UI
                webView?.setPadding(
                    0,
                    statusBars.top + extraTop,
                    0,
                    navigationBars.bottom
                )

                // Dynamic status bar height
                statusBarOverlay?.layoutParams =
                    statusBarOverlay?.layoutParams?.apply {
                        height = statusBars.top
                    }

                insets
            }

            ViewCompat.requestApplyInsets(root)
        }
    }

    // =========================================================
    // BACK BUTTON
    // =========================================================

    private fun setupBackPressHandler() {

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {

                override fun handleOnBackPressed() {

                    try {

                        if (customView != null) {
                            hideCustomView()
                            return
                        }

                        if (webView?.canGoBack() == true) {
                            webView?.goBack()
                        } else {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Back navigation error", e)

                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    // =========================================================
    // WEBVIEW CONFIGURATION
    // =========================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {

        val wv = webView ?: return

        try {

            wv.settings.apply {

                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true

                allowFileAccess = false
                allowContentAccess = true

                builtInZoomControls = true
                displayZoomControls = false

                useWideViewPort = true
                loadWithOverviewMode = true

                mediaPlaybackRequiresUserGesture = true

                mixedContentMode =
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW

                cacheMode = WebSettings.LOAD_DEFAULT

                setGeolocationEnabled(true)

                // Popup windows are disabled for security
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false

                userAgentString =
                    "$userAgentString WebViewApp/1.0"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }
            }

            CookieManager.getInstance().apply {

                setAcceptCookie(true)

                setAcceptThirdPartyCookies(
                    wv,
                    true
                )
            }

            WebView.setWebContentsDebuggingEnabled(
                BuildConfig.DEBUG
            )

        } catch (e: Exception) {
            Log.e(TAG, "WebView settings error", e)
        }

        setupDownloadListener(wv)
        setupWebViewClient(wv)
        setupWebChromeClient(wv)
    }

    // =========================================================
    // DOWNLOAD MANAGER
    // =========================================================

    private fun setupDownloadListener(wv: WebView) {

        wv.setDownloadListener {
                url,
                userAgent,
                contentDisposition,
                mimeType,
                _ ->

            try {

                val uri = Uri.parse(url)

                if (uri.scheme != "https" && uri.scheme != "http") {
                    Toast.makeText(
                        this,
                        "Unsupported download URL",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setDownloadListener
                }

                val fileName = URLUtil.guessFileName(
                    url,
                    contentDisposition,
                    mimeType
                ).replace(Regex("[/\\\\]"), "_")

                val request = DownloadManager.Request(uri)

                request.apply {

                    setMimeType(mimeType)

                    addRequestHeader(
                        "User-Agent",
                        userAgent
                    )

                    CookieManager.getInstance()
                        .getCookie(url)
                        ?.let {
                            addRequestHeader("Cookie", it)
                        }

                    setDescription("Downloading file...")
                    setTitle(fileName)

                    setNotificationVisibility(
                        DownloadManager.Request
                            .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )

                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName
                    )

                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }

                val manager =
                    getSystemService(DOWNLOAD_SERVICE) as DownloadManager

                manager.enqueue(request)

                Toast.makeText(
                    this,
                    "Downloading: $fileName",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {

                Log.e(TAG, "Download error", e)

                Toast.makeText(
                    this,
                    "Download failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // =========================================================
    // WEBVIEW CLIENT
    // =========================================================

    private fun setupWebViewClient(wv: WebView) {

        wv.webViewClient = object : WebViewClient() {

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                super.onPageFinished(view, url)

                if (isFirstPageLoad) {
                    isFirstPageLoad = false
                    fadeOutLoadingOverlay()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {

                val url = request?.url ?: return false

                return handleExternalUrl(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)

                if (request?.isForMainFrame == true && isFirstPageLoad) {
                    isFirstPageLoad = false
                    fadeOutLoadingOverlay()
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(
                    view,
                    request,
                    errorResponse
                )

                if (
                    request?.isForMainFrame == true &&
                    isFirstPageLoad
                ) {
                    isFirstPageLoad = false
                    fadeOutLoadingOverlay()
                }
            }
        }
    }

    // =========================================================
    // EXTERNAL URL HANDLING
    // =========================================================

    private fun handleExternalUrl(uri: Uri): Boolean {

        val scheme = uri.scheme?.lowercase() ?: return false

        when (scheme) {

            "http", "https" -> return false

            "tel", "mailto", "sms", "smsto", "whatsapp" -> {

                try {

                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            uri
                        )
                    )

                } catch (e: Exception) {

                    Toast.makeText(
                        this,
                        "No application can open this link",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                return true
            }

            "intent" -> {

                try {

                    val intent = Intent.parseUri(
                        uri.toString(),
                        Intent.URI_INTENT_SCHEME
                    )

                    val fallback =
                        intent.getStringExtra("browser_fallback_url")

                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    } else if (!fallback.isNullOrBlank()) {
                        webView?.loadUrl(fallback)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Intent URL error", e)
                }

                return true
            }

            else -> return true
        }
    }

    // =========================================================
    // WEB CHROME CLIENT
    // =========================================================

    private fun setupWebChromeClient(wv: WebView) {

        wv.webChromeClient = object : WebChromeClient() {

            // -------------------------------------------------
            // FILE UPLOAD + CAMERA
            // -------------------------------------------------

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {

                try {

                    fileUploadCallback?.onReceiveValue(null)

                    fileUploadCallback = filePathCallback

                    val acceptTypes =
                        fileChooserParams?.acceptTypes
                            ?.filter { it.isNotBlank() }
                            ?: emptyList()

                    val allowMultiple =
                        fileChooserParams?.mode ==
                            FileChooserParams.MODE_OPEN_MULTIPLE

                    val mimeType = when {

                        acceptTypes.isEmpty() -> "*/*"

                        acceptTypes.size == 1 -> acceptTypes.first()

                        else -> "*/*"
                    }

                    val contentIntent = Intent(
                        Intent.ACTION_GET_CONTENT
                    ).apply {

                        addCategory(Intent.CATEGORY_OPENABLE)

                        type = mimeType

                        if (acceptTypes.size > 1) {
                            putExtra(
                                Intent.EXTRA_MIME_TYPES,
                                acceptTypes.toTypedArray()
                            )
                        }

                        putExtra(
                            Intent.EXTRA_ALLOW_MULTIPLE,
                            allowMultiple
                        )
                    }

                    val isImageUpload =
                        acceptTypes.isEmpty() ||
                        acceptTypes.any {
                            it.startsWith("image/") ||
                            it == "*/*"
                        }

                    if (isImageUpload) {

                        val cameraFile = File.createTempFile(
                            "camera_${System.currentTimeMillis()}_",
                            ".jpg",
                            cacheDir
                        )

                        cameraImageUri = FileProvider.getUriForFile(
                            this@MainActivity,
                            "$packageName.fileprovider",
                            cameraFile
                        )

                        val cameraIntent = Intent(
                            MediaStore.ACTION_IMAGE_CAPTURE
                        ).apply {

                            putExtra(
                                MediaStore.EXTRA_OUTPUT,
                                cameraImageUri
                            )

                            addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        }

                        val chooser = Intent.createChooser(
                            contentIntent,
                            "Select or capture image"
                        )

                        chooser.putExtra(
                            Intent.EXTRA_INITIAL_INTENTS,
                            arrayOf(cameraIntent)
                        )

                        fileChooserLauncher.launch(chooser)

                    } else {

                        fileChooserLauncher.launch(
                            Intent.createChooser(
                                contentIntent,
                                "Select file"
                            )
                        )
                    }

                    return true

                } catch (e: Exception) {

                    Log.e(TAG, "File chooser error", e)

                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null

                    cameraImageUri = null

                    return false
                }
            }

            // -------------------------------------------------
            // GEOLOCATION
            // -------------------------------------------------

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {

                if (
                    origin == null ||
                    !isTrustedOrigin(origin)
                ) {
                    callback?.invoke(origin, false, false)
                    return
                }

                geolocationOrigin = origin
                geolocationCallback = callback

                val permissions = arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )

                if (hasPermissions(permissions)) {

                    callback?.invoke(origin, true, false)

                    geolocationCallback = null
                    geolocationOrigin = null

                } else {

                    geolocationPermissionLauncher.launch(
                        permissions
                    )
                }
            }

            // -------------------------------------------------
            // CAMERA AND MICROPHONE
            // -------------------------------------------------

            override fun onPermissionRequest(
                request: PermissionRequest?
            ) {

                if (request == null) return

                runOnUiThread {

                    try {

                        val origin =
                            request.origin.toString()

                        if (!isTrustedOrigin(origin)) {
                            request.deny()
                            return@runOnUiThread
                        }

                        val permissions = mutableListOf<String>()

                        for (resource in request.resources) {

                            when (resource) {

                                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                                    permissions.add(
                                        Manifest.permission.CAMERA
                                    )
                                }

                                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                                    permissions.add(
                                        Manifest.permission.RECORD_AUDIO
                                    )
                                }

                                else -> {
                                    request.deny()
                                    return@runOnUiThread
                                }
                            }
                        }

                        if (permissions.isEmpty()) {
                            request.grant(request.resources)
                            return@runOnUiThread
                        }

                        if (hasPermissions(permissions.toTypedArray())) {

                            request.grant(request.resources)

                        } else {

                            pendingPermissionRequest = request

                            mediaPermissionLauncher.launch(
                                permissions.distinct().toTypedArray()
                            )
                        }

                    } catch (e: Exception) {

                        Log.e(TAG, "Web permission error", e)
                        request.deny()
                    }
                }
            }

            // -------------------------------------------------
            // FULLSCREEN VIDEO
            // -------------------------------------------------

            override fun onShowCustomView(
                view: View?,
                callback: CustomViewCallback?
            ) {

                if (view == null) return

                customView = view
                customViewCallback = callback

                webView?.visibility = View.GONE

                container?.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )

                view.bringToFront()

                WindowInsetsControllerCompat(
                    window,
                    window.decorView
                ).hide(
                    WindowInsetsCompat.Type.systemBars()
                )
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }
    }

    // =========================================================
    // FULLSCREEN CLEANUP
    // =========================================================

    private fun hideCustomView() {

        try {

            customView?.let {
                container?.removeView(it)
            }

            customView = null

            webView?.visibility = View.VISIBLE

            customViewCallback?.onCustomViewHidden()
            customViewCallback = null

            WindowInsetsControllerCompat(
                window,
                window.decorView
            ).show(
                WindowInsetsCompat.Type.systemBars()
            )

        } catch (e: Exception) {
            Log.e(TAG, "Fullscreen cleanup error", e)
        }
    }

    // =========================================================
    // TRUSTED WEBSITE CHECK
    // =========================================================

    private fun isTrustedOrigin(origin: String): Boolean {

        return try {

            val requestedHost =
                Uri.parse(origin).host ?: return false

            val trustedHost =
                Uri.parse(BuildConfig.WEBSITE_URL).host
                    ?: return false

            requestedHost.equals(
                trustedHost,
                ignoreCase = true
            )

        } catch (e: Exception) {
            false
        }
    }

    // =========================================================
    // PERMISSION CHECK
    // =========================================================

    private fun hasPermissions(
        permissions: Array<String>
    ): Boolean {

        return permissions.all {

            ContextCompat.checkSelfPermission(
                this,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // =========================================================
    // SPLASH SCREEN ANIMATION
    // =========================================================

    private fun fadeOutLoadingOverlay() {

        val overlay = loadingOverlay ?: return

        if (overlay.visibility != View.VISIBLE) return

        val animation = AlphaAnimation(1f, 0f).apply {

            duration = 400
            fillAfter = false

            setAnimationListener(
                object : android.view.animation.Animation.AnimationListener {

                    override fun onAnimationStart(
                        animation: android.view.animation.Animation?
                    ) {}

                    override fun onAnimationRepeat(
                        animation: android.view.animation.Animation?
                    ) {}

                    override fun onAnimationEnd(
                        animation: android.view.animation.Animation?
                    ) {

                        overlay.visibility = View.GONE
                        overlay.clearAnimation()
                    }
                }
            )
        }

        overlay.startAnimation(animation)
    }

    // =========================================================
    // LIFECYCLE
    // =========================================================

    override fun onResume() {
        super.onResume()

        if (!isDestroyed) {
            webView?.onResume()
        }
    }

    override fun onPause() {

        webView?.onPause()

        super.onPause()
    }

    override fun onDestroy() {

        isDestroyed = true

        try {

            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = null

            geolocationCallback?.invoke(
                geolocationOrigin,
                false,
                false
            )

            geolocationCallback = null
            geolocationOrigin = null

            pendingPermissionRequest?.deny()
            pendingPermissionRequest = null

            CookieManager.getInstance().flush()

            hideCustomView()

            webView?.let { wv ->

                wv.stopLoading()
                wv.loadUrl("about:blank")

                wv.webChromeClient = null
                wv.webViewClient = WebViewClient()

                wv.onPause()
                wv.removeAllViews()

                container?.removeView(wv)

                wv.destroy()
            }

            webView = null
            statusBarOverlay = null
            loadingOverlay = null
            container = null

        } catch (e: Exception) {

            Log.e(TAG, "Destroy error", e)
        }

        super.onDestroy()
    }
}
