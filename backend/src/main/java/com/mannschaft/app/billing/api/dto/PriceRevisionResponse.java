package com.mannschaft.app.billing.api.dto;

import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementScopeKind;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code POST /price-revisions} レスポンス（決定4）。 */
@Getter
@Builder
public class PriceRevisionResponse {

    private UUID id;
    private BillingProductKind productKind;
    private String productKey;
    private EntitlementScopeKind scopeKind;
    private long revisionNo;
    private String catalogRevision;
    private BillingPriceVersionStatus status;
    private Instant effectiveFrom;
    private Instant effectiveUntil;
    private List<PriceRevisionBandResponse> bands;

    /**
     * provision/retry-provision/reconcile-provision/activate が要求する CAS 用のバージョン
     * （{@code BillingPriceVersionEntity#lockVersion}）。
     *
     * <p>根治治療（2026-09-23・出陣隊第4陣）: 本フィールドが存在しないと、クライアント
     * （FE 詳細画面・本 IT 含む）は create/provision 等のレスポンスから現在の lockVersion を
     * 一切知る手段がなく、2回目以降の CAS 呼び出しが常に古い値（実質0固定）を送ることになり、
     * 1回でも provision した revision に対する retry-provision/reconcile-provision/activate が
     * 必ず {@code LOCK_VERSION_CONFLICT}（409）になる欠陥があった
     * （{@code PriceRevisionReachesPlanChangeIT} の実行で発見・実測）。</p>
     */
    private Long lockVersion;
}
