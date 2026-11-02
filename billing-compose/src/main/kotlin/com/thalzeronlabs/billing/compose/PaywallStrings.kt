package com.thalzeronlabs.billing.compose

import androidx.compose.runtime.staticCompositionLocalOf
import com.thalzeronlabs.billing.SubscriptionInfo
import com.thalzeronlabs.billing.SubscriptionStatus
import java.text.DateFormat
import java.util.Date

/**
 * Every text the paywall components show, in English. To translate or reword them, subclass it and provide it with
 * [LocalPaywallStrings]:
 *
 * ```kotlin
 * CompositionLocalProvider(LocalPaywallStrings provides MyPortugueseStrings) { MyPaywall() }
 * ```
 *
 * Prices are never built here: they always come formatted by Google Play.
 */
open class PaywallStrings {
    open val restorePurchases: String = "Restore purchases"
    open val termsOfService: String = "Terms of Service"
    open val privacyPolicy: String = "Privacy Policy"
    open val manageSubscription: String = "Manage subscription"
    open val currentPlan: String = "Your current plan"
    open val paymentPending: String = "Payment pending: unlocks once it clears"
    open val subscribe: String = "Subscribe"
    open val buy: String = "Buy"

    /** "month" for "P1M", "3 months" for "P3M": the unit after a price ("R$ 9,90 / month"). */
    open fun periodUnit(isoPeriod: String): String {
        val (count, unit) = parsePeriod(isoPeriod) ?: return isoPeriod
        return if (count == 1) unit else "$count ${unit}s"
    }

    /** "1 week" for ("P1W", 1), "3 months" for ("P1M", 3): how long a trial or an intro price lasts. */
    open fun duration(isoPeriod: String, cycles: Int = 1): String {
        val (count, unit) = parsePeriod(isoPeriod) ?: return isoPeriod
        val total = count * cycles.coerceAtLeast(1)
        return if (total == 1) "1 $unit" else "$total ${unit}s"
    }

    /** "R$ 9,90 / month", or just the price for a one-time product. */
    open fun pricePerPeriod(price: String, isoPeriod: String?): String =
        if (isoPeriod == null) price else "$price / ${periodUnit(isoPeriod)}"

    /** The badge on a plan with a free trial: "1 week free". */
    open fun freeTrialBadge(duration: String): String = "$duration free"

    /** "R$ 4,90 / month for 3 months": the intro price line. */
    open fun introOffer(pricePerPeriod: String, duration: String): String = "$pricePerPeriod for $duration"

    /** The button label of a plan card. */
    open fun buyButton(plan: PaywallPlan): String = when {
        plan.oneTime -> buy
        plan.freeTrialPeriod != null -> "Start ${duration(plan.freeTrialPeriod, plan.freeTrialCycles)} free trial"
        else -> subscribe
    }

    /** A date the user reads, e.g. the end of a canceled subscription. */
    open fun formatDate(epochMillis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))

    /**
     * What to tell the user about a subscription status, or null when there is nothing to say
     * ([SubscriptionStatus.ACTIVE]; [SubscriptionStatus.EXPIRED] simply shows the paywall again).
     */
    open fun statusMessage(info: SubscriptionInfo): String? = when (info.status) {
        SubscriptionStatus.GRACE_PERIOD ->
            "There is a problem with your payment. Update your payment method in Google Play to keep your subscription."
        SubscriptionStatus.ON_HOLD ->
            "Your subscription is on hold because the payment failed. Update your payment method in Google Play to get access back."
        SubscriptionStatus.PAUSED ->
            "Your subscription is paused. You can resume it in Google Play at any time."
        SubscriptionStatus.SUSPENDED ->
            "Your subscription is paused or its payment failed. Open Google Play to get access back."
        SubscriptionStatus.CANCELED -> info.expiryTimeMillis
            ?.let { "Your subscription was canceled. You keep access until ${formatDate(it)}." }
            ?: "Your subscription was canceled. You keep access until the end of the current period."
        SubscriptionStatus.PENDING -> "Your payment is pending. Access unlocks once it clears."
        SubscriptionStatus.ACTIVE, SubscriptionStatus.EXPIRED -> null
    }

    /** The label of the button that opens Google Play for this status, or null for no button. */
    open fun statusAction(status: SubscriptionStatus): String? = when (status) {
        SubscriptionStatus.GRACE_PERIOD, SubscriptionStatus.ON_HOLD -> "Fix payment"
        SubscriptionStatus.PAUSED -> "Resume"
        SubscriptionStatus.SUSPENDED -> manageSubscription
        SubscriptionStatus.CANCELED -> "Resubscribe"
        SubscriptionStatus.ACTIVE, SubscriptionStatus.EXPIRED, SubscriptionStatus.PENDING -> null
    }

    private fun parsePeriod(iso: String): Pair<Int, String>? {
        val match = PERIOD.matchEntire(iso) ?: return null
        val unit = when (match.groupValues[2]) {
            "D" -> "day"
            "W" -> "week"
            "M" -> "month"
            else -> "year"
        }
        return match.groupValues[1].toInt() to unit
    }

    private companion object {
        /** The single-unit ISO-8601 periods Play uses: P3D, P1W, P1M, P1Y. */
        val PERIOD = Regex("""P(\d+)([DWMY])""")
    }
}

/** The [PaywallStrings] the components read. Defaults to English. */
val LocalPaywallStrings = staticCompositionLocalOf { PaywallStrings() }
