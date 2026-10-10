package com.xmen12c.bgtube

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * A WebView that always reports itself visible, so the YouTube player inside it
 * keeps playing when the app is backgrounded or the screen is turned off.
 */
class BackgroundWebView(context: Context) : WebView(context) {

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, View.VISIBLE)
    }
}
