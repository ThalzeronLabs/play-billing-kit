# play-billing-kit

**Google Play subscriptions for Android without the classic bugs — one `StateFlow`, tested business rules, Billing Library 8.**

```kotlin
val billing = BillingKit(PlayBillingGateway(context), listOf("pro_monthly", "pro_yearly"), appScope)
billing.start()

billing.state.collect { state ->
    premium = state.hasAnySubscription
    trialBadge = billing.offerFor("pro_monthly")?.hasFreeTrial == true   // "7 days free"
}

billing.buy(activity, "pro_yearly")   // or an upgrade/downgrade, decided for you
```

## The bugs it removes

| Bug in the wild | What the kit does |
|---|---|
| **Refunded after 3 days** — the purchase was acknowledged only in `onPurchasesUpdated`, so a purchase restored on another device or after a crash was never acknowledged and Play refunded it. | Every paid purchase is acknowledged **wherever it shows up**: the purchase sheet *and* every restore. |
| **Wrong offer sold** — `subscriptionOfferDetails.first()` has no guaranteed order: users eligible for a free trial were charged, or a test offer was sold to everyone. | `OfferSelector.bestForUser()` picks the free trial, then the cheapest intro price, then the base plan; offers tagged `manual-only` are never auto-sold. |
| **Two subscriptions at once** — buying a second plan stacked a new subscription instead of replacing the old one. | `buy()` turns into a plan change: upgrades switch now with time proration, downgrades wait for the renewal date. |
| **Dead after a service hiccup** — one failed `startConnection` and billing stayed off until the app restarted. | Automatic reconnection plus exponential backoff for transient errors; `BILLING_UNAVAILABLE` (no Play account) is reported once, without retry loops. |
| **Pending payments unlocked** — slow payment methods (cash, bank slips) were treated as paid. | Pending purchases are exposed separately and never unlock features. |

## API at a glance

```kotlin
billing.state            // StateFlow<BillingState>: connection, products, activeProductIds, pendingProductIds, lastError
billing.refresh()        // "restore purchases"; safe on every app resume
billing.buy(activity, productId, basePlanId = null)
billing.changePlan(activity, toProductId, mode = PlanChange.DEFERRED)
billing.offerFor(productId)   // the offer buy() would sell, for your paywall copy
billing.stop()
```

Need a different rule? Pass your own `OfferSelector`, or use `OfferSelector.basePlanOnly` / `OfferSelector.byOfferId("winback")`.

## Testable by design

All Play calls go through the small `BillingGateway` interface. `PlayBillingGateway` is the real one; the 22 unit tests
run the business rules (acknowledgement, offer choice, plan changes, retries) against a fake on the plain JVM.

```bash
./gradlew :billing:testDebugUnitTest
```

## Setup

Add the module to your project (`settings.gradle.kts`): `include(":billing")` with `projectDir` pointing here.
Requirements: Android 6.0+ (API 23), Google Play Billing Library 8.3.

## License

Apache-2.0 © Esdras Souza (Thalzeron Labs).
