# Module play-billing-kit

Google Play subscriptions and one-time products for Android without the classic bugs: one `StateFlow`, tested
business rules, Google Play Billing Library 8.

Start with [com.thalzeronlabs.billing.BillingKit]. All Play calls go through
[com.thalzeronlabs.billing.BillingGateway]: [com.thalzeronlabs.billing.PlayBillingGateway] is the real one, and any
fake can stand in for it in tests and demos.

Source, sample app and issues: <https://github.com/ThalzeronLabs/play-billing-kit>

# Package com.thalzeronlabs.billing

The whole public API: the [BillingKit] facade, the [BillingGateway] seam, the [OfferSelector] rules, the
[SubscriptionStatus] of each subscription (with [SubscriptionStatusSource] for your backend), [PlayLinks], and the
immutable models the UI renders ([BillingState], [SubscriptionProduct], [SubscriptionOffer], [OneTimeProduct],
[OneTimeOffer], [OwnedPurchase], [SubscriptionInfo]).
