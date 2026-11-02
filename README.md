# play-billing-kit

[![CI](https://github.com/ThalzeronLabs/play-billing-kit/actions/workflows/ci.yml/badge.svg)](https://github.com/ThalzeronLabs/play-billing-kit/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/ThalzeronLabs/play-billing-kit.svg)](https://jitpack.io/#ThalzeronLabs/play-billing-kit)
[![API docs](https://img.shields.io/badge/docs-Dokka-7F52FF)](https://thalzeronlabs.github.io/play-billing-kit/)
![Play Billing](https://img.shields.io/badge/Play%20Billing-8.3.0-34A853)
![minSdk](https://img.shields.io/badge/minSdk-23-informational)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**Google Play subscriptions and one-time products for Android without the classic bugs — one `StateFlow`, tested
business rules, Billing Library 8, optional Compose paywall components.**

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

**2. Add the dependency** in your app's `build.gradle.kts`:

```kotlin
implementation("com.github.ThalzeronLabs.play-billing-kit:play-billing-kit:v0.2.1")
implementation("com.github.ThalzeronLabs.play-billing-kit:play-billing-kit-compose:v0.2.1")   // optional: ready-made paywall components
```

It brings Google Play Billing Library 8.3.0 and the `BILLING` permission with it.
Since v0.2.0 the repository publishes two modules, so JitPack serves them under the group
`com.github.ThalzeronLabs.play-billing-kit` (v0.1.0 was `com.github.ThalzeronLabs:play-billing-kit:v0.1.0`).

**3. Sell:**

```kotlin
val billing = BillingKit(
    gateway = PlayBillingGateway(context),
    productIds = listOf("pro_monthly", "pro_yearly"),   // subscriptions
    scope = appScope,
    lifetimeProductIds = listOf("remove_ads"),          // one-time, kept forever
    consumableProductIds = listOf("coins_100"),         // one-time, bought again and again
)
billing.start()

billing.state.collect { state ->
    premium = state.hasAnySubscription
    adsRemoved = state.isActive("remove_ads")
    trialBadge = billing.offerFor("pro_monthly")?.hasFreeTrial == true   // "7 days free"
    state.subscription("pro_monthly")?.status   // ACTIVE, GRACE_PERIOD, ON_HOLD, PAUSED, CANCELED…
}

billing.buy(activity, "pro_yearly")   // or an upgrade/downgrade, decided for you
```

Requirements: Android 6.0+ (API 23) and subscriptions configured in the Play Console.

## Try it without a Play account

The [`sample`](sample) app is a Compose paywall built with `billing-compose`: two plans (monthly with a free trial,
and yearly), a lifetime "remove ads", consumable coins, the status banner, a "Restore purchases" button and buttons
that simulate grace period, account hold, pause and cancellation. Its
[`MainActivity`](sample/src/main/kotlin/com/thalzeronlabs/billing/sample/MainActivity.kt) has `@Preview`s of every
component. It runs on any emulator because it talks to
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
| **Refunded after 3 days** — the purchase was acknowledged only in `onPurchasesUpdated`, so a purchase restored on another device or after a crash was never acknowledged and Play refunded it. | Every paid purchase is acknowledged **wherever it shows up**: the purchase sheet *and* every restore; a failed acknowledgement is retried with backoff. | [Notify Google the purchase was processed](https://developer.android.com/google/play/billing/integrate#notifying-google) |
| **Wrong offer sold** — `subscriptionOfferDetails.first()` has no guaranteed order: users eligible for a free trial were charged, or a test offer was sold to everyone. | `OfferSelector.bestForUser()` picks the free trial, then the cheapest intro price, then the base plan; offers tagged `manual-only` are never auto-sold. | [Show products available to buy](https://developer.android.com/google/play/billing/integrate#show-products) |
| **Two subscriptions at once** — buying a second plan stacked a new subscription instead of replacing the old one. | `buy()` turns into a plan change: upgrades switch now with time proration, downgrades wait for the renewal date, and the replaced purchase stops granting access. | [Upgrade, downgrade, or change a subscription](https://developer.android.com/google/play/billing/subscriptions#allow-users-change) |
| **Dead after a service hiccup** — one failed `startConnection` and billing stayed off until the app restarted. | Automatic reconnection plus exponential backoff for transient errors; `BILLING_UNAVAILABLE` (no Play account) is reported once, without retry loops. | [Automatically re-establish a connection](https://developer.android.com/google/play/billing/integrate#automatic-service-reconnection) |
| **Pending payments unlocked** — slow payment methods (cash, bank slips) were treated as paid. | Pending purchases are exposed separately and never unlock features. | [Handle pending transactions](https://developer.android.com/google/play/billing/integrate#pending) |
| **Purchases made elsewhere never show up** — a renewal, a purchase on another device or a pending payment that cleared while the app was closed. | `refresh()` is a full restore that is safe to call on every `onResume`. | [Detect and process purchases](https://developer.android.com/google/play/billing/integrate#process) |

## One-time products

Lifetime (non-consumable) and consumable products live in the same `BillingState` as subscriptions.

```kotlin
billing.buy(activity, "remove_ads")            // acknowledged for you, then state.isActive("remove_ads") == true

billing.buy(activity, "coins_100")
billing.state.collect { state ->
    state.unconsumedPurchases.forEach { purchase ->
        wallet.add(100 * purchase.quantity)    // 1. grant it (on your server, ideally)
        billing.consume(purchase.purchaseToken) // 2. then consume: it also acknowledges, and allows buying again
    }
}
```

| Rule | What the kit does | Official docs |
|---|---|---|
| A non-consumable must be acknowledged within 3 days or Play refunds it. | Acknowledged wherever it shows up (purchase sheet and restore), like subscriptions. | [Non-consumable products](https://developer.android.com/google/play/billing/integrate#non-consumable_products) |
| A consumable is acknowledged by consuming it, after the app grants it. | Never acknowledged by the kit; exposed in `unconsumedPurchases` until you call `consume()`. An unconsumed one comes back on restore, so a crash never loses it. | [Consumable products](https://developer.android.com/google/play/billing/integrate#consumable_products) |
| A consumable cannot be bought again before it is consumed. | `buy()` returns `ITEM_ALREADY_OWNED` without opening the sheet; same for a lifetime product already owned. | [Consumable products](https://developer.android.com/google/play/billing/integrate#consumable_products) |
| Consuming a lifetime product or a subscription would destroy it. | `consume()` refuses them with `DEVELOPER_ERROR`. | |
| Billing Library 8 can return several purchase options and offers per one-time product, only the ones the user is eligible for. | `buy()` sells the cheapest one, skipping offers tagged `manual-only`; `oneTimeOfferFor()` tells you which. | [Multiple purchase options and offers](https://developer.android.com/google/play/billing/one-time-product-multi-purchase-options-offers) |
| Pending payments do not count. | Pending one-time purchases unlock nothing and are neither acknowledged nor offered for consumption. | [Handle pending transactions](https://developer.android.com/google/play/billing/integrate#pending) |

Tests: [`OneTimeProductsTest`](billing/src/test/kotlin/com/thalzeronlabs/billing/OneTimeProductsTest.kt).

## Subscription states apps forget

`state.subscriptions` holds a `SubscriptionInfo` per product with one of these statuses, and `isActive()` /
`hasAnySubscription` follow its `grantsAccess`. `SubscriptionStatusBanner` (in `billing-compose`) shows the message
and the Google Play button below.

| State | What the device sees | Access | What the app should show | Test | Official docs |
|---|---|---|---|---|---|
| **Grace period** | The purchase as if active; only your backend knows. | **Keep** | A non-blocking "problem with your payment" message and a *Fix payment* button to Play's subscription page. Call `billing.showInAppMessages(activity)` on app start: Play shows its own message and the kit refreshes when the user fixes it. | `grace period keeps access` | [Grace period](https://developer.android.com/google/play/billing/lifecycle/subscriptions#grace-period), [in-app messaging](https://developer.android.com/google/play/billing/subscriptions#in-app-messaging) |
| **Account hold** | Not returned by `queryPurchasesAsync()`, per the lifecycle docs; only your backend knows. | **Block** | "Your subscription is on hold": why access stopped, and *Fix payment* (Play's subscription page). Not the paywall: the user still has a subscription. | `account hold blocks access and still says why` | [Account hold](https://developer.android.com/google/play/billing/lifecycle/subscriptions#account-hold) |
| **Paused** | Returned as suspended only with `includeSuspendedSubscriptions` (the kit sets it). | **Block** | "Your subscription is paused" and a *Resume* button: a deep link to Play. | `paused blocks access` | [Pause](https://developer.android.com/google/play/billing/lifecycle/subscriptions#pause) |
| **Suspended** (device only) | `Purchase.isSuspended()`: paused *or* payment declined; the device cannot tell which. | **Block** | Send the user to Play's subscriptions center to fix the payment or resume. | `a subscription the device reports as suspended blocks access` | [Release notes 8.1.0](https://developer.android.com/google/play/billing/release-notes) |
| **Canceled, still valid** | Returned, with `isAutoRenewing() == false`. | **Keep** until the period ends | "Canceled, you keep access until *date*" and a *Resubscribe* button (Play's subscription page has a Resubscribe button that undoes the cancellation). | `canceled but not expired keeps access until the end of the period` | [Cancellations](https://developer.android.com/google/play/billing/lifecycle/subscriptions#cancel), [restore](https://developer.android.com/google/play/billing/lifecycle/subscriptions#resubscribe) |

Grace period, account hold and paused (and the expiry date) are only known for sure by the Google Play Developer
API. Plug your backend in with a `SubscriptionStatusSource`; see **[docs/server-verification.md](docs/server-verification.md)**.
Without one, the kit uses what the device reports (active, canceled, suspended, pending).
Tests: [`SubscriptionStatusTest`](billing/src/test/kotlin/com/thalzeronlabs/billing/SubscriptionStatusTest.kt).

## Compose paywall components (`billing-compose`)

An optional module with the pieces every paywall needs, driven by Play's own data:

```kotlin
val plans = billing.paywallPlans(listOf("pro_monthly", "pro_yearly", "remove_ads"), state)

plans.forEach { plan -> PlanCard(plan, onBuy = { billing.buy(activity, it) }) }   // Play's price, trial badge, intro price
state.subscription("pro_monthly")?.let { SubscriptionStatusBanner(it) }          // grace, on hold, paused, canceled
RestorePurchasesButton(onClick = { scope.launch { billing.refresh() } })
PaywallLinks(termsUrl = "https://example.com/terms", privacyUrl = "https://example.com/privacy") // + Play's manage-subscription page
```

| Component | What it shows |
|---|---|
| `PlanCard` | Title, Play's formatted price per period ("R$ 9,90 / month"), `FreeTrialBadge` when Play offers this user a trial, the intro price, and a buy button (or "Your current plan" / "Payment pending"). |
| `FreeTrialBadge` | The "1 week free" pill. |
| `RestorePurchasesButton` | "Restore purchases". |
| `PaywallLinks` | Terms of service, privacy policy, and [Play's manage-subscription page](https://developer.android.com/google/play/billing/subscriptions#deep-link). |
| `SubscriptionStatusBanner` | The message and Google Play button of the table above. |

Prices are never formatted by the app. Texts are English; translate them by providing your own `PaywallStrings`
through `LocalPaywallStrings`. The components are tested on the JVM with Robolectric
([`PaywallComponentsTest`](billing-compose/src/test/kotlin/com/thalzeronlabs/billing/compose/PaywallComponentsTest.kt)).

## API at a glance

```kotlin
billing.state            // StateFlow<BillingState>: connection, products, oneTimeProducts, activeProductIds,
                         // pendingProductIds, subscriptions (statuses), unconsumedPurchases, lastError
billing.refresh()        // "restore purchases"; safe on every app resume
billing.buy(activity, productId, basePlanId = null)   // subscription or one-time product
billing.changePlan(activity, toProductId, mode = PlanChange.DEFERRED)
billing.consume(purchaseToken)         // after granting a consumable
billing.showInAppMessages(activity)    // Play's own "fix your payment" message (grace period, account hold)
billing.offerFor(productId)            // the subscription offer buy() would sell, for your paywall copy
billing.oneTimeOfferFor(productId)     // the one-time offer buy() would sell
PlayLinks.manageSubscription(packageName, productId)   // deep link to Play's subscription page
billing.stop()
```

Need a different rule? Pass your own `OfferSelector`, or use `OfferSelector.basePlanOnly` / `OfferSelector.byOfferId("winback")`.

Full API reference (Dokka): <https://thalzeronlabs.github.io/play-billing-kit/>

## Tests

All Play calls go through the small `BillingGateway` interface. `PlayBillingGateway` is the real one; the unit tests
run the business rules (acknowledgement, offer choice, plan changes, retries, pending payments, one-time products,
consumption, subscription states) against a fake on the plain JVM — no emulator, no Play account.

```bash
./gradlew :billing:testDebugUnitTest           # the library: 55 tests
./gradlew :billing-compose:testDebugUnitTest   # the Compose components: 21 tests (Robolectric)
./gradlew :sample:testDebugUnitTest            # the sample's ViewModel on the fake store: 10 tests
```

CI runs them on every push and pull request, then builds both AARs, the sample app and the API docs.

## Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) and the
[good first issues](https://github.com/ThalzeronLabs/play-billing-kit/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22).
Release notes live in [CHANGELOG.md](CHANGELOG.md).

## License

Apache-2.0 © Esdras Souza (Thalzeron Labs).
