package com.androidsurround.ui

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun BrowserSheet(
    onDismiss: () -> Unit,
    onUrlSelected: (String) -> Unit,
) {
    var url by remember { mutableStateOf("https://www.youtube.com") }
    var currentUrl by remember { mutableStateOf("https://www.youtube.com") }
    var isLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (showControls) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { webView?.goBack() }) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                    IconButton(onClick = { webView?.goForward() }) {
                        Icon(Icons.Filled.ArrowForward, "Forward")
                    }
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Filled.Refresh, "Reload")
                    }

                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )

                    IconButton(onClick = {
                        currentUrl = url.let {
                            if (it.startsWith("http")) it else "https://$it"
                        }
                        webView?.loadUrl(currentUrl)
                    }) {
                        Icon(Icons.Filled.Search, "Go")
                    }

                    IconButton(onClick = {
                        isFullscreen = !isFullscreen
                        showControls = !isFullscreen
                    }) {
                        Icon(
                            if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            if (isFullscreen) "Exit Fullscreen" else "Fullscreen"
                        )
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, newUrl: String?, favicon: Bitmap?) {
                                    isLoading = true
                                    newUrl?.let { currentUrl = it; url = it }
                                }
                                override fun onPageFinished(view: WebView?, newUrl: String?) {
                                    isLoading = false
                                    newUrl?.let { currentUrl = it; url = it }
                                }
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?, request: WebResourceRequest?
                                ): Boolean {
                                    val reqUrl = request?.url?.toString()
                                    if (reqUrl != null) { currentUrl = reqUrl; url = reqUrl }
                                    return false
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    isLoading = newProgress < 100
                                }
                                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                    isFullscreen = true
                                    showControls = false
                                }
                                override fun onHideCustomView() {
                                    isFullscreen = false
                                    showControls = true
                                }
                            }

                            loadUrl(currentUrl)
                            webView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        if (view.url != currentUrl && currentUrl.isNotEmpty()) {
                            view.loadUrl(currentUrl)
                        }
                    },
                )
            }

            if (showControls) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Close Browser")
                    }
                    Button(onClick = { onUrlSelected(currentUrl) }) {
                        Icon(Icons.Filled.Link, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Play Audio")
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
