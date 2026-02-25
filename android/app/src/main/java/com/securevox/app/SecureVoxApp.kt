package com.securevox.app

import android.app.Application
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.whisper.ModelManager

class SecureVoxApp : Application() {

    val database: SecureVoxDatabase by lazy {
        SecureVoxDatabase.getInstance(this)
    }

    val modelManager: ModelManager by lazy {
        ModelManager(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        initRevenueCat()
    }

    private fun initRevenueCat() {
        Purchases.logLevel = LogLevel.DEBUG
        Purchases.configure(
            PurchasesConfiguration.Builder(this, REVENUECAT_API_KEY).build()
        )
    }

    companion object {
        lateinit var instance: SecureVoxApp
            private set

        // TODO: Replace with your actual RevenueCat API key
        private const val REVENUECAT_API_KEY = "YOUR_REVENUECAT_API_KEY"
    }
}
