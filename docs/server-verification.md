# Verifying purchases on your server

`play-billing-kit` runs on the device. That is enough to sell, unlock and restore, but not enough to **trust** a
purchase, nor to see every subscription state. This guide explains why Google recommends a backend, what it does,
and gives a minimal example. Everything here links to the official documentation; no real key is involved.

## Why

1. **The device can be tampered with.** Google's security guide: *"The more data and logic you have in a frontend
   device, the more vulnerable it is to being modified or tampered with."* It names purchase verification as the
   special case: *"A special case of sensitive data and logic that should be handled in the backend is purchase
   verification and acknowledgement."*
   ([Fight fraud and abuse](https://developer.android.com/google/play/billing/security#verify))
2. **Tokens can be replayed.** Store every `purchaseToken` you have processed and reject one you have already used.
   *"purchaseToken is globally unique, so you can safely use this value as a primary key in your database."*
   ([same page](https://developer.android.com/google/play/billing/security#verify))
3. **The device does not see every state.** During **account hold** *"the subscription is not returned by the
   queryPurchasesAsync() method"*, and the device never says that a still-active subscription is in its **grace
   period** ([subscription lifecycle](https://developer.android.com/google/play/billing/lifecycle/subscriptions)).
   Billing Library 8.1+ can return paused or payment-declined subscriptions as *suspended*, without saying which.
   The Google Play Developer API knows the exact state.
4. **Refunds and chargebacks happen after the purchase.** The
   [Voided Purchases API](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.voidedpurchases/list)
   *"lists the purchases that were canceled, refunded or charged-back"*, so you can take back what was revoked.

## How it fits together

```
 device (BillingKit)                      your backend                          Google
 ───────────────────                      ────────────                          ──────
 purchase / refresh ── purchase tokens ─▶ verify each new token ── GET ──────▶ Play Developer API
                                          store token → user                    purchases.subscriptionsv2.get
 BillingState  ◀── statuses ───────────── answer with every subscription        purchases.products.get
 (grace, on hold, paused, canceled…)      of this user (incl. on hold/paused)
                                          ▲
                                          └──── Real-time developer notifications (Pub/Sub)
```

1. The app sends each `purchaseToken` to your backend. `BillingKit` does this for you when you give it a
   [`SubscriptionStatusSource`](../billing/src/main/kotlin/com/thalzeronlabs/billing/SubscriptionStatus.kt): it calls
   `fetch(devicePurchases)` on every `refresh()` and after each purchase.
2. The backend checks the token with the Google Play Developer API, links it to the signed-in user and stores it.
3. The backend answers with the status of every subscription of that user, including the ones the device no longer
   returns (on hold, paused). For a token both sides know, `BillingKit` trusts the backend.
4. [Real-time developer notifications](https://developer.android.com/google/play/billing/rtdn-reference) keep the
   backend up to date without polling. They only say *that* something changed: *"You must call the Google Play
   Developer API after receiving Real-time developer notifications to get the complete status and update your own
   backend state."* The useful subscription types include `SUBSCRIPTION_RECOVERED` (1), `SUBSCRIPTION_CANCELED` (3),
   `SUBSCRIPTION_ON_HOLD` (5), `SUBSCRIPTION_IN_GRACE_PERIOD` (6), `SUBSCRIPTION_PAUSED` (10) and
   `SUBSCRIPTION_EXPIRED` (13).

## The API calls

| What | Call | Fields to read |
|---|---|---|
| A subscription | `GET https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{packageName}/purchases/subscriptionsv2/tokens/{token}` ([reference](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2/get)) | `subscriptionState`, `lineItems[].productId`, `lineItems[].expiryTime`, `acknowledgementState`, `linkedPurchaseToken` |
| A one-time product | `GET .../applications/{packageName}/purchases/products/{productId}/tokens/{token}` ([reference](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.products/get)) | `purchaseState` (0 purchased, 1 canceled, 2 pending), `consumptionState`, `acknowledgementState` |

Notes:

- `linkedPurchaseToken` is set on the new token after an upgrade, a downgrade or a re-signup: look up the old token
  in your database and revoke its entitlement
  ([`SubscriptionPurchaseV2`](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2),
  [security](https://developer.android.com/google/play/billing/security)).
- One-time products also have a newer
  [`purchases.productsv2.getproductpurchasev2`](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.productsv2/getproductpurchasev2)
  endpoint (`.../purchases/productsv2/tokens/{token}`).
- `subscriptionState` maps one-to-one to the kit's
  [`SubscriptionStatus`](../billing/src/main/kotlin/com/thalzeronlabs/billing/SubscriptionStatus.kt) with
  `SubscriptionStatus.fromPlayApi(...)`:

  | `subscriptionState` | `SubscriptionStatus` | Access |
  |---|---|---|
  | `SUBSCRIPTION_STATE_ACTIVE` | `ACTIVE` | yes |
  | `SUBSCRIPTION_STATE_CANCELED` (*"canceled but not expired yet"*) | `CANCELED` | yes, until `expiryTime` |
  | `SUBSCRIPTION_STATE_IN_GRACE_PERIOD` | `GRACE_PERIOD` | yes |
  | `SUBSCRIPTION_STATE_ON_HOLD` | `ON_HOLD` | no |
  | `SUBSCRIPTION_STATE_PAUSED` | `PAUSED` | no |
  | `SUBSCRIPTION_STATE_EXPIRED` | `EXPIRED` | no |
  | `SUBSCRIPTION_STATE_PENDING` | `PENDING` | no |
  | `SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED` | `EXPIRED` | no |

## Credentials (never in the repository)

1. In Google Cloud, create a **service account** for your backend and download its JSON key. Keep it in your
   server's secret store; never commit it, never ship it in the app.
2. In the Play Console, **Users and permissions → Invite new users**, add the service account's e-mail and grant
   *"View financial data, orders, and cancellation survey responses"* and *"Manage orders and subscriptions"*
   ([getting started](https://developers.google.com/android-publisher/getting_started#service-account)).
3. Every call needs the OAuth scope `https://www.googleapis.com/auth/androidpublisher`.

## Minimal example

### Backend (Node.js, [`googleapis`](https://www.npmjs.com/package/googleapis))

```js
// server.mjs: npm install express googleapis
// Run with GOOGLE_APPLICATION_CREDENTIALS=/secure/path/service-account.json (outside the repository).
import express from "express";
import { google } from "googleapis";

const PACKAGE_NAME = "com.example.app"; // your applicationId
const auth = new google.auth.GoogleAuth({ scopes: ["https://www.googleapis.com/auth/androidpublisher"] });
const play = google.androidpublisher({ version: "v3", auth });

const app = express();
app.use(express.json());

// POST /billing/subscriptions { "purchaseTokens": ["..."] } -> [{ productId, purchaseToken, subscriptionState, expiryTime }]
app.post("/billing/subscriptions", async (req, res) => {
  // Production: authenticate the user, save token -> user (reject a token that belongs to someone else),
  // and add the tokens you already stored for this user, so on-hold and paused subscriptions are reported too.
  const tokens = req.body.purchaseTokens ?? [];
  const answer = [];
  for (const token of tokens) {
    const { data } = await play.purchases.subscriptionsv2.get({ packageName: PACKAGE_NAME, token });
    for (const item of data.lineItems ?? []) {
      answer.push({
        productId: item.productId,
        purchaseToken: token,
        subscriptionState: data.subscriptionState,
        expiryTime: item.expiryTime,
      });
    }
  }
  res.json(answer);
});

app.listen(8080);
```

### App (`SubscriptionStatusSource`)

```kotlin
class MyBackendStatusSource(private val api: MyBillingApi) : SubscriptionStatusSource {
    override suspend fun fetch(devicePurchases: List<OwnedPurchase>): List<SubscriptionInfo> =
        api.subscriptions(devicePurchases.map { it.purchaseToken }).mapNotNull { row ->
            val status = SubscriptionStatus.fromPlayApi(row.subscriptionState) ?: return@mapNotNull null
            SubscriptionInfo(
                productId = row.productId,
                status = status,
                purchaseToken = row.purchaseToken,
                expiryTimeMillis = row.expiryTime?.let { java.time.Instant.parse(it).toEpochMilli() },
            )
        }
}

val billing = BillingKit(
    gateway = PlayBillingGateway(context),
    productIds = listOf("pro_monthly", "pro_yearly"),
    scope = appScope,
    statusSource = MyBackendStatusSource(api),
)
```

`MyBillingApi` is your own HTTP client (Retrofit, Ktor…) for the endpoint above. If `fetch` throws (no network,
backend down), `BillingKit` keeps the last statuses it got and falls back to what the device reports, so users are
never locked out by a server hiccup.

## Checklist

- [ ] Every new `purchaseToken` is verified with the Developer API before granting anything valuable server-side.
- [ ] Tokens are stored and a reused token is rejected.
- [ ] `linkedPurchaseToken` retires the old subscription after a plan change or re-signup.
- [ ] RTDN is set up, and each notification triggers a fresh `subscriptionsv2.get`.
- [ ] Voided purchases are checked and their entitlements revoked.
- [ ] The service-account key lives only in the server's secret store.
