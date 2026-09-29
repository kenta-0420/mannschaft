package com.mannschaft.app.billing.api.dto;

import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementScopeKind;

import java.time.Instant;
import java.util.List;

/**
 * {@code POST /price-revisions} リクエスト（決定4）。
 *
 * @param productKind   PLAN/ADDON
 * @param productKey    商品キー
 * @param scopeKind     USER/TEAM/ORG
 * @param effectiveFrom 適用開始日時（未来日時のみ許容）
 * @param effectiveUntil 適用終了日時（null は無期限。指定時は effectiveFrom より後である必要がある）
 * @param bands         band 一覧（1〜10件）
 */
public record PriceRevisionCreateRequest(
        BillingProductKind productKind, String productKey, EntitlementScopeKind scopeKind,
        Instant effectiveFrom, Instant effectiveUntil, List<PriceBandInput> bands) {
}
