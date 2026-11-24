package com.alglucomon.android

import android.app.Application
import com.alglucomon.android.data.GlucoseStore
import com.alglucomon.android.data.SettingsRepository

class AlGlucoMonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsRepository.init(this)
        GlucoseStore.start()
    }
}
