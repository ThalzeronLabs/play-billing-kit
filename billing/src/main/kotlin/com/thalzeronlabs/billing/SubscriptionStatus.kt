package com.thalzeronlabs.billing

/**
 * Where a subscription stands, including the states Play reports and apps tend to forget.
 * See the [subscription lifecycle](https://developer.android.com/google/play/billing/lifecycle/subscriptions).
 *
 * What the device can see on its own is limited: the Billing Library reports [ACTIVE], [CANCELED], [PENDING] and
 * [SUSPENDED]. [GRACE_PERIOD], [ON_HOLD], [PAUSED] and [EXPIRED] come from your backend through a
 * [SubscriptionStatusSource] (the Google Play Developer API knows them; see `docs/server-verification.md`).
 */
enum class SubscriptionStatus(
    /** Whether the user should have the subscription's features right now. */
    val grantsAccess: Boolean,
) {
    /** Paid and renewing. Without a [SubscriptionStatusSource] this also covers a grace period, which the device cannot tell apart. */
    ACTIVE(true),

    /**
     * Canceled by the user but not expired: access stays until the end of the paid period. Offer to resubscribe;
     * the user can also undo the cancellation with "Resubscribe" in the Play subscriptions center.
     */
    CANCELED(true),

    /** A renewal payment failed and Play is retrying. Keep access and ask the user to fix their payment method. */
    GRACE_PERIOD(true),

    /** The grace period ran out without payment. Block access and ask the user to fix their payment method. */
    ON_HOLD(false),

    /** The user paused the subscription. Block access and offer to resume it in Google Play. */
    PAUSED(false),

    /**
     * Reported by the device as suspended (`Purchase.isSuspended()`): the user paused it or a renewal payment was
     * declined; the device cannot tell which. Block access and send the user to the Play subscriptions center.
     */
    SUSPENDED(false),

    /** Over. Show the paywall again. */
    EXPIRED(false),

    /** Bought with a slow payment method that has not cleared yet. Do not unlock anything. */
    PENDING(false),
    ;

    companion object {
        /**
         * Maps `subscriptionState` from the Google Play Developer API
         * ([purchases.subscriptionsv2](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2#subscriptionstate))
         * to a [SubscriptionStatus]. Returns null for `SUBSCRIPTION_STATE_UNSPECIFIED` and unknown values.
         */
        fun fromPlayApi(subscriptionState: String): SubscriptionStatus? = when (subscriptionState) {
            "SUBSCRIPTION_STATE_ACTIVE" -> ACTIVE
            "SUBSCRIPTION_STATE_CANCELED" -> CANCELED
            "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" -> GRACE_PERIOD
            "SUBSCRIPTION_STATE_ON_HOLD" -> ON_HOLD
            "SUBSCRIPTION_STATE_PAUSED" -> PAUSED
            "SUBSCRIPTION_STATE_EXPIRED" -> EXPIRED
            "SUBSCRIPTION_STATE_PENDING" -> PENDING
            // The pending payment of a new subscription was canceled: it never started.
            "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED" -> EXPIRED
            else -> null
        }
    }
}

/** The status of one subscription product for this user. */
data class SubscriptionInfo(
    val productId: String,
    val status: SubscriptionStatus,
    val purchaseToken: String? = null,
    /**
     * When the current period ends (`lineItems[].expiryTime` in the Developer API), in epoch millis.
     * The device does not report it, so only a [SubscriptionStatusSource] can fill it.
     */
    val expiryTimeMillis: Long? = null,
)

/**
 * Your backend's view of the user's subscriptions, usually from the Google Play Developer API
 * (`purchases.subscriptionsv2.get`) kept up to date with Real-time developer notifications.
 *
 * [BillingKit] calls it on every [BillingKit.refresh] and after each purchase, with the purchases Play reports on the
 * device so the backend can verify new tokens. For a purchase token both sides know, the backend's status wins; entries
 * only the backend knows (a subscription on hold or paused, which the device may not return at all) are added.
 * If it throws, the kit keeps the last statuses it got and falls back to what the device reports.
 */
fun interface SubscriptionStatusSource {
    suspend fun fetch(devicePurchases: List<OwnedPurchase>): List<SubscriptionInfo>
}

/** Google Play deep links ([docs](https://developer.android.com/google/play/billing/subscriptions#deep-link)). */
object PlayLinks {
    /** The Play subscriptions center with every subscription of the user. */
    const val SUBSCRIPTIONS_CENTER: String = "https://play.google.com/store/account/subscriptions"

    /**
     * The Play page to manage (cancel, resume, fix the payment method of) one subscription.
     * Falls back to [SUBSCRIPTIONS_CENTER] when [productId] is null.
     */
    fun manageSubscription(packageName: String, productId: String?): String =
        if (productId == null) {
            SUBSCRIPTIONS_CENTER
        } else {
            "$SUBSCRIPTIONS_CENTER?sku=${encode(productId)}&package=${encode(packageName)}"
        }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
