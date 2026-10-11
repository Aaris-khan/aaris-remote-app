package com.aaris.remoteassist.webrtc

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** Instrumentation host, absent from the release APK and launcher. */
class LiveVideoTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
    }
}
