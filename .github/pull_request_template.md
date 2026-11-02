## What and why

<!-- What does this change, and which issue does it close? e.g. "Closes #12" -->

## How it was tested

<!-- The new or changed tests. Bug fixes start with a test that failed before the fix. -->

## Checklist

- [ ] `./gradlew :billing:testDebugUnitTest :billing-compose:testDebugUnitTest :sample:testDebugUnitTest :sample:assembleDebug` passes
- [ ] New behavior is covered by a unit test against the fake gateway
- [ ] Public API changes have KDoc
- [ ] Play behavior changes link the official developer.android.com doc
- [ ] `CHANGELOG.md` has a line under `## [Unreleased]`
- [ ] No keys, tokens or `local.properties` committed
