package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingContractCancelService;
import com.mannschaft.app.billing.BillingContractCancelService.CancelView;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.BillingContractService;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.api.dto.BillingContractCancelResponse;
import com.mannschaft.app.common.BusinessException;
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

        // AC-25: 無償契約（PSP 紐付なし）は従来どおり即時失効。期末解約の Saga には載せない（D2・AC-15）。
        if (contract.getPspSubscriptionRef() == null || contract.getPriceJpySnapshot() == null) {
            billingContractService.cancelContract(
                    contract.getScopeKind(), contract.getScopeId(), contractId, actorId);
            return toResponse(billingContractCancelService.viewOf(reload(contractId)));
        }

        return toResponse(billingContractCancelService.scheduleCancel(contractId, version, actorId));
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
        loadManageable(actorId, contractId);
        return toResponse(billingContractCancelService.resumeCancel(contractId, version, actorId));
    }

    // ============================================================
    // 内部
    // ============================================================

    /** 契約を読み、操作者が当該スコープの課金を管理できることを確かめる（できなければ 404 秘匿・AC-51）。 */
    private BillingContractEntity loadManageable(long actorId, UUID contractId) {
        BillingContractEntity contract = reload(contractId);
        if (!billingAccessGuard.canManageByActorId(
                actorId, contract.getScopeKind(), contract.getScopeId())) {
            throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND);
        }
        return contract;
    }

    private BillingContractEntity reload(UUID contractId) {
        return billingContractRepository.findByIdAndDeletedAtIsNull(contractId)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND));
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
