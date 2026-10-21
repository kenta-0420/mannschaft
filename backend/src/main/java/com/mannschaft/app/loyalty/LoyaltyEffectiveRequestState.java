package com.mannschaft.app.loyalty;

/** GETの仮投影とPOSTの終端化が共有する状態・固定理由。 */
public record LoyaltyEffectiveRequestState(RequestStatus status, RequestReason reason) {
}
