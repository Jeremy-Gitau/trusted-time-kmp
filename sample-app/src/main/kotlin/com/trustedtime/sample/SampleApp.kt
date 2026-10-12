package com.trustedtime.sample

import android.app.Application
import com.trustedtime.TrustedTimeInitializer

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TrustedTimeInitializer.init(this)
    }
}
