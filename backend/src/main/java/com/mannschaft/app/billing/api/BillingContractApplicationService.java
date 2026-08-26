package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.BillingContractService;
import com.mannschaft.app.billing.BillingContractService.ContractResult;
import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.EntitlementEntity;
import com.mannschaft.app.billing.EntitlementRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.EntitlementSourceKind;
import com.mannschaft.app.billing.api.dto.ChangePlanRequest;
import com.mannschaft.app.billing.api.dto.ContractResponse;
import com.mannschaft.app.billing.api.dto.CreateContractRequest;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * F20.1: 契約 API のアプリケーションサービス（設計書 02 §3）。
 *
 * <p>Controller とドメインの {@link BillingContractService} の間に立ち、(1) テナント
 * {@code organizationId} の解決（USER=null / ORG=自身 / TEAM=主所属組織）、(2) 冪等キーによる
 * 二重送信の吸収（M-1）、(3) {@link ContractResponse} への組み立て、を担う。認可（scope ADMIN・
 * 本人固定）は Controller の {@code @PreAuthorize} が一次防御、契約の所属スコープ一致（IDOR）は
 * ドメイン層 {@code loadContractInScope} が二重防御する（03 §2）。</p>
 */
@Service
@RequiredArgsConstructor
public class BillingContractApplicationService {

    private final BillingContractService billingContractService;
    private final BillingContractRepository billingContractRepository;
    private final EntitlementRepository entitlementRepository;
    private final TeamOrgMembershipQueryService teamOrgMembershipQueryService;
    private final BillingIdempotencyService idempotencyService;
    /** F20.1 実決済（D-4）: 月額をマスタから解決（NULL=無償フロー / 非 NULL=決済フロー）。 */
    private final com.mannschaft.app.billing.BillingPriceResolver priceResolver;
    /** F20.1 実決済（D-4）: 決済フローのオーケストレーション（PENDING 起票→Checkout 生成→補償）。 */
    private final com.mannschaft.app.billing.BillingCheckoutService checkoutService;

    // ============================================================
    // 作成（PLAN / ADDON）— Idempotency-Key 必須
    // ============================================================

    /**
     * 契約を作成する（設計書 02 §3.1）。冪等キーが既知なら既存結果を返す（M-1）。
     *
     * @param scopeKind       USER / TEAM / ORG
     * @param scopeId         users.id / teams.id / organizations.id
     * @param operatorUserId  操作者（USER スコープでは scopeId と一致・監査用）
     * @param request         契約種別・planKey/featureKey
     * @param idempotencyKey  {@code Idempotency-Key} ヘッダ値
     */
    public ContractResponse create(
            EntitlementScopeKind scopeKind, Long scopeId, Long operatorUserId,
            CreateContractRequest request, String idempotencyKey) {

        // M-1: 冪等キーが既知なら既存契約の結果を返す（二重送信の吸収）。
        UUID existing = idempotencyService.findStoredContractId(operatorUserId, idempotencyKey);
        if (existing != null) {
            BillingContractEntity contract = billingContractRepository.findByIdAndDeletedAtIsNull(existing).orElse(null);
            if (contract != null) {
                return toResponse(contract, grantedKeysOf(contract));
            }
            // 記録はあるが契約が見つからない（TTL 内に物理削除は起きない想定）＝再実行に倒す。
        }

        ContractKind contractKind = BillingApiSupport.parseContractKind(request.contractKind());
        Long organizationId = resolveOrganizationId(scopeKind, scopeId);

        // D-4: 価格をマスタから解決。NULL=無償ワンクリック（即 ACTIVE＋発行）／非 NULL=Checkout 決済フロー
        // （PENDING＋entitlements 未発行・入金 webhook で ACTIVE 化）。既存無償契約には遡及しない。
        // 0 円以下は無償扱い（0 円サブスクの Checkout は成立しない・マスタ誤設定の防御）。
        Integer priceJpy = priceResolver.resolveMonthlyPriceJpy(
                scopeKind, scopeId, contractKind, request.planKey(), request.featureKey());

        if (priceJpy == null || priceJpy <= 0) {
            // 無償フロー（P1 のまま）。
            ContractResult result = billingContractService.createContract(
                    scopeKind, scopeId, organizationId, contractKind,
                    request.planKey(), request.featureKey(), operatorUserId);
            idempotencyService.store(operatorUserId, idempotencyKey, result.contractId());
            return toResponse(result, null);
        }

        // 決済フロー: PENDING 起票→Checkout 生成。冪等キーには PENDING 契約 ID を紐付ける。
        com.mannschaft.app.billing.BillingCheckoutService.PaidCheckoutResult paid =
                checkoutService.startPaidContract(scopeKind, scopeId, organizationId, contractKind,
                        request.planKey(), request.featureKey(), priceJpy, operatorUserId);
        idempotencyService.store(operatorUserId, idempotencyKey, paid.pending().contractId());
        return toResponse(paid.pending(), paid.checkoutUrl());
    }

    // ============================================================
    // 解約
    // ============================================================

    /** 契約を解約する（設計書 02 §3.2）。scope 不一致は 404 秘匿（ドメイン層で担保）。 */
    public ContractResponse cancel(
            EntitlementScopeKind scopeKind, Long scopeId, UUID contractId, Long operatorUserId) {
        ContractResult result = billingContractService.cancelContract(scopeKind, scopeId, contractId, operatorUserId);
        return toResponse(result, null);
    }

    // ============================================================
    // プラン変更
    // ============================================================

    /** PLAN 契約のプランを変更する（設計書 02 §3.3）。 */
    public ContractResponse changePlan(
            EntitlementScopeKind scopeKind, Long scopeId, UUID contractId,
            ChangePlanRequest request, Long operatorUserId) {
        ContractResult result = billingContractService.changePlan(
                scopeKind, scopeId, contractId, request.planKey(), operatorUserId);
        return toResponse(result, null);
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    /**
     * テナント organization_id を解決する（設計書 01 §3.1・02 §3.1）。
     * USER=null / ORG=scope_id 自身 / TEAM=主所属組織（ACTIVE 所属の先頭・無所属は null）。
     */
    private Long resolveOrganizationId(EntitlementScopeKind scopeKind, Long scopeId) {
        return switch (scopeKind) {
            case USER -> null;
            case ORG -> scopeId;
            case TEAM -> {
                List<Long> orgIds = teamOrgMembershipQueryService.findActiveOrganizationIds(scopeId);
                yield orgIds.isEmpty() ? null : orgIds.get(0);
            }
        };
    }

    /** 冪等再送時: 契約に紐づく未取消 entitlements から発行機能キーを復元する。 */
    private List<String> grantedKeysOf(BillingContractEntity contract) {
        EntitlementSourceKind sourceKind = contract.getContractKind() == ContractKind.ADDON
                ? EntitlementSourceKind.ADDON : EntitlementSourceKind.PLAN;
        List<String> keys = new ArrayList<>();
        for (EntitlementEntity e : entitlementRepository
                .findBySourceKindAndSourceRefIdAndRevokedAtIsNull(sourceKind, contract.getId())) {
            keys.add(e.getFeatureKey());
        }
        return keys;
    }

    private ContractResponse toResponse(ContractResult r, String checkoutUrl) {
        return ContractResponse.builder()
                .contractId(r.contractId().toString())
                .scopeKind(r.scopeKind().name())
                .scopeId(r.scopeId())
                .contractKind(r.contractKind().name())
                .planKey(r.planKey())
                .featureKey(r.featureKey())
                .status(r.status().name())
                .memberCountSnapshot(r.memberCountSnapshot())
                .bandNoSnapshot(r.bandNoSnapshot())
                .priceJpySnapshot(r.priceJpySnapshot())
                .contractedAt(r.contractedAt())
                .grantedFeatureKeys(r.grantedFeatureKeys())
                .checkoutUrl(checkoutUrl)
                .currentPeriodEnd(r.currentPeriodEnd())
                .build();
    }

    private ContractResponse toResponse(BillingContractEntity c, List<String> grantedKeys) {
        return ContractResponse.builder()
                .contractId(c.getId().toString())
                .scopeKind(c.getScopeKind().name())
                .scopeId(c.getScopeId())
                .contractKind(c.getContractKind().name())
                .planKey(c.getPlanKey())
                .featureKey(c.getFeatureKey())
                .status(c.getStatus().name())
                .memberCountSnapshot(c.getMemberCountSnapshot())
                .bandNoSnapshot(c.getBandNoSnapshot())
                .priceJpySnapshot(c.getPriceJpySnapshot())
                .contractedAt(c.getContractedAt())
                .grantedFeatureKeys(grantedKeys)
                .currentPeriodEnd(c.getCurrentPeriodEnd())
                .build();
    }
}
