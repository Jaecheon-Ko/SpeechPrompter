package com.prompter.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : ComponentActivity() {

    companion object {
        const val APP_URL = "https://voice-prompter-kr.lovable.app/"
        private const val APP_HOST_SUFFIX = "lovable.app"
        private const val OFFLINE_URL = "file:///android_asset/offline.html"

        /** 하드웨어 뒤로가기: 프롬프터 화면이면 "← 목록" 버튼을 눌러준다 */
        private const val BACK_JS = """
            (function(){
              var bs = document.querySelectorAll('button');
              for (var i = 0; i < bs.length; i++) {
                if ((bs[i].textContent || '').trim().indexOf('← 목록') === 0) { bs[i].click(); return true; }
              }
              return false;
            })()
        """
    }

    private lateinit var webView: WebView
    private lateinit var speech: SpeechBridge
    private lateinit var polyfill: String

    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = fileCallback
            fileCallback = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            speech.onPermissionResult(granted)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(webView)
        hideSystemBars()

        polyfill = assets.open("speech-polyfill.js").bufferedReader().use { it.readText() }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // 저장한 대본(localStorage) 유지
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100                    // 시스템 글꼴 크기에 레이아웃이 깨지지 않게
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            cacheMode = if (isOnline()) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
        }

        speech = SpeechBridge(this, webView) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (speech.isAvailable) {
            webView.addJavascriptInterface(speech.jsApi, "AndroidSpeech")
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, polyfill, setOf("*"))
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host.orEmpty()
                if (uri.scheme == "file" || host == APP_HOST_SUFFIX || host.endsWith(".$APP_HOST_SUFFIX")) {
                    return false
                }
                // 외부 링크는 기본 브라우저로
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                // 문서 시작 스크립트를 지원하지 않는 구형 WebView 대비 (중복 실행은 스크립트가 막음)
                if (speech.isAvailable) view.evaluateJavascript(polyfill, null)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame && request.url.scheme != "file") {
                    view.loadUrl(OFFLINE_URL)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf(
                            "text/*",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/octet-stream",
                        ),
                    )
                }
                return try {
                    filePicker.launch(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage(message)
                    .setPositiveButton("확인") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage(message)
                    .setPositiveButton("확인") { _, _ -> result.confirm() }
                    .setNegativeButton("취소") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            // 웹 쪽 getUserMedia 는 쓰지 않는다 (음성은 네이티브 브리지로 처리)
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(BACK_JS) { r ->
                    if (r == "true") return@evaluateJavascript
                    if (webView.canGoBack()) webView.goBack() else finish()
                }
            }
        })

        if (savedInstanceState == null) webView.loadUrl(APP_URL)
        else webView.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        webView.onResume()
        speech.onHostResume()
    }

    override fun onPause() {
        speech.onHostPause()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        speech.destroy()
        webView.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
