package com.neuralbridge.companion.screenshot

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * ScreenshotConsentActivity - No-op stub for X-NIX OS.
 * Direct shell capture (UID 2000) eliminates the need for MediaProjection consent dialogs.
 */
class ScreenshotConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }

    companion object {
        fun hasConsentResult(): Boolean = false
        fun consumeConsentResult(): Pair<Int, Intent?>? = null
    }
}
