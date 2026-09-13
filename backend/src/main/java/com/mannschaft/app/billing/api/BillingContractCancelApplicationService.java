package com.mannschaft.app.billing.api;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.billing.BillingContractCancelService;
import com.mannschaft.app.billing.BillingContractCancelService.CancelView;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.BillingContractService;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.BillingContractCancelResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Billing Center PR6a: 解約（{@code POST …/cancel}）／解約撤回（{@code DELETE …/cancel}）の
 * アプリケーションサービス。
 *
 * <p>担うのは (1) 契約 → スコープ解決と認可、(2) 無償契約の即時失効経路への振り分け（AC-25）、
 * (3) {@link BillingContractCancelResponse} への組み立て、の3点だけである。
 * Saga のトランザクション分割と Stripe 呼び出しの順序は
 * {@link BillingContractCancelService} が持つ。</p>
 *
 * <p><b>認可（AC-51）</b>: 契約 ID から所属スコープを解決し、操作者が当該スコープの課金を
 * 管理できなければ <b>404</b> に畳む（403 と 404 を撃ち分けると契約 ID の存在オラクルが残り、
 * PR5 で IDOR になった前科がある）。判定は {@link BillingAccessGuard#canManageByActorId} に一元化する。</p>
 *
 * <p><b>本クラスに {@code @Transactional} を付けてはならない</b>（Saga の tx1/tx2 を吸収してしまう）。</p>
 */
@Service
@RequiredArgsConstructor
public class BillingContractCancelApplicationService {

    private final BillingContractRepository billingContractRepository;
    private final BillingContractCancelService billingContractCancelService;
    private final BillingContractService billingContractService;
    private final BillingAccessGuard billingAccessGuard;
    private final AuditLogService auditLogService;

    /**
     * 認可だけを先に済ませ、契約の所属 scope を返す（AC-54）。
     *
     * <p><b>冪等台帳より前に呼ばれることが本メソッドの存在理由</b>である。PR5 の
     * {@code BillingCustomerPortalController} は認可より先に {@code begin} を呼んでおり、
     * 権限の無い要求が台帳に行を作れたため、以後その actor / key が 409 を返す
     * <b>存在オラクル</b>になっていた。controller はこのメソッドの戻り値を
     * 受け取らないと冪等処理へ進めない形にしてある。</p>
     *
     * @param actorId    操作者
     * @param contractId 対象契約
     * @return 契約の所属 scope（レート制限のバケットキーにもなる・AC-56）
     * @throws BusinessException scope 外なら 404、scope 内で権限不足なら 403
     */
    public CancelAuthorization authorize(long actorId, UUID contractId) {
        BillingContractEntity contract = loadManageable(actorId, contractId);
        return new CancelAuthorization(contract.getScopeKind(), contract.getScopeId());
    }

    /**
     * 認可が通ったことの証（controller が冪等台帳へ進むための通行手形）。
     *
     * @param scopeKind 契約の scope 種別
     * @param scopeId   契約の scope ID
     */
    public record CancelAuthorization(EntitlementScopeKind scopeKind, Long scopeId) {
    }

    /**
     * 解約（期末解約の予約。無償契約は即時失効）。
     *
     * @param actorId    操作者
     * @param contractId 対象契約
     * @param version    CAS 期待値
     * @return 解約後の応答
     */
    public BillingContractCancelResponse cancel(long actorId, UUID contractId, Long version) {
        BillingContractEntity contract = loadManageable(actorId, contractId);
        // AC-66: 「作成」= 解約要求の受理。認可が通った時点で必ず残す（この後どこで落ちても
        // 「利用者が解約を試みた」事実が消えない）。
        audit(AuditEventType.BILLING_CANCEL_REQUESTED, actorId, contract, null);

        try {
            // AC-25: 無償契約（PSP 紐付なし）は従来どおり即時失効。期末解約の Saga には載せない（D2・AC-15）。
            BillingContractCancelResponse response;
            if (contract.getPspSubscriptionRef() == null || contract.getPriceJpySnapshot() == null) {
                billingContractService.cancelContract(
                        contract.getScopeKind(), contract.getScopeId(), contractId, actorId);
                response = toResponse(billingContractCancelService.viewOf(contractId));
            } else {
                response = toResponse(
                        billingContractCancelService.scheduleCancel(contractId, version, actorId));
            }
            audit(AuditEventType.BILLING_CANCEL_APPLIED, actorId, contract, null);
            return response;
        } catch (RuntimeException e) {
            // AC-66: 失敗も記録する（成功だけを監査しない）。握り潰さず必ず再送出する。
            audit(AuditEventType.BILLING_CANCEL_FAILED, actorId, contract, errorCodeOf(e));
            throw e;
        }
    }

    /**
     * 解約撤回。
     *
     * @param actorId    操作者
     * @param contractId 対象契約
     * @param version    CAS 期待値
     * @return 撤回後の応答
     */
    public BillingContractCancelResponse resume(long actorId, UUID contractId, Long version) {
        BillingContractEntity contract = loadManageable(actorId, contractId);
        audit(AuditEventType.BILLING_CANCEL_RESUME_REQUESTED, actorId, contract, null);

        try {
            BillingContractCancelResponse response =
                    toResponse(billingContractCancelService.resumeCancel(contractId, version, actorId));
            audit(AuditEventType.BILLING_CANCEL_RESUME_APPLIED, actorId, contract, null);
            return response;
        } catch (RuntimeException e) {
            audit(AuditEventType.BILLING_CANCEL_RESUME_FAILED, actorId, contract, errorCodeOf(e));
            throw e;
        }
    }

    // ============================================================
    // 内部
    // ============================================================

    /** 契約を読み、操作者が当該スコープの課金を管理できることを確かめる（AC-51〜53）。 */
    private BillingContractEntity loadManageable(long actorId, UUID contractId) {
        BillingContractEntity contract = reload(contractId);
        requireManageable(actorId, contract);
        return contract;
    }

    /**
     * 認可の唯一の判定本体。許可されていなければ 404（scope 外）か 403（scope 内で権限不足）を投げる。
     *
     * <ul>
     *   <li><b>404</b>: 操作者が当該 scope の構成員ですらない。契約 ID の存在を悟らせない
     *       （PR5 で 409 を返して IDOR になった前科・AC-51）。存在しない契約 ID と同じ応答になる。</li>
     *   <li><b>403</b>: 構成員ではあるが課金を管理できない（MEMBER ロール・permission group 未付与の
     *       DEPUTY_ADMIN・AC-52 / AC-53）。存在は既に相手に見えているので秘匿の必要がない。</li>
     * </ul>
     */
    private void requireManageable(long actorId, BillingContractEntity contract) {
        EntitlementScopeKind scopeKind = contract.getScopeKind();
        Long scopeId = contract.getScopeId();
        if (billingAccessGuard.canManageByActorId(actorId, scopeKind, scopeId)) {
            return;
        }
        throw billingAccessGuard.isScopeMember(actorId, scopeKind, scopeId)
                ? new BusinessException(EntitlementErrorCode.SCOPE_FORBIDDEN)
                : new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND);
    }

    private BillingContractEntity reload(UUID contractId) {
        return billingContractRepository.findByIdAndDeletedAtIsNull(contractId)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND));
    }

    /**
     * 監査を1件記録する（AC-66 / AC-67）。
     *
     * <p>metadata に載せるのは <b>scopeKind / scopeId / contractId（object ref）/ errorCode</b> だけである。
     * Stripe の raw payload・Portal URL・client secret・カード情報・住所は一切載せない（AC-67・正本 §370）。
     * 例外のメッセージ本文も載せない —— Stripe の応答文言には ID や URL が混じりうるため、
     * アプリ側で採番した {@link ErrorCode#getCode()} のみを残す。</p>
     */
    private void audit(AuditEventType eventType, long actorId,
                       BillingContractEntity contract, String errorCode) {
        EntitlementScopeKind scopeKind = contract.getScopeKind();
        Long scopeId = contract.getScopeId();
        StringBuilder metadata = new StringBuilder()
                .append("{\"scopeKind\":\"").append(scopeKind.name())
                .append("\",\"scopeId\":").append(scopeId)
                .append(",\"contractId\":\"").append(contract.getId()).append('"');
        if (errorCode != null) {
            metadata.append(",\"errorCode\":\"").append(errorCode).append('"');
        }
        metadata.append('}');
        auditLogService.record(eventType.name(), actorId, null,
                scopeKind == EntitlementScopeKind.TEAM ? scopeId : null,
                scopeKind == EntitlementScopeKind.ORG ? scopeId : null,
                null, null, null, metadata.toString());
    }

    /** アプリのエラーコードだけを取り出す（例外メッセージ本文は監査へ出さない・AC-67）。 */
    private String errorCodeOf(RuntimeException e) {
        if (e instanceof BusinessException be) {
            ErrorCode code = be.getErrorCode();
            return code == null ? "UNKNOWN" : code.getCode();
        }
        return "UNEXPECTED";
    }

    private BillingContractCancelResponse toResponse(CancelView view) {
        return new BillingContractCancelResponse(
                view.contractId(),
                view.contractStatus() == null ? null : view.contractStatus().name(),
                view.cancelScheduled()
                        ? BillingContractCancelResponse.STATUS_SCHEDULED
                        : BillingContractCancelResponse.STATUS_ACTIVE,
                view.scheduledAt(),
                view.endAt(),
                view.endAt(),
                view.version(),
                view.canCancel(),
                view.canResume());
    }
}
