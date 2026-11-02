# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed
- Install coordinates in the README, CONTRIBUTING, the sample and the release notes template: with two modules JitPack
  publishes `com.github.ThalzeronLabs.play-billing-kit:play-billing-kit` and
  `com.github.ThalzeronLabs.play-billing-kit:play-billing-kit-compose` (checked against the JitPack build of v0.2.0).

## [0.2.0] - 2026-11-02

### Added
- One-time products in the same `BillingState`: lifetime (non-consumable) products are acknowledged and unlock
  through `activeProductIds`; consumables wait in `unconsumedPurchases` until the app grants them and calls
  `BillingKit.consume()`, then can be bought again. `buy()` sells the cheapest eligible one-time offer (Billing
  Library 8 purchase options and offers) and answers `ITEM_ALREADY_OWNED` without opening the sheet when the product
  is still owned. `consume()` refuses lifetime products and subscriptions.
- Subscription statuses: `BillingState.subscriptions` with `SubscriptionStatus` `ACTIVE`, `CANCELED` (still valid),
  `GRACE_PERIOD`, `ON_HOLD`, `PAUSED`, `SUSPENDED`, `EXPIRED` and `PENDING`; access follows `grantsAccess`.
- `SubscriptionStatusSource` to plug in your backend (Google Play Developer API), and
  `SubscriptionStatus.fromPlayApi()` to map `subscriptionState`.
- Suspended subscriptions are queried (`includeSuspendedSubscriptions`, Billing Library 8.1+) and never unlock.
- `BillingKit.showInAppMessages()`: Play's own message for payment problems; the state refreshes when the user fixes it.
- `PlayLinks`: deep links to the Play subscriptions center and to one subscription.
- New optional module `play-billing-kit-compose`: `PlanCard`, `FreeTrialBadge`, `RestorePurchasesButton`,
  `PaywallLinks`, `SubscriptionStatusBanner`, `PaywallPlan`/`paywallPlans()` and translatable `PaywallStrings`;
  Compose UI tests on Robolectric.
- `docs/server-verification.md`: why and how to verify purchases with the Google Play Developer API, with a minimal
  backend example.
- Sample: lifetime and consumable products, status simulator, previews of every component.
- One Dokka site for both modules.

### Changed
- `BillingState.hasAnySubscription` now means "a subscription grants access right now" (lifetime products do not count).
- `BillingGateway` has new methods with default implementations (`queryOneTimeProducts`, `queryOneTimePurchases`,
  `consume`, `showInAppMessages`), so existing fakes keep compiling.
- `OwnedPurchase` has `quantity` and `suspended`; a suspended purchase does not grant access.

## [0.1.0] - 2026-11-01

First public release.

### Added
- `BillingKit`: one `StateFlow<BillingState>` for subscriptions on Google Play Billing Library 8.3.0.
- Acknowledgement of every paid purchase wherever it shows up (purchase sheet and restore).
- `OfferSelector` with `bestForUser()` (free trial, then cheapest intro price, then base plan; skips `manual-only`
  offers), `basePlanOnly` and `byOfferId()`.
- Plan changes from `buy()`/`changePlan()`: upgrades switch now with time proration, downgrades are deferred.
- Reconnection with exponential backoff for transient errors; `BILLING_UNAVAILABLE` reported once.
- Pending purchases exposed separately, never unlocking features.
- `BillingGateway` seam with the real `PlayBillingGateway`.
- Publishing through JitPack: `com.github.ThalzeronLabs:play-billing-kit:v0.1.0`.
- `sample` app: Compose paywall with two plans, `StateFlow` screen state and restore, on an in-memory fake store.
- Dokka API reference and GitHub Pages workflow; contributing guide, issue and pull request templates.

### Fixed
- After a plan change, the replaced subscription kept granting access until the next `refresh()`. It is now retired as
  soon as the new purchase arrives, and the state is re-synced with `queryPurchasesAsync`.
- `kotlinx-coroutines-core` is now an `api` dependency, since `StateFlow`/`Flow` are part of the public API.

[Unreleased]: https://github.com/ThalzeronLabs/play-billing-kit/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/ThalzeronLabs/play-billing-kit/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/ThalzeronLabs/play-billing-kit/releases/tag/v0.1.0
