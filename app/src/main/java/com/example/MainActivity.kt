package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

  private lateinit var rootContainer: FrameLayout
  private lateinit var webView: WebView
  private lateinit var loadingProgress: ProgressBar
  private lateinit var initialSpinner: ProgressBar
  private lateinit var errorContainer: View
  private lateinit var btnRetry: Button

  private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
  private var customView: View? = null
  private var customViewCallback: WebChromeClient.CustomViewCallback? = null
  private var hasLoadError = false

  private val fileChooserLauncher =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileUploadCallback
        fileUploadCallback = null
        if (callback == null) return@registerForActivityResult

        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
          val data = result.data
          val clipData = data?.clipData
          val singleUri = data?.data

          val results: Array<Uri>? =
              when {
                clipData != null -> {
                  Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                }
                singleUri != null -> {
                  arrayOf(singleUri)
                }
                else -> null
              }
          callback.onReceiveValue(results)
        } else {
          callback.onReceiveValue(null)
        }
      }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    rootContainer = findViewById(R.id.root_container)
    webView = findViewById(R.id.web_view)
    loadingProgress = findViewById(R.id.loading_progress)
    initialSpinner = findViewById(R.id.initial_spinner)
    errorContainer = findViewById(R.id.error_container)
    btnRetry = findViewById(R.id.btn_retry)

    setupBackNavigation()
    setupWebViewSettings()
    setupCookies()
    setupDownloadListener()
    setupWebClients()

    btnRetry.setOnClickListener {
      hasLoadError = false
      errorContainer.visibility = View.GONE
      webView.visibility = View.VISIBLE
      initialSpinner.visibility = View.VISIBLE
      webView.loadUrl(TARGET_URL)
    }

    if (savedInstanceState != null) {
      webView.restoreState(savedInstanceState)
    } else {
      webView.loadUrl(TARGET_URL)
    }
  }

  private fun setupBackNavigation() {
    onBackPressedDispatcher.addCallback(
        this,
        object : OnBackPressedCallback(true) {
          override fun handleOnBackPressed() {
            if (customView != null) {
              hideCustomView()
            } else if (webView.canGoBack()) {
              webView.goBack()
            } else {
              isEnabled = false
              onBackPressedDispatcher.onBackPressed()
            }
          }
        })
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun setupWebViewSettings() {
    webView.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      databaseEnabled = true
      setSupportZoom(true)
      builtInZoomControls = true
      displayZoomControls = false
      loadWithOverviewMode = true
      useWideViewPort = true
      cacheMode = WebSettings.LOAD_DEFAULT
      allowFileAccess = true
      allowContentAccess = true
      mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
      mediaPlaybackRequiresUserGesture = false
    }
  }

  private fun setupCookies() {
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookieManager.setAcceptThirdPartyCookies(webView, true)
  }

  private fun setupDownloadListener() {
    webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
      try {
        val uri = Uri.parse(url)
        val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
        val request =
            DownloadManager.Request(uri).apply {
              setMimeType(mimetype)
              val cookies = CookieManager.getInstance().getCookie(url)
              if (!cookies.isNullOrEmpty()) {
                addRequestHeader("Cookie", cookies)
              }
              if (!userAgent.isNullOrEmpty()) {
                addRequestHeader("User-Agent", userAgent)
              }
              setDescription(filename)
              setTitle(filename)
              setNotificationVisibility(
                  DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
              setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        dm?.enqueue(request)
        Toast.makeText(this, getString(R.string.download_started), Toast.LENGTH_SHORT).show()
      } catch (e: Exception) {
        try {
          val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
          startActivity(intent)
        } catch (ignored: Exception) {
          Toast.makeText(this, "Unable to start download", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  private fun setupWebClients() {
    webView.webViewClient =
        object : WebViewClient() {
          override fun shouldOverrideUrlLoading(
              view: WebView?,
              request: WebResourceRequest?
          ): Boolean {
            val uri = request?.url ?: return false
            val host = uri.host?.lowercase() ?: ""
            val scheme = uri.scheme?.lowercase() ?: ""

            if (scheme == "https" || scheme == "http") {
              if (host == TARGET_HOST || host.endsWith(".$TARGET_HOST")) {
                return false
              }
              return try {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
                true
              } catch (e: Exception) {
                false
              }
            }

            return try {
              val intent = Intent(Intent.ACTION_VIEW, uri)
              startActivity(intent)
              true
            } catch (e: Exception) {
              false
            }
          }

          override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            hasLoadError = false
            loadingProgress.visibility = View.VISIBLE
            errorContainer.visibility = View.GONE
          }

          override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            initialSpinner.visibility = View.GONE
            loadingProgress.visibility = View.GONE
            if (!hasLoadError) {
              errorContainer.visibility = View.GONE
              webView.visibility = View.VISIBLE
            }
          }

          override fun onReceivedError(
              view: WebView?,
              request: WebResourceRequest?,
              error: WebResourceError?
          ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
              hasLoadError = true
              showErrorScreen()
            }
          }

          override fun onReceivedSslError(
              view: WebView?,
              handler: SslErrorHandler?,
              error: SslError?
          ) {
            // Strict HTTPS security requirement:
            // Do NOT bypass certificate errors. Do NOT use handler.proceed().
            handler?.cancel()
            hasLoadError = true
            showErrorScreen()
          }

          override fun onReceivedHttpAuthRequest(
              view: WebView?,
              handler: HttpAuthHandler?,
              host: String?,
              realm: String?
          ) {
            if (isFinishing || isDestroyed) {
              handler?.cancel()
              return
            }

            val savedAuth = view?.getHttpAuthUsernamePassword(host ?: "", realm ?: "")
            if (savedAuth != null && savedAuth.size == 2 && savedAuth[0].isNotEmpty()) {
              handler?.proceed(savedAuth[0], savedAuth[1])
              return
            }

            val layout = LinearLayout(this@MainActivity).apply {
              orientation = LinearLayout.VERTICAL
              setPadding(64, 32, 64, 16)
            }

            val usernameInput = EditText(this@MainActivity).apply {
              hint = "Username"
              setSingleLine(true)
            }
            val passwordInput = EditText(this@MainActivity).apply {
              hint = "Password"
              inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
              setSingleLine(true)
            }

            layout.addView(usernameInput)
            layout.addView(passwordInput)

            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.app_name))
                .setMessage("Authentication required for ${host ?: "server"}")
                .setView(layout)
                .setCancelable(false)
                .setPositiveButton("Sign In") { _, _ ->
                  val username = usernameInput.text.toString().trim()
                  val password = passwordInput.text.toString()
                  view?.setHttpAuthUsernamePassword(host ?: "", realm ?: "", username, password)
                  handler?.proceed(username, password)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                  handler?.cancel()
                }
                .show()
          }
        }

    webView.webChromeClient =
        object : WebChromeClient() {
          override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            loadingProgress.progress = newProgress
            if (newProgress >= 100) {
              loadingProgress.visibility = View.GONE
              initialSpinner.visibility = View.GONE
            } else {
              loadingProgress.visibility = View.VISIBLE
            }
          }

          override fun onShowFileChooser(
              webView: WebView?,
              filePathCallback: ValueCallback<Array<Uri>>?,
              fileChooserParams: FileChooserParams?
          ): Boolean {
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = filePathCallback

            val intent =
                try {
                  fileChooserParams?.createIntent()
                      ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                      }
                } catch (e: Exception) {
                  Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                  }
                }

            if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
              intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }

            return try {
              val chooser =
                  Intent.createChooser(intent, getString(R.string.file_chooser_title))
              fileChooserLauncher.launch(chooser)
              true
            } catch (e: Exception) {
              fileUploadCallback?.onReceiveValue(null)
              fileUploadCallback = null
              false
            }
          }

          override fun onJsAlert(
              view: WebView?,
              url: String?,
              message: String?,
              result: JsResult?
          ): Boolean {
            if (isFinishing || isDestroyed) {
              result?.cancel()
              return true
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.app_name))
                .setMessage(message ?: "")
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                .setOnCancelListener { result?.cancel() }
                .show()
            return true
          }

          override fun onJsConfirm(
              view: WebView?,
              url: String?,
              message: String?,
              result: JsResult?
          ): Boolean {
            if (isFinishing || isDestroyed) {
              result?.cancel()
              return true
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.app_name))
                .setMessage(message ?: "")
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                .setOnCancelListener { result?.cancel() }
                .show()
            return true
          }

          override fun onJsPrompt(
              view: WebView?,
              url: String?,
              message: String?,
              defaultValue: String?,
              result: JsPromptResult?
          ): Boolean {
            if (isFinishing || isDestroyed) {
              result?.cancel()
              return true
            }
            val input =
                EditText(this@MainActivity).apply {
                  setText(defaultValue ?: "")
                  setSingleLine(true)
                }
            val container =
                FrameLayout(this@MainActivity).apply {
                  setPadding(48, 16, 48, 16)
                  addView(input)
                }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.app_name))
                .setMessage(message ?: "")
                .setView(container)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                  result?.confirm(input.text.toString())
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                .setOnCancelListener { result?.cancel() }
                .show()
            return true
          }

          override fun onShowCustomView(
              view: View?,
              callback: CustomViewCallback?
          ) {
            if (customView != null) {
              callback?.onCustomViewHidden()
              return
            }
            customView = view
            customViewCallback = callback
            webView.visibility = View.GONE
            rootContainer.addView(
                customView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT))
          }

          override fun onHideCustomView() {
            hideCustomView()
          }
        }
  }

  private fun hideCustomView() {
    val cv = customView ?: return
    rootContainer.removeView(cv)
    customView = null
    customViewCallback?.onCustomViewHidden()
    customViewCallback = null
    webView.visibility = View.VISIBLE
  }

  private fun showErrorScreen() {
    webView.visibility = View.GONE
    initialSpinner.visibility = View.GONE
    loadingProgress.visibility = View.GONE
    errorContainer.visibility = View.VISIBLE
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    webView.saveState(outState)
  }

  override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    super.onRestoreInstanceState(savedInstanceState)
    webView.restoreState(savedInstanceState)
  }

  override fun onResume() {
    super.onResume()
    webView.onResume()
  }

  override fun onPause() {
    super.onPause()
    webView.onPause()
    CookieManager.getInstance().flush()
  }

  override fun onDestroy() {
    CookieManager.getInstance().flush()
    webView.apply {
      stopLoading()
      clearHistory()
      loadUrl("about:blank")
      onPause()
      removeAllViews()
      destroy()
    }
    super.onDestroy()
  }

  companion object {
    private const val TARGET_URL = "https://assessmentstudio.ddns.net/"
    private const val TARGET_HOST = "assessmentstudio.ddns.net"
  }
}

