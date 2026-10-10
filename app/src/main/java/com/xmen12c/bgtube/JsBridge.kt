package com.xmen12c.bgtube

import android.webkit.JavascriptInterface

/**
 * Bridge called from the player HTML running inside the WebView.
 * Callbacks arrive on a JS thread; the service marshals them to the main thread.
 */
class JsBridge(
    private val stateCallback: (state: Int, title: String) -> Unit,
    private val errorCallback: (code: Int) -> Unit,
) {
    @JavascriptInterface
    fun onState(state: Int, title: String) = stateCallback(state, title)

    @JavascriptInterface
    fun onError(code: Int) = errorCallback(code)
}
