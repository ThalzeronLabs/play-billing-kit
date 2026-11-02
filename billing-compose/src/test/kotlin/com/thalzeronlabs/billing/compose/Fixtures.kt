package com.thalzeronlabs.billing.compose

import com.thalzeronlabs.billing.OneTimeOffer
import com.thalzeronlabs.billing.OneTimeProduct
import com.thalzeronlabs.billing.PricingPhase
import com.thalzeronlabs.billing.SubscriptionOffer
import com.thalzeronlabs.billing.SubscriptionProduct

internal fun phase(formatted: String, micros: Long, period: String, cycles: Int = 0) =
    PricingPhase(formatted, micros, "BRL", period, cycles)

internal val MONTHLY_BASE = SubscriptionOffer("monthly", null, "t-base", phases = listOf(phase("R$ 9,90", 9_900_000, "P1M")))
internal val MONTHLY_TRIAL = SubscriptionOffer(
    "monthly", "trial", "t-trial",
    phases = listOf(phase("Grátis", 0, "P1W", cycles = 1), phase("R$ 9,90", 9_900_000, "P1M")),
)
internal val MONTHLY_INTRO = SubscriptionOffer(
    "monthly", "intro", "t-intro",
    phases = listOf(phase("R$ 4,90", 4_900_000, "P1M", cycles = 3), phase("R$ 9,90", 9_900_000, "P1M")),
)
internal val MONTHLY = SubscriptionProduct("pro_monthly", "Pro", listOf(MONTHLY_BASE, MONTHLY_TRIAL, MONTHLY_INTRO))
internal val YEARLY = SubscriptionProduct(
    "pro_yearly", "Pro yearly",
    listOf(SubscriptionOffer("yearly", null, "t-yearly", phases = listOf(phase("R$ 99,90", 99_900_000, "P1Y")))),
)
internal val REMOVE_ADS = OneTimeProduct("remove_ads", "Remove ads", listOf(OneTimeOffer("t-ads", "R$ 19,90", 19_900_000, "BRL")))
