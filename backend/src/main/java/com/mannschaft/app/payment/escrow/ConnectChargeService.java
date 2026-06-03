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
import java.util.List;
import java.util.UUID;

/**
 * F22.1 統一決済 P2-b: 共通送金サービス（与信＝authorize の中核・設計書 02 §0 / §5.1）。
 *
 * <p>謝礼（{@link EscrowSourceKind#RECRUITMENT}・エスクローモード）の与信を一元化する。
 * 手数料折半（5%＝支払者2.5%+受取側2.5%）は {@link PaymentFeeCalculator} に集約し、本サービスでは
 * 数式を再実装しない（設計書 02 §3.5・散在禁止）。</p>
 *
 * <p><b>P2-c 第一波で {@link #capture(UUID)}（払出＝capture+transfer）を追加した。</b>
 * 返金（reverse_transfer）は次波（P2-c-2）であり本サービスには実装しない（設計書 02 §6）。</p>
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
    private final LedgerEntryRepository ledgerEntryRepository;

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
        // 🟠3 決定（P2-c 第一波）: DB レベルの全行 UNIQUE backstop は張らない。アプリ層の冪等チェックを正とする。
        //   理由1: cancel(CANCELLED)/refund(REFUNDED) 後の再与信は同三つ組で別行を要するため、全行 UNIQUE は不可。
        //   理由2: MySQL は部分 UNIQUE（filtered index）非対応。アクティブ状態のみの一意化には生成列 active_key
        //          （CANCELLED/REFUNDED 時 NULL・それ以外は三つ組ハッシュ）＋ UNIQUE が必要だが、二重 capture は
        //          (a) status no-op + (b) Stripe idempotency key "capture-{escrowId}" + (c) 札行 PESSIMISTIC_WRITE
        //          の三重で、二重 authorize は本 findBy 事前チェック＋上流 recruitment_participants の一意制約で
        //          既に防げており、生成列 UNIQUE は冗長（過剰実装）。最小で二重を防げる現状維持＋本注記とする。
        //   将来再考の引き金: 並行 authorize の競合が実機で観測された場合は active_key 生成列 UNIQUE を Flyway 追加する。
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
     * 謝礼の払出（capture+transfer）を確定する（設計書 02 §5.3）。
     *
     * <p>manual-capture の与信（{@link EscrowStatus#AUTHORIZED}）を Stripe で capture し、同時に
     * {@code transfer_data.destination} へ送金（{@code application_fee_amount} 控除後）する。escrow を
     * {@link EscrowStatus#CAPTURED} にし {@code captured_at} を記録、複式記帳（CAPTURE/TRANSFER_OUT/FEE）を
     * {@code ledger_entries} に追記する（借方合計＝貸方合計・01 §3.3）。</p>
     *
     * <p><b>冪等:</b> 既に {@link EscrowStatus#CAPTURED} なら no-op（Stripe capture を 2 回呼ばない）。さらに
     * Stripe へ {@code idempotency_key="capture-{escrowId}"} を渡し、ネットワーク再送も Stripe 側で拒否する
     * （二重防御・02 §5.3）。</p>
     *
     * <p><b>二重払出防止:</b> 本メソッドは札行 {@code PESSIMISTIC_WRITE} ロック直下（MarketFinalize フック）から
     * 呼ばれる前提で、並行 confirm を直列化する（02 §5.3）。</p>
     *
     * <p><b>不正状態:</b> {@link EscrowStatus#HELD}（onboarding 未完で payout 不能）/{@link EscrowStatus#CANCELLED}/
     * {@link EscrowStatus#REFUNDED} 等の後段状態、または PI 未設定からの capture は
     * {@link ConnectPaymentErrorCode#INVALID_ESCROW_STATE}（409）で拒否する（症状を隠さない）。</p>
     *
     * @param escrowId 払出対象のエスクロー取引 ID
     */
    public void capture(UUID escrowId) {
        EscrowTransactionEntity escrow = escrowTransactionRepository.findById(escrowId)
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));

        // 冪等: CAPTURED 済みは再 capture しない（Stripe capture を 2 回呼ばない・02 §5.3）。
        if (escrow.getStatus() == EscrowStatus.CAPTURED) {
            log.info("払出は既に確定済み（冪等・no-op）: escrowId={}", escrowId);
            return;
        }

        // AUTHORIZED 以外（HELD/CANCELLED/REFUNDED/DISPUTED 等）は払出不能。HELD は payout 不能のため
        // capture せず、onboarding 完了→AUTHORIZED 化（§5.2 webhook）を待つ。症状を隠さず 409 で拒否する。
        if (escrow.getStatus() != EscrowStatus.AUTHORIZED || escrow.getStripePaymentIntentId() == null) {
            log.warn("払出不能な状態からの capture 要求を拒否: escrowId={}, status={}, hasPi={}",
                    escrowId, escrow.getStatus(), escrow.getStripePaymentIntentId() != null);
            throw new BusinessException(ConnectPaymentErrorCode.INVALID_ESCROW_STATE);
        }

        // capture と同時に transfer_data.destination へ送金（application_fee_amount 控除）。
        String idempotencyKey = "capture-" + escrowId;
        StripePaymentProvider.PaymentIntentInfo pi =
                stripePaymentProvider.captureManualPaymentIntent(escrow.getStripePaymentIntentId(), idempotencyKey);

        escrow.setStatus(EscrowStatus.CAPTURED);
        escrow.setCapturedAt(LocalDateTime.now());
        escrowTransactionRepository.save(escrow);

        // 複式記帳（02 §5.3・01 §3.3）: capture 総額（charge=amount）を ESCROW に借方計上し、
        // 受取側送金（transfer=amount−application_fee）を PAYEE に、Mannschaft 手数料を PLATFORM_FEE に貸方計上する。
        // FEE は本波では設定値（application_fee_amount）。Stripe 実手数料での純益確定は P2-c-2 のリコンシリエーション。
        long captureAmount = escrow.getAmount();
        long feeAmount = escrow.getApplicationFeeAmount();
        long transferOut = captureAmount - feeAmount;
        List<LedgerEntryEntity> entries = LedgerEntryBuilder.forTransaction(escrowId, escrow.getCurrency())
                .debit(LedgerEntryType.CAPTURE, LedgerAccount.ESCROW, captureAmount, pi.paymentIntentId())
                .credit(LedgerEntryType.TRANSFER_OUT, LedgerAccount.PAYEE, transferOut, pi.paymentIntentId())
                .credit(LedgerEntryType.FEE, LedgerAccount.PLATFORM_FEE, feeAmount, pi.paymentIntentId())
                .build();
        ledgerEntryRepository.saveAll(entries);

        log.info("謝礼の払出を確定 CAPTURED: escrowId={}, piId={}, capture={}, transfer={}, fee={}",
                escrowId, pi.paymentIntentId(), captureAmount, transferOut, feeAmount);
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
