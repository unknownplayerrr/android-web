package com.vauth.webviewapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * WebView app with floating navigation buttons.
 * 
 * Created by: https://github.com/vauth
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_URL = "https://example.com"
        private const val GITHUB_URL = "https://github.com/vauth/android-web"
    }

    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null
    private var container: FrameLayout? = null
    private var loadingOverlay: FrameLayout? = null
    private var fabGithub: FloatingActionButton? = null
    private var fabBack: FloatingActionButton? = null
    private var fabForward: FloatingActionButton? = null
    
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
                    // Multiple files selected
                    (0 until clipData.itemCount).map { 
                        clipData.getItemAt(it).uri 
                    }.toTypedArray()
                } else {
                    // Single file selected
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
            // Create the layout programmatically
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
            
            // Create progress bar
            progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    8
                ).apply {
                    gravity = Gravity.TOP
                }
                isIndeterminate = false
                max = 100
                progressDrawable?.setColorFilter(
                    ContextCompat.getColor(context, R.color.progressBarColor),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            }
            
            // Create loading overlay with dark background and modern spinner
            loadingOverlay = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(ContextCompat.getColor(context, R.color.loadingBackground))
                visibility = View.VISIBLE // Show initially while first page loads
                
                // Add circular progress indicator
                val loadingSpinner = ProgressBar(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER
                    }
                    indeterminateDrawable?.setColorFilter(
                        ContextCompat.getColor(context, R.color.progressBarColor),
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                }
                addView(loadingSpinner)
            }
            
            container?.addView(webView)
            container?.addView(progressBar)
            container?.addView(loadingOverlay)
            
            
            setContentView(container)
            
            // Setup back press handling after views are created
            setupBackPressHandler()
            
            setupWebView()
            
            // Load the configured website URL with validation
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
            val settings = webView?.settings
            if (settings == null) {
                Log.e(TAG, "WebView settings is null, cannot configure")
                return
            }
            
            settings.apply {
                // Enable JavaScript
                javaScriptEnabled = true
                
                // Enable DOM storage for persistent data
                domStorageEnabled = true
                
                // Enable database storage
                databaseEnabled = true
                
                // Allow file access
                allowFileAccess = true
                allowContentAccess = true
                
                // Enable zoom controls (hidden)
                builtInZoomControls = true
                displayZoomControls = false
                
                // Support viewport meta tag
                useWideViewPort = true
                loadWithOverviewMode = true
                
                // Enable media playback without gesture
                mediaPlaybackRequiresUserGesture = false
                
                // Enable mixed content
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                
                // Cache settings - use cache when available for better performance
                cacheMode = WebSettings.LOAD_DEFAULT
                
                // Enable geolocation
                setGeolocationEnabled(true)
                
                // Support multiple windows for popups
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                
                // Set user agent to indicate mobile app
                userAgentString = "$userAgentString WebViewApp/1.0"
            }
            
            // Enable cookie persistence for login sessions
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring WebView settings", e)
        }
        
        // Custom WebViewClient for handling navigation
        webView?.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                try {
                    super.onPageStarted(view, url, favicon)
                    progressBar?.visibility = View.VISIBLE
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onPageStarted", e)
                }
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                try {
                    super.onPageFinished(view, url)
                    progressBar?.visibility = View.GONE
                    // Hide loading overlay after first page loads
                    if (isFirstPageLoad) {
                        loadingOverlay?.visibility = View.GONE
                        isFirstPageLoad = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onPageFinished", e)
                }
            }
            
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                try {
                    val url = request?.url?.toString() ?: return false
                    
                    // Handle special URLs (tel:, mailto:, etc.)
                    if (url.startsWith("tel:") || url.startsWith("mailto:") || 
                        url.startsWith("sms:") || url.startsWith("whatsapp:")) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: Exception) {
                            Log.e(TAG, "Cannot open special link: $url", e)
                            Toast.makeText(this@MainActivity, "Cannot open this link", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                    
                    // Handle external app links
                    if (url.startsWith("intent:")) {
                        try {
                            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                            startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Cannot parse intent URL: $url", e)
                            // Fallback to browser
                        }
                        return true
                    }
                    
                    // Load URL in WebView
                    return false
                } catch (e: Exception) {
                    Log.e(TAG, "Error in shouldOverrideUrlLoading", e)
                    return false
                }
            }
            
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                try {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        // Show error for main frame only
                        progressBar?.visibility = View.GONE
                        // Hide loading overlay on error
                        if (isFirstPageLoad) {
                            loadingOverlay?.visibility = View.GONE
                            isFirstPageLoad = false
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Log.e(TAG, "WebView error: ${error?.description}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onReceivedError", e)
                }
            }
        }
        
        // Custom WebChromeClient for handling permissions, file uploads, etc.
        webView?.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                try {
                    progressBar?.progress = newProgress
                    if (newProgress == 100) {
                        progressBar?.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onProgressChanged", e)
                }
            }
            
            // Handle file uploads
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
            
            // Handle geolocation permission
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
            
            // Handle popups / new windows
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                try {
                    if (resultMsg == null) {
                        Log.w(TAG, "resultMsg is null in onCreateWindow")
                        return false
                    }
                    
                    val transport = resultMsg.obj as? WebView.WebViewTransport
                    if (transport == null) {
                        Log.w(TAG, "transport is null in onCreateWindow")
                        return false
                    }
                    
                    val newWebView = WebView(this@MainActivity)
                    transport.webView = newWebView
                    resultMsg.sendToTarget()
                    
                    newWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            try {
                                webView?.loadUrl(request?.url?.toString() ?: "")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error loading URL in popup", e)
                            }
                            return true
                        }
                    }
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating window", e)
                    return false
                }
            }
            
            // Handle fullscreen video
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
            
            // Handle permission requests (camera, microphone)
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
                            // No special permissions needed, grant the request
                            permRequest.grant(permRequest.resources)
                        } else if (hasPermissions(permissions.toTypedArray())) {
                            // Already have permissions, grant the request
                            permRequest.grant(permRequest.resources)
                        } else {
                            // Need to request permissions, store the request and wait for callback
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
            // Clear callbacks to prevent memory leaks
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = null
            geolocationCallback?.invoke(geolocationOrigin, false, false)
            geolocationCallback = null
            pendingPermissionRequest?.deny()
            pendingPermissionRequest = null
            
            // Save cookies before cleanup
            CookieManager.getInstance().flush()
            
            // Clean up WebView
            webView?.let { wv ->
                wv.loadUrl("about:blank")
                wv.onPause()
                wv.removeAllViews()
                // Remove from parent last, right before destroy
                container?.removeView(wv)
                wv.destroy()
            }
            webView = null
            progressBar = null
            loadingOverlay = null
            fabGithub = null
            fabBack = null
            fabForward = null
            container = null
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
        super.onDestroy()
    }
}
