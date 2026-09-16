package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.billing.BillingChangePreviewEntity;
import com.mannschaft.app.billing.BillingChangePreviewRepository;
import com.mannschaft.app.billing.BillingContractChangeEntity;
import com.mannschaft.app.billing.BillingContractChangeKind;
import com.mannschaft.app.billing.BillingContractChangeRepository;
import com.mannschaft.app.billing.BillingContractChangeStatus;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractOperationSagaService;
import com.mannschaft.app.billing.BillingContractOperationSagaService.OperationReservation;
import com.mannschaft.app.billing.BillingContractOperationSagaService.ReserveCommand;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.BillingOperationActorKind;
import com.mannschaft.app.billing.BillingOperationKind;
import com.mannschaft.app.billing.BillingPlanChangeGateway;
import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.ScopeMemberCountService;
import com.mannschaft.app.billing.api.BillingConflictException.BillingConflictDetails;
import com.mannschaft.app.billing.api.BillingConflictException.Reason;
import com.mannschaft.app.billing.api.dto.BillingContractChangeResponse;
import com.mannschaft.app.billing.api.dto.BillingPlanChangeRequest;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Billing Center PR6b-1 A群: preview の消費（AC-6〜12/AC-21）。
 *
 * <p><b>本クラスの担当範囲</b>: preview の一回消費 CAS・IDOR・4軸再照合・Saga への予約委譲までが
 * A群（AC-1〜24）の担当。予約後の Stripe 適用結果の反映（{@code applyPlanChange} の呼び出し自体は
 * AC-2 の逆写像として必要だが、{@code invoice.paid} 確定・原子性・冪等リプレイなど B群
 * （AC-25〜47）の精緻化は第7隊が引き継ぐ。</p>
 */
@Slf4j
@Service
public class BillingPlanChangeService {

    private final BillingChangePreviewRepository changePreviewRepository;
    private final BillingContractChangeRepository changeRepository;
    private final BillingContractRepository billingContractRepository;
    private final BillingPriceBandVersionRepository bandRepository;
    private final BillingAccessGuard billingAccessGuard;
    private final ScopeMemberCountService scopeMemberCountService;
    private final BillingContractOperationSagaService sagaService;
    private final BillingPlanChangeGateway planChangeGateway;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    /** AC-136/AC-138: change の作成・同期失敗を監査する（PR6a {@code BILLING_CANCEL_*} と同型）。 */
    private final AuditLogService auditLogService;

    /** {@code prepare}/{@code finalizeChange} を自己呼び出しせず独立トランザクションで走らせるための template。 */
    private final TransactionTemplate newTransactionTemplate;

    public BillingPlanChangeService(
            BillingChangePreviewRepository changePreviewRepository,
            BillingContractChangeRepository changeRepository,
            BillingContractRepository billingContractRepository,
            BillingPriceBandVersionRepository bandRepository,
            BillingAccessGuard billingAccessGuard,
            ScopeMemberCountService scopeMemberCountService,
            BillingContractOperationSagaService sagaService,
            BillingPlanChangeGateway planChangeGateway,
            ObjectMapper objectMapper,
            Clock clock,
            AuditLogService auditLogService,
            PlatformTransactionManager transactionManager) {
        this.changePreviewRepository = changePreviewRepository;
        this.changeRepository = changeRepository;
        this.billingContractRepository = billingContractRepository;
        this.bandRepository = bandRepository;
        this.billingAccessGuard = billingAccessGuard;
        this.scopeMemberCountService = scopeMemberCountService;
        this.sagaService = sagaService;
        this.planChangeGateway = planChangeGateway;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.auditLogService = auditLogService;
        this.newTransactionTemplate = new TransactionTemplate(transactionManager);
        this.newTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public BillingContractChangeResponse change(
            long actorId, UUID contractId, BillingPlanChangeRequest request,
            String idempotencyKey, String requestBody) {

        Prepared prepared = newTransactionTemplate.execute(
                tx -> prepare(actorId, contractId, request, requestBody));

        // AC-136: change の作成（= upgrade 実行要求の受理）を監査する。予約 tx の commit 後
        // （PR6a の cancel と同じく、確定した DB の姿を追認する形で記録する）。
        audit(AuditEventType.BILLING_PLAN_CHANGE_REQUESTED, actorId, prepared.contract(), prepared.change(), null);

        sagaService.markCallingStripe(prepared.operationId());

        BillingPlanChangeGateway.PlanChangeApplyResult result;
        try {
            result = planChangeGateway.applyPlanChange(new BillingPlanChangeGateway.PlanChangeApplyCommand(
                    prepared.contract().getPspSubscriptionRef(), prepared.toBand().getStripePriceRef(),
                    prepared.memberCount(), prepared.operationId(),
                    BillingContractOperationSagaService.stripeIdempotencyKeyOf(prepared.operationId()),
                    BillingPlanChangeGateway.PRORATION_BEHAVIOR_ALWAYS_INVOICE,
                    BillingPlanChangeGateway.PAYMENT_BEHAVIOR_PENDING_IF_INCOMPLETE,
                    Map.of(BillingPlanChangeGateway.METADATA_OPERATION_ID_KEY, prepared.operationId().toString())));
        } catch (RuntimeException e) {
            // AC-46: Stripe 呼び出し失敗は change も FAILED にする（operation/pointer とは別 tx でよい。
            // 同一 tx を要求するのは webhook 確定側の AC-37〜41 のみ）。
            BillingContractChangeEntity[] failedHolder = new BillingContractChangeEntity[1];
            newTransactionTemplate.executeWithoutResult(tx -> {
                BillingContractChangeEntity failed = changeRepository
                        .findByIdAndDeletedAtIsNull(prepared.changeId())
                        .orElseThrow(() -> new IllegalStateException(
                                "change が見つからない: " + prepared.changeId()));
                failed.setStatus(BillingContractChangeStatus.FAILED);
                changeRepository.save(failed);
                failedHolder[0] = failed;
            });
            sagaService.failAndRelease(prepared.operationId(), "STRIPE_CALL_FAILED");
            // AC-138: Stripe 呼び出し自体の失敗も記録する（成功だけを監査しない・PR6a AC-66 と同方針）。
            audit(AuditEventType.BILLING_PLAN_CHANGE_FAILED, actorId, prepared.contract(), failedHolder[0],
                    "STRIPE_CALL_FAILED");
            throw new BusinessException(EntitlementErrorCode.STRIPE_UNAVAILABLE, e);
        }

        BillingPlanChangeGateway.PlanChangeApplyResult finalResult = result;
        return newTransactionTemplate.execute(tx -> finalizeChange(prepared.changeId(), finalResult));
    }

    // ============================================================
    // 予約（preview 消費 + Saga reserve + change 行の起票）
    // ============================================================

    private record Prepared(UUID operationId, UUID changeId, BillingContractEntity contract,
                            BillingContractChangeEntity change,
                            BillingPriceBandVersionEntity toBand, int memberCount) {
    }

    private Prepared prepare(long actorId, UUID contractId, BillingPlanChangeRequest request, String requestBody) {
        BillingChangePreviewEntity preview = changePreviewRepository
                .findByIdAndContractIdAndDeletedAtIsNull(request.previewId(), contractId)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND));
        // AC-11: 同一スコープでも別 actor の preview は 404（存在オラクルを残さない）。
        if (!preview.getActorId().equals(actorId)) {
            throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND);
        }

        Instant now = clock.instant();
        // AC-6/AC-9: 半開区間。既に消費済みでも同じコードで畳む。
        if (preview.getConsumedAt() != null || !preview.getExpiresAt().isAfter(now)) {
            throw new BillingConflictException(EntitlementErrorCode.PREVIEW_EXPIRED,
                    new BillingConflictDetails(Reason.PREVIEW_EXPIRED, null, preview.getId()));
        }

        BillingContractEntity contract = loadManageable(actorId, contractId);

        // AC-12: preview 発行時点の契約 version と現在の contract version の突合（提示された
        // request.version() ではなく、preview に焼き込んだ値と現在の DB の値を比べる。
        // 直前に client が取り直した version をそのまま渡す攻撃では CAS が無意味になるため）。
        if (!preview.getContractVersion().equals(contract.getVersion())) {
            throw BillingPlanChangePreviewService.conflict(Reason.CHANGE_CONFLICT, null);
        }

        int memberCount = scopeMemberCountService.countActiveMembers(
                contract.getScopeKind(), contract.getScopeId());
        // AC-14: 人数の再照合。
        if (!java.util.Objects.equals(preview.getMemberCount(), memberCount)) {
            throw BillingPlanChangePreviewService.conflict(Reason.CHANGE_CONFLICT, null);
        }

        // AC-16: 期間（current_period_end）の再照合。
        Instant currentPeriodEndInstant = contract.getCurrentPeriodEnd() == null
                ? null : contract.getCurrentPeriodEnd().atZone(clock.getZone()).toInstant();
        if (!java.util.Objects.equals(preview.getPeriodEnd(), currentPeriodEndInstant)) {
            throw BillingPlanChangePreviewService.conflict(Reason.CHANGE_CONFLICT, null);
        }

        // AC-20/AC-21: target band の現況を再確認する（RETIRED 化・削除・Price ref 消失）。
        BillingPriceBandVersionEntity toBand = bandRepository
                .findByIdAndDeletedAtIsNull(preview.getToPriceBandVersionId())
                .filter(band -> band.getStatus() == BillingPriceVersionStatus.ACTIVE)
                .filter(band -> band.getStripePriceRef() != null && !band.getStripePriceRef().isBlank())
                .orElseThrow(() -> BillingPlanChangePreviewService.conflict(Reason.CHANGE_CONFLICT, null));

        // AC-13/AC-15: 価格・税率の再照合（preview 発行時点の snapshot と現在の band を突合）。
        validateAmountUnchanged(preview, toBand);
        validateTaxUnchanged(preview, toBand);

        BillingPriceBandVersionEntity fromBand = bandRepository
                .findByIdAndDeletedAtIsNull(preview.getFromPriceBandVersionId())
                .orElseThrow(() -> BillingPlanChangePreviewService.conflict(Reason.CHANGE_CONFLICT, null));

        // AC-7/AC-8: 一回消費の CAS（実 DB の並行実行に対する唯一の防波堤）。
        int updated = changePreviewRepository.consumeIfValid(preview.getId(), now, preview.getVersion());
        if (updated == 0) {
            throw new BillingConflictException(EntitlementErrorCode.PREVIEW_EXPIRED,
                    new BillingConflictDetails(Reason.PREVIEW_EXPIRED, null, preview.getId()));
        }

        String requestHash = sha256(requestBody);
        OperationReservation reservation = sagaService.reserve(new ReserveCommand(
                contractId, BillingOperationKind.PLAN_CHANGE, request.version(),
                BillingOperationActorKind.USER, actorId, requestHash));

        BillingContractChangeEntity change = BillingContractChangeEntity.builder()
                .operationId(reservation.operationId())
                .contractId(contractId)
                .billingCustomerId(contract.getBillingCustomerId())
                .organizationId(contract.getOrganizationId())
                .kind(BillingContractChangeKind.UPGRADE)
                .status(BillingContractChangeStatus.PENDING_PAYMENT)
                .fromPlanKey(contract.getPlanKey())
                .toPlanKey(preview.getProductKey())
                .fromPriceBandVersionId(fromBand.getId())
                .toPriceBandVersionId(toBand.getId())
                .fromAmountIncludingTax(fromBand.getAmountIncludingTax())
                .toAmountIncludingTax(toBand.getAmountIncludingTax())
                .stripeSubscriptionRef(contract.getPspSubscriptionRef())
                .effectiveAt(now)
                .idempotencyKey(reservation.operationId().toString())
                .requestHash(requestHash)
                .version(0L)
                .createdBy(actorId)
                .build();
        changeRepository.save(change);

        return new Prepared(reservation.operationId(), change.getId(), contract, change, toBand, memberCount);
    }

    // ============================================================
    // Stripe 適用結果の反映（AC-32〜35 の走りだけ。厳密な原子性は第7隊）
    // ============================================================

    private BillingContractChangeResponse finalizeChange(
            UUID changeId, BillingPlanChangeGateway.PlanChangeApplyResult result) {
        BillingContractChangeEntity change = changeRepository.findByIdAndDeletedAtIsNull(changeId)
                .orElseThrow(() -> new IllegalStateException("change が見つからない: " + changeId));

        // AC-35: Stripe への同期呼び出しの最中に invoice.paid webhook が先着し、既に APPLIED/FAILED へ
        // 確定していることがある。API は現状を読むだけで、webhook の確定を PENDING_PAYMENT へ
        // 巻き戻してはならない（確定の主体は webhook 側・E6'）。
        if (change.getStatus() == BillingContractChangeStatus.PENDING_PAYMENT
                || change.getStatus() == BillingContractChangeStatus.REQUIRES_ACTION) {
            change.setStripeInvoiceRef(result.invoiceRef());
            if (result.pendingUpdatePresent()) {
                change.setStatus(BillingContractChangeStatus.REQUIRES_ACTION);
                change.setPendingUpdateExpiresAt(result.pendingUpdateExpiresAt());
                change.setPendingUpdateTargetSnapshot(result.pendingUpdateTargetSnapshot());
                change.setExpiresAt(result.pendingUpdateExpiresAt());
            } else {
                // E6': 同期成功でも invoice.paid を待つ（第9隊の webhook が APPLIED へ確定させる）。
                change.setStatus(BillingContractChangeStatus.PENDING_PAYMENT);
            }
            changeRepository.save(change);
        }
        return new BillingContractChangeResponse(change.getId(), change.getStatus(), change.getEffectiveAt());
    }

    // ============================================================
    // 再照合ヘルパ
    // ============================================================

    private void validateAmountUnchanged(BillingChangePreviewEntity preview, BillingPriceBandVersionEntity toBand) {
        JsonNode amount = readJson(preview.getAmountSnapshot());
        long snapshotAmount = amount.path("toAmountIncludingTax").asLong(Long.MIN_VALUE);
        if (snapshotAmount != toBand.getAmountIncludingTax()) {
            throw BillingPlanChangePreviewService.conflict(Reason.CHANGE_CONFLICT, null);
        }
    }

    private void validateTaxUnchanged(BillingChangePreviewEntity preview, BillingPriceBandVersionEntity toBand) {
        JsonNode tax = readJson(preview.getTaxSnapshot());
        int snapshotRate = tax.path("taxRateBasisPoints").asInt(Integer.MIN_VALUE);
        if (snapshotRate != toBand.getTaxRateBasisPoints()) {
            throw BillingPlanChangePreviewService.conflict(Reason.CHANGE_CONFLICT, null);
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse billing preview snapshot", e);
        }
    }

    private BillingContractEntity loadManageable(long actorId, UUID contractId) {
        BillingContractEntity contract = billingContractRepository.findByIdAndDeletedAtIsNull(contractId)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND));
        if (billingAccessGuard.canManageByActorId(actorId, contract.getScopeKind(), contract.getScopeId())) {
            return contract;
        }
        throw billingAccessGuard.isScopeMember(actorId, contract.getScopeKind(), contract.getScopeId())
                ? new BusinessException(EntitlementErrorCode.SCOPE_FORBIDDEN)
                : new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND);
    }

    /**
     * 監査を1件記録する（AC-136/AC-138）。
     *
     * <p>metadata に載せるのは <b>scopeKind / scopeId / contractId / changeId / fromPlanKey /
     * toPlanKey / errorCode</b> だけである。Stripe の raw payload・clientSecret・カード情報・住所は
     * 一切載せない（AC-139・{@code BillingContractCancelApplicationService#audit} と同型）。</p>
     */
    private void audit(AuditEventType eventType, long actorId, BillingContractEntity contract,
                       BillingContractChangeEntity change, String errorCode) {
        EntitlementScopeKind scopeKind = contract.getScopeKind();
        Long scopeId = contract.getScopeId();
        StringBuilder metadata = new StringBuilder()
                .append("{\"scopeKind\":\"").append(scopeKind.name())
                .append("\",\"scopeId\":").append(scopeId)
                .append(",\"contractId\":\"").append(contract.getId()).append('"')
                .append(",\"changeId\":\"").append(change.getId()).append('"')
                .append(",\"fromPlanKey\":\"").append(change.getFromPlanKey()).append('"')
                .append(",\"toPlanKey\":\"").append(change.getToPlanKey()).append('"');
        if (errorCode != null) {
            metadata.append(",\"errorCode\":\"").append(errorCode).append('"');
        }
        metadata.append('}');
        auditLogService.record(eventType.name(), actorId, null,
                scopeKind == EntitlementScopeKind.TEAM ? scopeId : null,
                scopeKind == EntitlementScopeKind.ORG ? scopeId : null,
                null, null, null, metadata.toString());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
