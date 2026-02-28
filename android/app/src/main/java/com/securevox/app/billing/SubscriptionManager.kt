package com.securevox.app.billing

import android.app.Activity
import android.util.Log
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.getOfferingsWith
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.purchaseWith
import com.revenuecat.purchases.restorePurchasesWith
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages subscription state and purchase operations via RevenueCat.
 * Uses Purchases.sharedInstance which is configured in SecureVoxApp.
 */
class SubscriptionManager private constructor() {

    companion object {
        private const val TAG = "SubscriptionManager"
        private const val ENTITLEMENT_ID = "premium"

        @Volatile
        private var instance: SubscriptionManager? = null

        fun getInstance(): SubscriptionManager {
            return instance ?: synchronized(this) {
                instance ?: SubscriptionManager().also {
                    instance = it
                }
            }
        }
    }

    private val _isSubscribed = MutableStateFlow(false)
    val isSubscribed: StateFlow<Boolean> = _isSubscribed.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Fetch initial customer info and offerings from RevenueCat.
     * Call this once after RevenueCat has been configured (e.g. from your main Activity).
     */
    fun initialize() {
        Log.d(TAG, "Initializing subscription manager")
        _isLoading.value = true
        checkSubscriptionStatus()
    }

    /**
     * Launch RevenueCat's purchase flow for a given package.
     *
     * @param activity The Activity launching the purchase (needed for Google Play billing UI)
     * @param packageToPurchase The RevenueCat Package to purchase
     */
    fun purchase(activity: Activity, packageToPurchase: Package) {
        _isLoading.value = true
        Purchases.sharedInstance.purchaseWith(
            purchaseParams = PurchaseParams.Builder(activity, packageToPurchase).build(),
            onError = { error, userCancelled ->
                _isLoading.value = false
                if (userCancelled) {
                    Log.d(TAG, "Purchase cancelled by user")
                } else {
                    Log.e(TAG, "Purchase error: ${error.message}")
                }
            },
            onSuccess = { _, customerInfo ->
                _isLoading.value = false
                updateSubscriptionState(customerInfo)
                Log.i(TAG, "Purchase successful")
            }
        )
    }

    /**
     * Restore previous purchases. Useful if the user reinstalls
     * or switches devices.
     */
    fun restorePurchases() {
        _isLoading.value = true
        Purchases.sharedInstance.restorePurchasesWith(
            onError = { error ->
                _isLoading.value = false
                Log.e(TAG, "Restore error: ${error.message}")
            },
            onSuccess = { customerInfo ->
                _isLoading.value = false
                updateSubscriptionState(customerInfo)
                Log.i(TAG, "Purchases restored")
            }
        )
    }

    /**
     * Fetch available offerings/packages from RevenueCat.
     *
     * @param onSuccess Called with the available Offerings
     * @param onError Called with the PurchasesError if the fetch fails
     */
    fun getOfferings(
        onSuccess: (Offerings) -> Unit,
        onError: (PurchasesError) -> Unit
    ) {
        Purchases.sharedInstance.getOfferingsWith(
            onError = { error ->
                Log.e(TAG, "Error fetching offerings: ${error.message}")
                onError(error)
            },
            onSuccess = { offerings ->
                Log.d(TAG, "Offerings fetched: ${offerings.current?.identifier}")
                onSuccess(offerings)
            }
        )
    }

    /**
     * Refresh the entitlement/subscription status from RevenueCat.
     */
    fun checkSubscriptionStatus() {
        Purchases.sharedInstance.getCustomerInfo(object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                _isLoading.value = false
                updateSubscriptionState(customerInfo)
            }

            override fun onError(error: PurchasesError) {
                _isLoading.value = false
                Log.e(TAG, "Error checking subscription status: ${error.message}")
            }
        })
    }

    /**
     * Update the local subscription state from a CustomerInfo object.
     */
    private fun updateSubscriptionState(customerInfo: CustomerInfo) {
        val entitlement = customerInfo.entitlements[ENTITLEMENT_ID]
        val subscribed = entitlement?.isActive == true
        _isSubscribed.value = subscribed
        Log.d(TAG, "Subscription active: $subscribed")
    }
}
