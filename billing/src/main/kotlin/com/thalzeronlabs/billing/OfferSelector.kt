package com.thalzeronlabs.billing

/**
 * Picks which offer to sell. Taking `subscriptionOfferDetails.first()` is the classic bug: the order is not
 * guaranteed, so the app sometimes sells the base plan to users who were eligible for a free trial (or a
 * developer-only test offer to everyone).
 */
fun interface OfferSelector {
    fun select(product: SubscriptionProduct, basePlanId: String?): SubscriptionOffer?

    companion object {
        /**
         * The default: among the offers of the requested base plan, prefer a free trial, then the cheapest first
         * phase (an intro price), and fall back to the base plan. Offers tagged [excludeTag] are never chosen
         * (use it for offers you only want to sell from a specific screen, e.g. a win-back campaign).
         */
        fun bestForUser(excludeTag: String = "manual-only") = OfferSelector { product, basePlanId ->
            val candidates = product.offers.filter {
                (basePlanId == null || it.basePlanId == basePlanId) && excludeTag !in it.tags
            }
            candidates.firstOrNull { it.hasFreeTrial }
                ?: candidates.filter { !it.isBasePlan }.minByOrNull { it.phases.first().priceMicros }
                    ?.takeIf { it.phases.first().priceMicros < (product.basePlan(it.basePlanId)?.recurringPhase?.priceMicros ?: Long.MAX_VALUE) }
                ?: candidates.firstOrNull { it.isBasePlan }
        }

        /** Always the plain base plan, never a promotion. */
        val basePlanOnly = OfferSelector { product, basePlanId -> product.basePlan(basePlanId) }

        /** Exactly the offer with this id (null = the base plan). */
        fun byOfferId(offerId: String?) = OfferSelector { product, basePlanId ->
            product.offers.firstOrNull { it.offerId == offerId && (basePlanId == null || it.basePlanId == basePlanId) }
        }
    }
}
