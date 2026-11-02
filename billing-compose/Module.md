# Module play-billing-kit-compose

Optional Jetpack Compose components for a Google Play paywall, on top of `play-billing-kit`:
[com.thalzeronlabs.billing.compose.PlanCard] (Play's formatted price, free-trial badge, intro price),
[com.thalzeronlabs.billing.compose.FreeTrialBadge], [com.thalzeronlabs.billing.compose.RestorePurchasesButton],
[com.thalzeronlabs.billing.compose.PaywallLinks] (terms, privacy, manage subscription) and
[com.thalzeronlabs.billing.compose.SubscriptionStatusBanner] (grace period, on hold, paused, canceled).

Texts are English by default; translate them by providing your own
[com.thalzeronlabs.billing.compose.PaywallStrings] through [com.thalzeronlabs.billing.compose.LocalPaywallStrings].

# Package com.thalzeronlabs.billing.compose

Composables plus [PaywallPlan], the plain model each card renders, built from Play's data with
[PaywallPlan.Companion.from] or [paywallPlans].
