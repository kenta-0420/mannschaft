package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingContractChangeEntity;
import com.mannschaft.app.billing.BillingContractChangeRepository;
import com.mannschaft.app.billing.BillingContractChangeStatus;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.BillingPlanChangeGateway;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.api.BillingConflictException.BillingConflictDetails;
import com.mannschaft.app.billing.api.BillingConflictException.Reason;
import com.mannschaft.app.common.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Billing Center PR6b-1 C群: 3DS payment-action の発行側（AC-48〜54/AC-61/AC-62/AC-70/AC-71・第8隊）。
 *
 * <p>本クラスの担当範囲は「change の状態確認 → 都度 Stripe から client secret を取得 → 戻り用の
 * return state を短命で発行する」ことだけである。状態遷移（{@code APPLIED}/{@code FAILED} への確定）は
 * webhook（第9隊）が行うため、本クラスは change 行を一切書き換えない（AC-71: 再発行しても状態は進まない）。</p>
 *
 * <h2>E5'（AC-70）の 404/409 の切り分け</h2>
 * <ul>
 *     <li>contract が存在しない、又は actor が contract の scope の外側 → 404（存在オラクルを残さない）</li>
 *     <li>change 自体が存在しない（他 contract の change・未知 changeId） → 409 CHANGE_CONFLICT（AC-52。
 *         「同一 contract 内に対象が無い」は IDOR ではなく通常の競合として扱う）</li>
 *     <li>change は存在するが {@code created_by != actor}（同一スコープの別 actor が起票） → 404（AC-70）</li>
 *     <li>change の status が {@code REQUIRES_ACTION} 以外 → 409 CHANGE_CONFLICT（AC-49〜51）</li>
 * </ul>
 *
 * <h2>E3'/AC-61/AC-62 の cookie 期限</h2>
 * <p>{@code min(change.pendingUpdateExpiresAt, now+15分)} を採り、発行する return state token 自身の
 * {@code exp} にも同じ瞬間を渡す（{@link BillingReturnStateService#issue} は最長24時間まで許すため、
 * 呼び側で絞らなければ token だけ長命な検体ができてしまう）。</p>
 */
@Service
public class BillingPaymentActionService {

    /** AC-61: pending_update.expires_at が遠いときに頭打ちにする上限。 */
    static final Duration COOKIE_MAX_LIFETIME = Duration.ofMinutes(15);

    private final BillingContractRepository billingContractRepository;
    private final BillingContractChangeRepository changeRepository;
    private final BillingAccessGuard billingAccessGuard;
    private final BillingPlanChangeGateway planChangeGateway;
    private final BillingReturnStateService returnStateService;
    private final Clock clock;

    public BillingPaymentActionService(
            BillingContractRepository billingContractRepository,
            BillingContractChangeRepository changeRepository,
            BillingAccessGuard billingAccessGuard,
            BillingPlanChangeGateway planChangeGateway,
            BillingReturnStateService returnStateService,
            @Qualifier("wallClock") Clock clock) {
        this.billingContractRepository = billingContractRepository;
        this.changeRepository = changeRepository;
        this.billingAccessGuard = billingAccessGuard;
        this.planChangeGateway = planChangeGateway;
        this.returnStateService = returnStateService;
        this.clock = clock;
    }

    /**
     * payment-action を都度取得する。
     *
     * @param actorId    要求者
     * @param contractId 対象契約
     * @param changeId   対象 change
     * @return 応答本文と発行した cookie の材料
     */
    public Result retrieve(long actorId, UUID contractId, UUID changeId) {
        BillingContractEntity contract = billingContractRepository.findByIdAndDeletedAtIsNull(contractId)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND));

        // E5'（前段）: 他スコープ（scope の外側）は 404。存在オラクルを残さない。
        if (!billingAccessGuard.isScopeMember(actorId, contract.getScopeKind(), contract.getScopeId())) {
            throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND);
        }

        // AC-52: change 自体が無ければ 409（IDOR ではなく通常の競合として畳む）。
        BillingContractChangeEntity change = changeRepository.findByIdAndDeletedAtIsNull(changeId)
                .filter(c -> c.getContractId().equals(contractId))
                .orElseThrow(BillingPaymentActionService::conflict);

        // AC-70（E5' 本体）: 同一スコープの別 actor が起票した change は 404（Stripe/cookie/冪等台帳の
        // いずれにも触れる前に弾く。以降の処理は一切実行しない）。
        if (!change.getCreatedBy().equals(actorId)) {
            throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND);
        }

        // AC-49〜51: REQUIRES_ACTION 以外は 409。
        if (change.getStatus() != BillingContractChangeStatus.REQUIRES_ACTION) {
            throw conflict();
        }

        // AC-48/54: Stripe から都度取得する（DB には一切保存しない）。
        BillingPlanChangeGateway.PaymentAction action = planChangeGateway
                .retrievePaymentAction(change.getStripeSubscriptionRef(), change.getStripeInvoiceRef())
                .orElseThrow(BillingPaymentActionService::conflict);

        Instant now = clock.instant();
        Instant capped = now.plus(COOKIE_MAX_LIFETIME);
        Instant chosen = (change.getPendingUpdateExpiresAt() != null
                && change.getPendingUpdateExpiresAt().isBefore(capped))
                ? change.getPendingUpdateExpiresAt() : capped;
        long maxAgeSeconds = Math.max(0L, Duration.between(now, chosen).getSeconds());

        // AC-71: 要求のたびに新しい nonce で token を切り直す（使い回すと片方の消費が他方を殺す）。
        String token = returnStateService.issue(new BillingReturnStateService.ReturnState(
                BillingReturnStateService.Purpose.PAYMENT_ACTION_RETURN,
                contract.getScopeKind(), contract.getScopeId(), actorId, null, null, null, null,
                now, chosen, UUID.randomUUID().toString()));

        return new Result(action.type(), action.clientSecret(), action.expiresAt(), token, maxAgeSeconds);
    }

    private static BillingConflictException conflict() {
        return new BillingConflictException(EntitlementErrorCode.CHANGE_CONFLICT,
                new BillingConflictDetails(Reason.CHANGE_CONFLICT, null, null));
    }

    /**
     * @param type              追加認証の種別
     * @param clientSecret      都度取得した client secret（呼び出し元は保存しない）
     * @param expiresAt         追加認証の期限（Stripe 由来）
     * @param cookieToken       発行した return state token
     * @param cookieMaxAgeSeconds AC-61/62: cookie の Max-Age（token の exp と同値）
     */
    public record Result(String type, String clientSecret, Instant expiresAt,
                         String cookieToken, long cookieMaxAgeSeconds) {
    }
}
