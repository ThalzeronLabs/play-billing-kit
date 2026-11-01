# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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

[Unreleased]: https://github.com/ThalzeronLabs/play-billing-kit/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/ThalzeronLabs/play-billing-kit/releases/tag/v0.1.0
