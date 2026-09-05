package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.escrow.event.EscrowCancelledEvent;
import com.mannschaft.app.payment.escrow.event.EscrowPaymentRequiredEvent;
import com.mannschaft.app.payment.stripe.CaptureMethod;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * F22.1 謝礼決済 第三陣: escrow ライフサイクルの堅牢化（未確認放置の自動取消・HELD 昇格）。
 *
 * <p>1 件の escrow に対する状態遷移を {@code PESSIMISTIC_WRITE} 行ロック下で冪等に行う単位処理を提供する。
 * バッチ（{@link EscrowLifecycleBatch}）と {@code account.updated} ハンドラ
 * （{@link com.mannschaft.app.payment.connect.ConnectAccountService}）の双方から呼ばれる。各メソッドは
 * 独立トランザクション（{@link Propagation#REQUIRES_NEW}）で実行し、1 件の失敗が他件を巻き込まないようにする
 * （呼び出し側がループで個別に try/catch する前提・設計書 02 §5.2 / §5.4）。</p>
 *
 * <h3>冪等・行ロック</h3>
 * <p>対象を {@link EscrowTransactionRepository#findByIdForUpdate}（行ロック）で取り直し、ロック取得後に status を
 * 再判定する。既に CANCELLED/CAPTURED 等の終端/後段状態なら no-op（バッチの抽出と実処理の間に webhook 等で
 * 状態が変わるレースを物理的に直列化）。Stripe 呼び出しには冪等キーを渡す（二重防御）。</p>
 *
 * <p>本陣の範囲は「未確認放置の取消＋通知」「HELD 昇格＋札主通知」まで。7 日 fallback の即時払い切替本体・
 * FE・Controller は後続陣（CLAUDE.md 障害対応＝症状を隠さない・段階的実装）。</p>
 *
 * <h3>通知の境界（Issue #2990 L7）</h3>
 * <p>取消・昇格に伴う通知は本サービスの業務トランザクションに参加させない。業務TX内では
 * {@link com.mannschaft.app.payment.escrow.event.EscrowCancelledEvent} /
 * {@link com.mannschaft.app.payment.escrow.event.EscrowPaymentRequiredEvent} を publish するだけとし、
 * 実配送は commit 後に
 * {@link com.mannschaft.app.payment.escrow.event.EscrowLifecycleNotificationListener} が行う。
 * 是正前は通知の失敗が本サービスのトランザクションへ伝播し、<b>Stripe 側の与信取消／PaymentIntent 作成は
 * 成立したまま DB だけが巻き戻る</b>状態だった。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/02_api_design.md §5.2 / §5.4</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EscrowLifecycleService {

    private final EscrowTransactionRepository escrowTransactionRepository;
    private final ConnectAccountRepository connectAccountRepository;
    private final StripePaymentProvider stripePaymentProvider;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 札主未 confirm の {@link EscrowStatus#PENDING_CONFIRMATION} 放置を自動取消する（設計書 02 §5.2）。
     *
     * <p>行ロックで取り直し、PENDING_CONFIRMATION のみ取消する（既に AUTHORIZED へ confirm 済み等は no-op）。
     * PI 作成済みのため {@code PaymentIntent.cancel}（{@link StripePaymentProvider#cancelAuthorization}）で
     * 与信を取消し（札主に課金は発生しない）、CANCELLED 化して札主・応じ手へ通知する。</p>
     *
     * @param escrowId 対象 escrow ID
     * @return 取消を実施した場合 true（既に対象外で no-op の場合 false）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean cancelExpiredPendingConfirmation(UUID escrowId) {
        EscrowTransactionEntity escrow = lockOrNull(escrowId);
        if (escrow == null || escrow.getStatus() != EscrowStatus.PENDING_CONFIRMATION) {
            return false;
        }
        cancelWithStripe(escrow);
        publishCancelled(escrow, EscrowCancelledEvent.Reason.PENDING_CONFIRMATION_EXPIRED);
        log.info("未確認放置の謝礼を自動取消 CANCELLED（PENDING_CONFIRMATION 期限超過）: escrowId={}", escrowId);
        return true;
    }

    /**
     * hold 失効/間近の {@link EscrowStatus#HELD}・{@link EscrowStatus#AUTHORIZED} を自動取消する
     * （設計書 02 §5.2 / §5.4）。
     *
     * <p><b>本陣の扱い:</b> HELD（onboarding 未完で 72h 超過）と AUTHORIZED（与信失効間近で未 capture）の双方を
     * 取消＋通知する。AUTHORIZED の「完了時即時払いへ切替」本体は後続陣（7 日 fallback 即時払い本体）であり、
     * 本陣では失効分の取消＋通知までを行う（capture できない与信を放置せず観測可能化・症状を隠さない）。
     * {@link EscrowStatus#DISPUTED}（先 capture 後返金戦略・§5.4）は本陣では扱わない（取消対象外）。</p>
     *
     * @param escrowId 対象 escrow ID
     * @return 取消を実施した場合 true（既に対象外で no-op の場合 false）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean cancelExpiredHeldOrAuthorized(UUID escrowId) {
        EscrowTransactionEntity escrow = lockOrNull(escrowId);
        if (escrow == null) {
            return false;
        }
        EscrowStatus status = escrow.getStatus();
        if (status == EscrowStatus.HELD) {
            cancelWithStripe(escrow);
            publishCancelled(escrow, EscrowCancelledEvent.Reason.HELD_EXPIRED);
            log.info("受取口座未登録のまま hold 失効した謝礼を自動取消 CANCELLED（HELD）: escrowId={}", escrowId);
            return true;
        }
        if (status == EscrowStatus.AUTHORIZED) {
            cancelWithStripe(escrow);
            publishCancelled(escrow, EscrowCancelledEvent.Reason.AUTHORIZATION_EXPIRED);
            log.info("与信失効間近で未 capture の謝礼を自動取消 CANCELLED（AUTHORIZED）: escrowId={}", escrowId);
            return true;
        }
        return false;
    }

    /**
     * 募集の取下げに連動して、capture 前のエスクローを取消す。
     *
     * <p>募集は COMPLETED になるまで capture されないため、取下げ可能な DRAFT/OPEN/FULL/CLOSED に
     * 対応する escrow は DEFERRED/PENDING_CONFIRMATION/AUTHORIZED/HELD のいずれかである。
     * 行ロック下で再判定し、既に別経路で終端状態へ遷移していれば no-op とする。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean cancelForRecruitmentCancellation(UUID escrowId) {
        EscrowTransactionEntity escrow = lockOrNull(escrowId);
        if (escrow == null) {
            return false;
        }
        EscrowStatus status = escrow.getStatus();
        if (status != EscrowStatus.DEFERRED
                && status != EscrowStatus.PENDING_CONFIRMATION
                && status != EscrowStatus.AUTHORIZED
                && status != EscrowStatus.HELD) {
            return false;
        }
        cancelWithStripe(escrow);
        publishCancelled(escrow, EscrowCancelledEvent.Reason.RECRUITMENT_CANCELLED);
        log.info("募集取下げに連動して未captureの与信を取消: escrowId={}, previousStatus={}", escrowId, status);
        return true;
    }

    /**
     * 受取口座の onboarding 完了（payouts_enabled=true）に伴い、{@link EscrowStatus#HELD} escrow を昇格する
     * （設計書 02 §5.2）。HELD（PI 未作成）に対し manual-capture の Destination PaymentIntent を作成し
     * {@link EscrowStatus#PENDING_CONFIRMATION}（札主の confirm 待ち）へ遷移させ、札主へ決済確認を依頼する。
     *
     * <p>冪等: 行ロックで取り直し、HELD かつ PI 未作成のときのみ昇格する（既に昇格済み＝PENDING_CONFIRMATION 以降や
     * PI 設定済みなら no-op）。札主の Stripe Customer が未解決（{@code payer_stripe_customer_id=null}）の場合は
     * PI を作成できないため HELD のまま据え置き、症状を隠さず警告ログを残す（昇格しない＝false 返却）。</p>
     *
     * @param escrowId 対象 escrow ID
     * @return 昇格を実施した場合 true（対象外/前提不足で no-op の場合 false）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean promoteHeldEscrow(UUID escrowId) {
        EscrowTransactionEntity escrow = lockOrNull(escrowId);
        if (escrow == null
                || escrow.getStatus() != EscrowStatus.HELD
                || escrow.getStripePaymentIntentId() != null) {
            return false;
        }

        ConnectAccountEntity payee = connectAccountRepository.findById(escrow.getPayeeConnectAccountId())
                .orElse(null);
        if (payee == null || !Boolean.TRUE.equals(payee.getPayoutsEnabled())) {
            // 昇格条件（payee の payouts_enabled=true）を満たさない。据え置き（呼び出し側の前提崩れ・症状を隠さない）。
            log.warn("HELD 昇格スキップ: payee 口座が解決不能/未 READY: escrowId={}, payeeAccountId={}",
                    escrowId, escrow.getPayeeConnectAccountId());
            return false;
        }
        if (escrow.getPayerStripeCustomerId() == null || escrow.getPayerStripeCustomerId().isBlank()) {
            // 札主の Stripe Customer 未解決では PI を作れない。HELD 据え置きで観測可能化（握りつぶさない）。
            log.warn("HELD 昇格スキップ: 札主の Stripe Customer 未解決のため PI 作成不能。HELD 据え置き: escrowId={}",
                    escrowId);
            return false;
        }

        // manual-capture の Destination PaymentIntent を作成（authorize() と同一の冪等キー体系）。
        String idempotencyKey = "escrow-" + escrow.getSourceId() + "-" + escrow.getSourceParticipantId();
        StripePaymentProvider.PaymentIntentInfo pi = stripePaymentProvider.createDestinationPaymentIntent(
                escrow.getAmount(), escrow.getCurrency(), escrow.getPayerStripeCustomerId(),
                escrow.getApplicationFeeAmount(), payee.getStripeAccountId(), CaptureMethod.MANUAL, idempotencyKey);

        escrow.setStripePaymentIntentId(pi.paymentIntentId());
        escrow.setStatus(EscrowStatus.PENDING_CONFIRMATION);
        escrow.setHoldExpiresAt(null); // hold 失効基準は AUTHORIZED 昇格（confirm）時に webhook が再度刻む。
        escrowTransactionRepository.save(escrow);

        // 通知は業務TXに参加させない。commit 後に EscrowLifecycleNotificationListener が配送する（#2990 L7）。
        eventPublisher.publishEvent(new EscrowPaymentRequiredEvent(escrow.getId()));

        log.info("HELD escrow を昇格（受取口座登録完了・PI 作成→PENDING_CONFIRMATION・札主 confirm 待ち）: "
                        + "escrowId={}, piId={}", escrowId, pi.paymentIntentId());
        return true;
    }

    /** 行ロックで取り直す（存在しなければ警告して null）。 */
    private EscrowTransactionEntity lockOrNull(UUID escrowId) {
        Optional<EscrowTransactionEntity> found = escrowTransactionRepository.findByIdForUpdate(escrowId);
        if (found.isEmpty()) {
            log.warn("escrow ライフサイクル処理: 対象 escrow が存在しません（処理間に削除/未存在）: escrowId={}", escrowId);
            return null;
        }
        return found.get();
    }

    /**
     * 与信取消（capture 前）を行い CANCELLED 化する。PI 未作成（HELD で onboarding 未完）の場合は Stripe を呼ばず
     * 状態のみ CANCELLED にする（capture 前ゆえ札主に課金は発生しない・設計書 02 §6.1 と同戦略）。
     */
    private void cancelWithStripe(EscrowTransactionEntity escrow) {
        if (escrow.getStripePaymentIntentId() != null) {
            stripePaymentProvider.cancelAuthorization(escrow.getStripePaymentIntentId(),
                    "cancel-" + escrow.getId());
        }
        escrow.setStatus(EscrowStatus.CANCELLED);
        escrow.setCancelledAt(LocalDateTime.now());
        escrowTransactionRepository.save(escrow);
    }

    /**
     * 取消の事実（escrow ID と理由）だけをイベントに積む（#2990 L7）。件名・本文の組み立てと配送は
     * 業務TXの commit 後に {@code EscrowLifecycleNotificationListener} が行う。
     */
    private void publishCancelled(EscrowTransactionEntity escrow, EscrowCancelledEvent.Reason reason) {
        eventPublisher.publishEvent(new EscrowCancelledEvent(escrow.getId(), reason));
    }
}
