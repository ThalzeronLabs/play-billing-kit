package com.thalzeronlabs.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingFlowParams.SubscriptionUpdateParams.ReplacementMode
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** [BillingGateway] backed by the real Google Play Billing Library 8. */
class PlayBillingGateway(context: Context) : BillingGateway {

    private val updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val purchaseUpdates: Flow<PurchaseUpdate> = updates.asSharedFlow()

    /** Play needs the original ProductDetails object to launch a purchase, so the last query is kept here. */
    private val details = ConcurrentHashMap<String, ProductDetails>()

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            updates.tryEmit(PurchaseUpdate(result.status(), purchases.orEmpty().map { it.toOwned() }))
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override suspend fun connect(): BillingStatus {
        if (client.isReady) return BillingStatus.OK
        return suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.status())
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(BillingStatus.SERVICE_DISCONNECTED)
                }
            })
        }
    }

    override suspend fun queryProducts(productIds: List<String>): Pair<BillingStatus, List<SubscriptionProduct>> {
        val (status, found) = queryDetails(productIds, ProductType.SUBS)
        return status to found.map { it.toProduct() }
    }

    override suspend fun queryOneTimeProducts(productIds: List<String>): Pair<BillingStatus, List<OneTimeProduct>> {
        val (status, found) = queryDetails(productIds, ProductType.INAPP)
        return status to found.map { it.toOneTimeProduct() }
    }

    private suspend fun queryDetails(productIds: List<String>, type: String): Pair<BillingStatus, List<ProductDetails>> {
        if (productIds.isEmpty()) return BillingStatus.OK to emptyList()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build() }
            )
            .build()
        return suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, found ->
                val list = found.productDetailsList.orEmpty()
                list.forEach { details[it.productId] = it }
                if (cont.isActive) cont.resume(result.status() to list)
            }
        }
    }

    override suspend fun queryPurchases(): Pair<BillingStatus, List<OwnedPurchase>> =
        // Billing Library 8.1+: also return paused / payment-declined subscriptions, flagged by Purchase.isSuspended().
        queryPurchases(QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).includeSuspendedSubscriptions(true).build())

    override suspend fun queryOneTimePurchases(): Pair<BillingStatus, List<OwnedPurchase>> =
        queryPurchases(QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build())

    private suspend fun queryPurchases(params: QueryPurchasesParams): Pair<BillingStatus, List<OwnedPurchase>> =
        suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { result, purchases ->
                if (cont.isActive) cont.resume(result.status() to purchases.map { it.toOwned() })
            }
        }

    override suspend fun consume(purchaseToken: String): BillingStatus {
        val params = ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build()
        return suspendCancellableCoroutine { cont ->
            client.consumeAsync(params) { result, _ -> if (cont.isActive) cont.resume(result.status()) }
        }
    }

    override fun showInAppMessages(activity: Activity, onSubscriptionUpdated: (purchaseToken: String) -> Unit): BillingStatus {
        val params = InAppMessageParams.newBuilder()
            .addInAppMessageCategoryToShow(InAppMessageParams.InAppMessageCategoryId.TRANSACTIONAL)
            .build()
        return client.showInAppMessages(activity, params) { result ->
            val token = result.purchaseToken
            if (result.responseCode == InAppMessageResult.InAppMessageResponseCode.SUBSCRIPTION_STATUS_UPDATED && token != null) {
                onSubscriptionUpdated(token)
            }
        }.status()
    }

    override suspend fun acknowledge(purchaseToken: String): BillingStatus {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()
        return suspendCancellableCoroutine { cont ->
            client.acknowledgePurchase(params) { result -> if (cont.isActive) cont.resume(result.status()) }
        }
    }

    override fun launchPurchase(
        activity: Activity,
        productId: String,
        offerToken: String,
        oldPurchaseToken: String?,
        replacement: PlanChange?,
    ): BillingStatus {
        val productDetails = details[productId] ?: return BillingStatus.ITEM_UNAVAILABLE
        val builder = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .apply { if (offerToken.isNotEmpty()) setOfferToken(offerToken) }
                    .build()
            )
        )
        if (oldPurchaseToken != null) {
            builder.setSubscriptionUpdateParams(
                BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                    .setOldPurchaseToken(oldPurchaseToken)
                    .setSubscriptionReplacementMode((replacement ?: PlanChange.WITH_TIME_PRORATION).toReplacementMode())
                    .build()
            )
        }
        return client.launchBillingFlow(activity, builder.build()).status()
    }

    override fun disconnect() = client.endConnection()

    private fun BillingResult.status(): BillingStatus = when (responseCode) {
        BillingResponseCode.OK -> BillingStatus.OK
        BillingResponseCode.USER_CANCELED -> BillingStatus.USER_CANCELED
        BillingResponseCode.ITEM_ALREADY_OWNED -> BillingStatus.ITEM_ALREADY_OWNED
        BillingResponseCode.ITEM_NOT_OWNED -> BillingStatus.ITEM_NOT_OWNED
        BillingResponseCode.ITEM_UNAVAILABLE -> BillingStatus.ITEM_UNAVAILABLE
        BillingResponseCode.SERVICE_UNAVAILABLE -> BillingStatus.SERVICE_UNAVAILABLE
        BillingResponseCode.SERVICE_DISCONNECTED -> BillingStatus.SERVICE_DISCONNECTED
        BillingResponseCode.BILLING_UNAVAILABLE -> BillingStatus.BILLING_UNAVAILABLE
        BillingResponseCode.NETWORK_ERROR -> BillingStatus.NETWORK_ERROR
        BillingResponseCode.DEVELOPER_ERROR -> BillingStatus.DEVELOPER_ERROR
        BillingResponseCode.FEATURE_NOT_SUPPORTED -> BillingStatus.FEATURE_NOT_SUPPORTED
        else -> BillingStatus.ERROR
    }

    private fun PlanChange.toReplacementMode(): Int = when (this) {
        PlanChange.WITH_TIME_PRORATION -> ReplacementMode.WITH_TIME_PRORATION
        PlanChange.CHARGE_PRORATED_PRICE -> ReplacementMode.CHARGE_PRORATED_PRICE
        PlanChange.WITHOUT_PRORATION -> ReplacementMode.WITHOUT_PRORATION
        PlanChange.CHARGE_FULL_PRICE -> ReplacementMode.CHARGE_FULL_PRICE
        PlanChange.DEFERRED -> ReplacementMode.DEFERRED
    }

    private fun ProductDetails.toProduct() = SubscriptionProduct(
        productId = productId,
        title = name,
        offers = subscriptionOfferDetails.orEmpty().map { offer ->
            SubscriptionOffer(
                basePlanId = offer.basePlanId,
                offerId = offer.offerId,
                offerToken = offer.offerToken,
                tags = offer.offerTags,
                phases = offer.pricingPhases.pricingPhaseList.map {
                    PricingPhase(it.formattedPrice, it.priceAmountMicros, it.priceCurrencyCode, it.billingPeriod, it.billingCycleCount)
                },
            )
        },
    )

    private fun ProductDetails.toOneTimeProduct() = OneTimeProduct(
        productId = productId,
        title = name,
        // The list holds every purchase option and offer the user is eligible for (Billing Library 8); fall back to
        // the single default offer when Play leaves the list empty.
        offers = (oneTimePurchaseOfferDetailsList?.takeIf { it.isNotEmpty() } ?: listOfNotNull(oneTimePurchaseOfferDetails)).map { offer ->
            OneTimeOffer(
                offerToken = offer.offerToken.orEmpty(), // empty: launch without an offer token (single-offer products)
                formattedPrice = offer.formattedPrice,
                priceMicros = offer.priceAmountMicros,
                currencyCode = offer.priceCurrencyCode,
                purchaseOptionId = offer.purchaseOptionId,
                offerId = offer.offerId,
                fullPriceMicros = offer.fullPriceMicros,
                tags = offer.offerTags.orEmpty(),
            )
        },
    )

    private fun Purchase.toOwned() = OwnedPurchase(
        purchaseToken = purchaseToken,
        productIds = products,
        state = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> OwnedPurchase.State.PURCHASED
            Purchase.PurchaseState.PENDING -> OwnedPurchase.State.PENDING
            else -> OwnedPurchase.State.UNSPECIFIED
        },
        acknowledged = isAcknowledged,
        autoRenewing = isAutoRenewing,
        quantity = quantity,
        suspended = isSuspended,
    )
}
