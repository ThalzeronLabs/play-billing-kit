# Contributing to play-billing-kit

Thanks for helping! This library exists to remove the billing bugs that cost apps real money, so the bar is simple:
**every rule is covered by a test that runs on the plain JVM**.

## Setup

- JDK 17 or newer
- Android SDK with platform 37 (Android Studio installs it, or `sdkmanager "platforms;android-37.0"`)
- No Play Console account needed: tests and the sample use fakes.

```bash
git clone https://github.com/ThalzeronLabs/play-billing-kit.git
cd play-billing-kit
./gradlew :billing:testDebugUnitTest :billing-compose:testDebugUnitTest :sample:testDebugUnitTest :sample:assembleDebug
```

## Project layout

| Path | What lives there |
|---|---|
| `billing/src/main/kotlin` | The library. `BillingKit` holds the rules; `PlayBillingGateway` is the only file that touches the Play Billing Library. |
| `billing/src/test/kotlin` | Unit tests against `FakeGateway` (`Fixtures.kt`). |
| `billing-compose/` | Optional Compose paywall components; JVM tests with Robolectric. |
| `sample/` | Compose paywall running on `FakeBillingGateway`, with previews. |
| `docs/` | Guides, e.g. server-side verification. |

## Making a change

1. Open an issue first for anything bigger than a small fix, so we agree on the shape of the API.
2. Branch from `main`.
3. **Write the test first.** A bug fix starts with a failing test in `BillingKitTest` or `OfferSelectorTest` that
   reproduces it with `FakeGateway`. Logic does not go into `PlayBillingGateway` — keep it a thin translation layer
   so the rules stay testable.
4. If you change Play behavior, link the official doc (developer.android.com) in the PR that says it works that way.
5. Run the checks above; CI runs the same ones.
6. Add a line under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md).
7. Open the pull request; the template has a short checklist.

## Style

- Kotlin official code style (`kotlin.code.style=official`); keep public APIs documented with KDoc — it becomes the
  [Dokka site](https://thalzeronlabs.github.io/play-billing-kit/).
- Small, focused commits with messages in the imperative ("Add OfferSelector.byTag").
- No new dependencies in `billing` without discussing it in an issue first.
- Never commit keys, tokens, `local.properties` or signing files.

## Releasing (maintainers)

1. Move the `Unreleased` notes in `CHANGELOG.md` under the new version and bump `VERSION_NAME` in `gradle.properties`.
2. Merge to `main`, then run the **Release** workflow (or push a `vX.Y.Z` tag). It runs the tests, builds the AAR
   and creates the GitHub release from the changelog section.
3. JitPack builds the tag on first request: `com.github.ThalzeronLabs:play-billing-kit:vX.Y.Z` and
   `com.github.ThalzeronLabs:play-billing-kit-compose:vX.Y.Z`.

## Code of conduct

Be kind and assume good intent. Harassment of any kind is not tolerated.
