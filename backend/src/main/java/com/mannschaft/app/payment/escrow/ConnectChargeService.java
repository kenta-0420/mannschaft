package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.FeeBreakdown;
import com.mannschaft.app.payment.PaymentFeeCalculator;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.PayeeScopeResolver;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.stripe.CaptureMethod;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * F22.1 統一決済 P2-b: 共通送金サービス（与信＝authorize の中核・設計書 02 §0 / §5.1）。
 *
 * <p>謝礼（{@link EscrowSourceKind#RECRUITMENT}・エスクローモード）の与信を一元化する。
 * 手数料折半（5%＝支払者2.5%+受取側2.5%）は {@link PaymentFeeCalculator} に集約し、本サービスでは
 * 数式を再実装しない（設計書 02 §3.5・散在禁止）。</p>
 *
 * <p><b>本波（P2-b 第二波）の範囲は与信（authorize）まで。</b> capture（払出）・返金（refund）は
 * 次Phase（P2-c）であり本サービスには実装しない。</p>
 *
 * <p>分岐（設計書 02 §5.1 / §5.2）:</p>
 * <ul>
 *   <li>受取側 {@code payouts_enabled=false}（onboarding 未完了）→ {@link EscrowStatus#HELD}。
 *       PaymentIntent は<b>作成しない</b>（口座登録完了後に capture 再開）。{@code hold_expires_at=now+72h}。</li>
 *   <li>受取側 {@code payouts_enabled=true} → manual-capture の Destination PaymentIntent を作成し
 *       {@link EscrowStatus#AUTHORIZED}。{@code clientSecret} を支払者本人へ返す（カード confirm 用）。</li>
 * </ul>
 *
 * <p>認可/IDOR: {@code actorUserId} が非 null（明示 API 経路）の場合のみ札主 scope の ADMIN を
 * {@link AccessControlService} で検証する（TEAM=checkPermission / ORG=checkAdminOrHasPermission・
 * 設計書 03 §3）。無関係者は {@link ConnectPaymentErrorCode#PAYMENT_FORBIDDEN}（403）。
 * {@code actorUserId=null}（応募成立イベント駆動の system 経路・設計書 02 §1 行#4「外部API無し」）は
 * 認可済みフロー前提でスキップする。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ConnectChargeService {

    /** HELD（受取側 onboarding 未完了）の与信保持期限（72h・設計書 02 §5.2）。 */
    static final long HELD_GRACE_HOURS = 72L;

    /** AUTHORIZED（与信成立）の hold 期限（最大7日・設計書 02 §5.1）。 */
    static final long AUTHORIZED_HOLD_DAYS = 7L;

    /** TEAM/ORG scope ADMIN 判定に用いる権限名（Connect onboarding と同等の管理権限）。 */
    static final String PERMISSION_MANAGE_PAYMENT = "MANAGE_RECRUITMENTS";

    private final EscrowTransactionRepository escrowTransactionRepository;
    private final ConnectAccountRepository connectAccountRepository;
    private final PaymentFeeCalculator paymentFeeCalculator;
    private final StripePaymentProvider stripePaymentProvider;
    private final AccessControlService accessControlService;

    /**
     * 謝礼の与信を開始する（設計書 02 §5.1）。
     *
     * @param cmd 与信コマンド
     * @return 与信結果（AUTHORIZED 時は clientSecret 同梱・HELD 時は null）
     */
    public AuthorizeChargeResult authorize(AuthorizeChargeCommand cmd) {
        // 認可（明示 API 経路のみ）: 受領主体（=札主 scope）の ADMIN/権限保有者のみ与信開始可（IDOR・03 §3/§4）。
        authorizeActorIfPresent(cmd);

        // 冪等: 同一応募（source_kind × source_id × source_participant_id）の二重与信を 1 件に収束（02 §9）。
        // 申し送り（P2-c で決定）: ここはアプリ層の冪等チェックのみで、DB レベルの
        // uq(source_kind, source_id, source_participant_id) UNIQUE backstop は本波（P2-b）では入れない。
        // 理由: 再与信（cancel 後の再 authorize）で同三つ組の別行が要るケースがあり、UNIQUE の可否は
        // P2-c の capture 状態機械（AUTHORIZED→CAPTURED/CANCELED と再与信 semantics）と合わせて決めるべき。
        // 対処療法的に今 UNIQUE を張ると再与信が不能になる恐れがあるため、意図的に保留する。
        var existing = escrowTransactionRepository.findBySourceKindAndSourceIdAndSourceParticipantId(
                cmd.sourceKind(), cmd.sourceId(), cmd.sourceParticipantId());
        if (existing.isPresent()) {
            EscrowTransactionEntity e = existing.get();
            log.info("与信は既に存在します（冪等・再作成しない）: escrowId={}, status={}", e.getId(), e.getStatus());
            return toResult(e, null);
        }

        // 手数料折半は PaymentFeeCalculator に一元化（数式を再実装しない・02 §3.5）。
        FeeBreakdown fee = paymentFeeCalculator.calculate(cmd.faceAmount());
        String currency = cmd.currency() != null ? cmd.currency() : "JPY";

        // 受取側 Connect 口座を解決し payouts_enabled を判定（02 §5.1 step2 / §5.2）。
        ConnectAccountEntity payee = connectAccountRepository
                .findByScopeKindAndScopeIdAndDeletedAtIsNull(cmd.payeeKind(), cmd.payeeScopeId())
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));

        boolean payoutsEnabled = Boolean.TRUE.equals(payee.getPayoutsEnabled());
        LocalDateTime now = LocalDateTime.now();

        EscrowTransactionEntity.EscrowTransactionEntityBuilder builder = EscrowTransactionEntity.builder()
                .sourceKind(cmd.sourceKind())
                .captureMode(EscrowCaptureMode.MANUAL)
                .sourceId(cmd.sourceId())
                .sourceParticipantId(cmd.sourceParticipantId())
                .payerScopeKind(cmd.payerScopeKind())
                .payerScopeId(cmd.payerScopeId())
                .payerStripeCustomerId(cmd.payerStripeCustomerId())
                .payeeKind(cmd.payeeKind())
                .payeeConnectAccountId(payee.getId())
                .organizationId(cmd.organizationId())
                .faceAmount(fee.faceAmount())
                .amount(fee.chargeAmount())
                .currency(currency)
                .applicationFeeAmount(fee.applicationFeeAmount());

        String clientSecret;
        if (!payoutsEnabled) {
            // 受取側 onboarding 未完了 → HELD。PaymentIntent は作らない（口座登録完了後に再開・02 §5.2）。
            EscrowTransactionEntity held = builder
                    .status(EscrowStatus.HELD)
                    .holdExpiresAt(now.plusHours(HELD_GRACE_HOURS))
                    .build();
            held = escrowTransactionRepository.save(held);
            log.info("与信を HELD で記録（受取側 onboarding 未完了・PI 未作成）: escrowId={}, payeeAccount={}",
                    held.getId(), payee.getStripeAccountId());
            return toResult(held, null);
        }

        // 受取側 onboarding 完了 → manual-capture の Destination PaymentIntent を作成（02 §5.1 step5）。
        String idempotencyKey = "escrow-" + cmd.sourceId() + "-" + cmd.sourceParticipantId();
        StripePaymentProvider.PaymentIntentInfo pi = stripePaymentProvider.createDestinationPaymentIntent(
                fee.chargeAmount(), currency, cmd.payerStripeCustomerId(), fee.applicationFeeAmount(),
                payee.getStripeAccountId(), CaptureMethod.MANUAL, idempotencyKey);

        EscrowTransactionEntity authorized = builder
                .status(EscrowStatus.AUTHORIZED)
                .stripePaymentIntentId(pi.paymentIntentId())
                .authorizedAt(now)
                .holdExpiresAt(now.plusDays(AUTHORIZED_HOLD_DAYS))
                .build();
        authorized = escrowTransactionRepository.save(authorized);
        clientSecret = pi.clientSecret();
        log.info("与信を AUTHORIZED で記録: escrowId={}, paymentIntentId={}, charge={}, appFee={}",
                authorized.getId(), pi.paymentIntentId(), fee.chargeAmount(), fee.applicationFeeAmount());
        return toResult(authorized, clientSecret);
    }

    /**
     * {@code actorUserId} が非 null（明示 API 経路）の場合のみ、受領主体（札主 scope）の ADMIN を検証する。
     *
     * <p>USER 受領（個人）の場合は scope 認可の対象外（本人固定・札に紐づく個人受領者）であり、
     * 札スコープ（TEAM/ORG）側の権限で守られるためここでは検証しない。</p>
     */
    private void authorizeActorIfPresent(AuthorizeChargeCommand cmd) {
        Long actorUserId = cmd.actorUserId();
        if (actorUserId == null) {
            return;
        }
        ScopeKind payeeKind = cmd.payeeKind();
        if (payeeKind == ScopeKind.USER) {
            // 個人受領は札スコープ側の権限で守る。本コマンドの payeeScopeId は users.id のため
            // scope 認可の対象にできない。明示経路では organizationId 等の上位 scope で守る前提だが、
            // 本波では USER 受領の明示 API 経路は未提供（イベント駆動のみ）のため拒否しない。
            return;
        }
        try {
            switch (payeeKind) {
                case TEAM -> accessControlService.checkPermission(actorUserId, cmd.payeeScopeId(),
                        PayeeScopeResolver.SCOPE_TYPE_TEAM, PERMISSION_MANAGE_PAYMENT);
                case ORG -> accessControlService.checkAdminOrHasPermission(actorUserId, cmd.payeeScopeId(),
                        PayeeScopeResolver.SCOPE_TYPE_ORGANIZATION, PERMISSION_MANAGE_PAYMENT);
                default -> { /* USER は上で return 済み */ }
            }
        } catch (BusinessException e) {
            // AccessControlService の認可エラーを Connect 系の 403 へ正規化（IDOR は存在秘匿しつつ拒否）。
            throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_FORBIDDEN, e);
        }
    }

    private AuthorizeChargeResult toResult(EscrowTransactionEntity e, String clientSecret) {
        return new AuthorizeChargeResult(
                e.getId(),
                e.getStatus(),
                clientSecret,
                e.getStripePaymentIntentId(),
                e.getFaceAmount(),
                e.getAmount(),
                e.getApplicationFeeAmount());
    }
}
