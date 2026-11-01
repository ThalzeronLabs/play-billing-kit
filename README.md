# play-billing-kit

[![CI](https://github.com/ThalzeronLabs/play-billing-kit/actions/workflows/ci.yml/badge.svg)](https://github.com/ThalzeronLabs/play-billing-kit/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/ThalzeronLabs/play-billing-kit.svg)](https://jitpack.io/#ThalzeronLabs/play-billing-kit)
[![API docs](https://img.shields.io/badge/docs-Dokka-7F52FF)](https://thalzeronlabs.github.io/play-billing-kit/)
![Play Billing](https://img.shields.io/badge/Play%20Billing-8.3.0-34A853)
![minSdk](https://img.shields.io/badge/minSdk-23-informational)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**Google Play subscriptions for Android without the classic bugs — one `StateFlow`, tested business rules, Billing Library 8.**

## Quick start

**1. Add JitPack** to `settings.gradle.kts` (once per project):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

**2. Add the dependency** — one line in your app's `build.gradle.kts`:

```kotlin
implementation("com.github.ThalzeronLabs:play-billing-kit:v0.1.0")
```

It brings Google Play Billing Library 8.3.0 and the `BILLING` permission with it.

**3. Sell:**

```kotlin
val billing = BillingKit(PlayBillingGateway(context), listOf("pro_monthly", "pro_yearly"), appScope)
billing.start()

billing.state.collect { state ->
    premium = state.hasAnySubscription
    trialBadge = billing.offerFor("pro_monthly")?.hasFreeTrial == true   // "7 days free"
}

billing.buy(activity, "pro_yearly")   // or an upgrade/downgrade, decided for you
```

Requirements: Android 6.0+ (API 23) and subscriptions configured in the Play Console.

## Try it without a Play account

The [`sample`](sample) app is a Compose paywall with two plans (monthly with a free trial, and yearly), the screen
state in a `StateFlow` and a "Restore purchases" button. It runs on any emulator because it talks to
[`FakeBillingGateway`](sample/src/main/kotlin/com/thalzeronlabs/billing/sample/FakeBillingGateway.kt), an in-memory
store, instead of Google Play.

```bash
./gradlew :sample:installDebug
```

**Going live** is one line in [`MainActivity`](sample/src/main/kotlin/com/thalzeronlabs/billing/sample/MainActivity.kt):

```kotlin
val gateway: BillingGateway = FakeBillingGateway()             // demo
val gateway: BillingGateway = PlayBillingGateway(applicationContext)   // real Google Play
```

With the real gateway, Play only returns products to an app uploaded to a Play Console track and installed by a
[license tester](https://developer.android.com/google/play/billing/test); the product ids must match the ones you
created in the Console.

## The classic bugs this kit fixes

| Bug in the wild | What the kit does | Official docs |
|---|---|---|
| **Refunded after 3 days** — the purchase was acknowledged only in `onPurchasesUpdated`, so a purchase restored on another device or after a crash was never acknowledged and Play refunded it. | Every paid purchase is acknowledged **wherever it shows up**: the purchase sheet *and* every restore. | [Notify Google the purchase was processed](https://developer.android.com/google/play/billing/integrate#notifying-google) |
| **Wrong offer sold** — `subscriptionOfferDetails.first()` has no guaranteed order: users eligible for a free trial were charged, or a test offer was sold to everyone. | `OfferSelector.bestForUser()` picks the free trial, then the cheapest intro price, then the base plan; offers tagged `manual-only` are never auto-sold. | [Show products available to buy](https://developer.android.com/google/play/billing/integrate#show-products) |
| **Two subscriptions at once** — buying a second plan stacked a new subscription instead of replacing the old one. | `buy()` turns into a plan change: upgrades switch now with time proration, downgrades wait for the renewal date, and the replaced purchase stops granting access. | [Upgrade, downgrade, or change a subscription](https://developer.android.com/google/play/billing/subscriptions#allow-users-change) |
| **Dead after a service hiccup** — one failed `startConnection` and billing stayed off until the app restarted. | Automatic reconnection plus exponential backoff for transient errors; `BILLING_UNAVAILABLE` (no Play account) is reported once, without retry loops. | [Automatically re-establish a connection](https://developer.android.com/google/play/billing/integrate#automatic-service-reconnection) |
| **Pending payments unlocked** — slow payment methods (cash, bank slips) were treated as paid. | Pending purchases are exposed separately and never unlock features. | [Handle pending transactions](https://developer.android.com/google/play/billing/integrate#pending) |
| **Purchases made elsewhere never show up** — a renewal, a purchase on another device or a pending payment that cleared while the app was closed. | `refresh()` is a full restore that is safe to call on every `onResume`. | [Detect and process purchases](https://developer.android.com/google/play/billing/integrate#process) |

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

Full API reference (Dokka): <https://thalzeronlabs.github.io/play-billing-kit/>

## Tests

All Play calls go through the small `BillingGateway` interface. `PlayBillingGateway` is the real one; the unit tests
run the business rules (acknowledgement, offer choice, plan changes, retries, pending payments) against a fake on the
plain JVM — no emulator, no Play account.

```bash
./gradlew :billing:testDebugUnitTest   # the library: 24 tests
./gradlew :sample:testDebugUnitTest    # the sample's helpers
```

CI runs both on every push and pull request, then builds the AAR and the sample app.

## Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) and the
[good first issues](https://github.com/ThalzeronLabs/play-billing-kit/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22).
Release notes live in [CHANGELOG.md](CHANGELOG.md).

## License

Apache-2.0 © Esdras Souza (Thalzeron Labs).
