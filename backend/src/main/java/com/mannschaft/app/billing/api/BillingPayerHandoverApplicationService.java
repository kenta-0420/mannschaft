package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPayerHandoverService;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.PayerHandoverAcceptResponse;
import com.mannschaft.app.billing.api.dto.PayerHandoverRequestResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 柱③-B: 請求担当（payer）引継 API のアプリケーションサービス（設計書 billing_payer_handover_design.md）。
 *
 * <p>ドメインサービス {@link BillingPayerHandoverService} の結果 record を API レスポンス DTO へ写す薄い層。
 * 業務判断・状態遷移・認可はすべてドメイン層が持ち、本クラスは<b>写像のみ</b>を行う（既存
 * {@code BillingContractApplicationService} と同じ役割分担）。</p>
 */
@Service
@RequiredArgsConstructor
public class BillingPayerHandoverApplicationService {

    private final BillingPayerHandoverService payerHandoverService;

    /**
     * 引継要求を作成する（旧 payer による申請・承諾型2段の1段目）。
     *
     * @param scopeKind     TEAM または ORG
     * @param scopeId       スコープ ID
     * @param oldContractId 引継元契約 ID
     * @param operatorUserId 操作者
     * @return 作成された引継要求
     */
    public PayerHandoverRequestResponse request(
            EntitlementScopeKind scopeKind, Long scopeId, UUID oldContractId, Long operatorUserId) {

        BillingPayerHandoverService.HandoverRequestResult result =
                payerHandoverService.requestHandover(scopeKind, scopeId, oldContractId, operatorUserId);

        return PayerHandoverRequestResponse.builder()
                .handoverRequestId(result.handoverRequestId().toString())
                .oldContractId(result.oldContractId().toString())
                .scopeKind(result.scopeKind().name())
                .scopeId(result.scopeId())
                .status(result.status().name())
                .requestedAt(result.requestedAt())
                .expiresAt(result.expiresAt())
                .build();
    }

    /**
     * 引継要求を承諾する（新 payer となる他 ADMIN による承諾・2段目）。
     *
     * <p>支払い方法未登録の場合は例外ではなく {@code REQUIRES_PAYMENT_METHOD} 状態のレスポンスを返す
     * （設計書 §3.6・AC-16。差し戻しであって失敗ではないため）。</p>
     *
     * <p>{@code scopeKind}/{@code scopeId} は URL 由来のスコープであり、ドメイン層で引継要求の実際の
     * スコープと一致するかを検証させるために渡す（不一致は 404 で畳む）。これが無いと、チーム A の ADMIN が
     * チーム B の引継要求 ID を推測して承諾できてしまう（IDOR）。</p>
     *
     * @param scopeKind         URL 上のスコープ種別（TEAM / ORG）
     * @param scopeId           URL 上のスコープ ID
     * @param handoverRequestId 引継要求 ID
     * @param operatorUserId    承諾者（新 payer）
     * @return 承諾結果
     */
    public PayerHandoverAcceptResponse accept(
            EntitlementScopeKind scopeKind, Long scopeId, UUID handoverRequestId, Long operatorUserId) {

        BillingPayerHandoverService.HandoverAcceptResult result =
                payerHandoverService.acceptHandover(scopeKind, scopeId, handoverRequestId, operatorUserId);

        return PayerHandoverAcceptResponse.builder()
                .handoverRequestId(result.handoverRequestId().toString())
                .status(result.status().name())
                .newContractId(result.newContractId() == null ? null : result.newContractId().toString())
                .checkoutUrl(result.checkoutUrl())
                .build();
    }
}
