package com.snowsniw.reccore

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.snowsniw.reccore.data.RecPreferences

class RecCoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RecPreferences.init(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}

