package io.github.deveshb1.paradiseflac

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Optional tips through Google Play billing. Tips are consumable one-time products
 * (create them in Play Console with the IDs below); they unlock nothing.
 */
class TipJar(context: Context) : PurchasesUpdatedListener {

    data class Tip(val details: ProductDetails, val price: String, val priceMicros: Long)

    enum class Status { LOADING, READY, UNAVAILABLE }

    var status by mutableStateOf(Status.LOADING)
        private set
    var tips by mutableStateOf<List<Tip>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingResponseCode.OK) {
                    // No Play Store (e.g. sideloaded on a de-Googled phone) — just hide the tips.
                    main.post { status = Status.UNAVAILABLE }
                    return
                }
                loadTips()
                consumeLeftovers()
            }

            override fun onBillingServiceDisconnected() = Unit // auto-reconnection is enabled
        })
    }

    fun stop() = client.endConnection()

    fun tip(activity: Activity, tip: Tip) {
        message = null
        val product = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(tip.details)
            .apply { tip.details.oneTimePurchaseOfferDetails?.offerToken?.let(::setOfferToken) }
            .build()
        client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(product)).build())
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> purchases.orEmpty().forEach(::handle)
            BillingResponseCode.USER_CANCELED -> Unit
            else -> main.post { message = "The tip didn't go through — no charge was made." }
        }
    }

    private fun handle(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> consume(purchase) {
                main.post { message = "Thank you for the tip! 💛" }
            }
            Purchase.PurchaseState.PENDING -> main.post { message = "Payment pending — thank you! 💛" }
            else -> Unit
        }
    }

    private fun loadTips() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                PRODUCT_IDS.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                },
            )
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            val loaded = details.productDetailsList.mapNotNull { product ->
                product.oneTimePurchaseOfferDetails?.let { Tip(product, it.formattedPrice, it.priceAmountMicros) }
            }.sortedBy { it.priceMicros }
            main.post {
                tips = loaded
                status = if (result.responseCode == BillingResponseCode.OK && loaded.isNotEmpty()) Status.READY else Status.UNAVAILABLE
            }
        }
    }

    /** Tips paid while the app was closed (or pending ones that cleared) still need consuming, or Play refunds them. */
    private fun consumeLeftovers() {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingResponseCode.OK) return@queryPurchasesAsync
            purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { consume(it) {} }
        }
    }

    private fun consume(purchase: Purchase, onDone: () -> Unit) {
        val params = ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        client.consumeAsync(params) { result, _ ->
            if (result.responseCode == BillingResponseCode.OK) onDone()
        }
    }

    companion object {
        val PRODUCT_IDS = listOf("tip_small", "tip_medium", "tip_large")
    }
}
