package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.payment.MembershipSubscriptionStatus;
import com.mannschaft.app.payment.entity.MembershipPayerWithdrawalCancellationEntity;
import com.mannschaft.app.payment.entity.MembershipPayerWithdrawalCancellationStatus;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.event.MembershipPayerWithdrawalNotificationEvent;
import com.mannschaft.app.payment.repository.MembershipPayerWithdrawalCancellationRepository;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 柱③-B（CMP-260901-1538）PR-3: 払い手退会に伴う期末解約の<b>トランザクション単位</b>
 * （設計書 {@code billing_payer_handover_design.md} §6.1・Codex 検分1巡目 P1-2 の是正）。
 *
 * <h2>なぜ Runner と Bean を分けるのか</h2>
 * <p>1サブスクの処理は「①DB で予約に着手 → ②Stripe 呼び出し（tx 外）→ ③DB へ確定」の3段であり、
 * ①③は<b>それぞれ独立したトランザクション</b>でなければならない。是正前は全件を1つの
 * {@code REQUIRES_NEW} トランザクションで処理しており、<b>最後の flush/commit で1件でも DB 更新が
 * 失敗すると、成功した全契約の DB 変更と通知イベントがまとめてロールバックする</b>一方で、
 * 先に成功した Stripe の {@code cancel_at_period_end=true} は戻らなかった。</p>
 *
 * <p>Spring の {@code @Transactional} はプロキシ経由でしか効かないため、同一クラス内の自己呼び出しでは
 * 新しいトランザクションが始まらない。オーケストレーション（{@link MembershipPayerWithdrawalRunner}）と
 * トランザクション単位（本クラス）を別 Bean に分けるのはこのためである。</p>
 *
 * <h2>行ロックと再検証</h2>
 * <p>各トランザクションは対象行を {@code SELECT ... FOR UPDATE}（{@code findByIdForUpdate}）で
 * ロックしてから payer / status / {@code cancel_at_period_end} を<b>取り直して</b>検証する。
 * これが無いと {@code customer.subscription.deleted} webhook（同じ行のロックを取る）と競合し、
 * 古い ACTIVE エンティティを保持したままの通常 UPDATE が webhook の CANCELLED を上書きしうる。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MembershipPayerWithdrawalTxService {

    /** 期末解約の対象となる状態（{@code PENDING} は初回課金前・終端は対象外）。 */
    private static final List<MembershipSubscriptionStatus> CANCELLABLE_STATUSES =
            List.of(MembershipSubscriptionStatus.ACTIVE, MembershipSubscriptionStatus.PAST_DUE);

    private final MembershipSubscriptionRepository membershipSubscriptionRepository;
    private final MembershipPayerWithdrawalCancellationRepository cancellationRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 1件ぶんの予約着手（tx①）。行ロック後に再検証し、処理状態を {@code PENDING} で永続化する。
     *
     * @return Stripe へ発行すべき対象（{@link PreparedTarget}）。処理不要なら空
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PreparedTarget> prepare(UUID subscriptionId, Long payerUserId) {
        Optional<MembershipSubscriptionEntity> locked =
                membershipSubscriptionRepository.findByIdForUpdate(subscriptionId);
        if (locked.isEmpty()) {
            log.info("払い手退会に伴う期末解約: 対象が消滅していました subscriptionId={}", subscriptionId);
            return Optional.empty();
        }
        MembershipSubscriptionEntity subscription = locked.get();

        // 抽出時点から状態が変わっている可能性がある（webhook・本人操作）。ロック後に取り直して判断する。
        if (!payerUserId.equals(subscription.getPayerUserId())
                || !CANCELLABLE_STATUSES.contains(subscription.getStatus())) {
            log.info("払い手退会に伴う期末解約: ロック後の再検証で対象外になりました subscriptionId={}, status={}",
                    subscriptionId, subscription.getStatus());
            return Optional.empty();
        }
        if (Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd())) {
            // 既に予約済み（再送・利用者自身の解約操作と競合）。Stripe を叩き直す必要はない。
            log.info("払い手退会に伴う期末解約: 既に予約済みのためスキップ subscriptionId={}", subscriptionId);
            return Optional.empty();
        }

        MembershipPayerWithdrawalCancellationEntity record =
                cancellationRepository.findBySubscriptionIdForUpdate(subscriptionId)
                        .orElseGet(() -> MembershipPayerWithdrawalCancellationEntity.builder()
                                .subscriptionId(subscriptionId)
                                .payerUserId(payerUserId)
                                .attemptCount(0)
                                .status(MembershipPayerWithdrawalCancellationStatus.PENDING)
                                .build());
        // 退会 → 取消 → 再退会で払い手が同じ行を再利用する。payer は毎回上書きして最新の申請者に合わせる。
        record.setPayerUserId(payerUserId);
        record.markAttempt(subscription.getStripeSubscriptionId());
        cancellationRepository.saveAndFlush(record);

        return Optional.of(new PreparedTarget(subscriptionId, subscription.getStripeSubscriptionId()));
    }

    /**
     * 1件ぶんの確定（tx③）。Stripe 成功後に行ロックを取り直して DB へ反映し、{@code SUCCEEDED} を記録する。
     *
     * @param currentPeriodEndEpochSec Stripe が返した期末 unix 秒（不明なら null）
     * @return DB へ期末解約を反映したら true（既に終端・既に予約済みなら false）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean applyScheduled(UUID subscriptionId, Long payerUserId, Long currentPeriodEndEpochSec) {
        MembershipSubscriptionEntity subscription = membershipSubscriptionRepository
                .findByIdForUpdate(subscriptionId)
                .orElseThrow(() -> new IllegalStateException(
                        "期末解約の確定対象が見つかりません subscriptionId=" + subscriptionId));

        if (!payerUserId.equals(subscription.getPayerUserId())) {
            throw new IllegalStateException(
                    "期末解約の確定対象の payer が変わっています subscriptionId=" + subscriptionId);
        }

        boolean applied = false;
        if (CANCELLABLE_STATUSES.contains(subscription.getStatus())
                && !Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd())) {
            subscription.scheduleCancelAtPeriodEnd();
            if (currentPeriodEndEpochSec != null) {
                LocalDate periodEnd = Instant.ofEpochSecond(currentPeriodEndEpochSec)
                        .atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toLocalDate();
                subscription.applyCurrentPeriod(null, periodEnd);
            }
            // 契約単位の DB 結果をここで確定させる（commit まで遅延させると隣の契約を巻き添えにする）。
            membershipSubscriptionRepository.saveAndFlush(subscription);
            applied = true;
        } else {
            // webhook が先に CANCELLED を確定させた等。「課金が止まる」という目的は既に達している。
            log.info("払い手退会に伴う期末解約: 確定時点で既に対象外だったため DB 更新を行いません "
                    + "subscriptionId={}, status={}, cancelAtPeriodEnd={}",
                    subscriptionId, subscription.getStatus(), subscription.getCancelAtPeriodEnd());
        }

        cancellationRepository.findBySubscriptionIdForUpdate(subscriptionId)
                .ifPresent(record -> {
                    record.markSucceeded(Instant.now());
                    cancellationRepository.saveAndFlush(record);
                });

        if (applied) {
            // 通知は業務 tx 内では publish のみ（配送は AFTER_COMMIT・rollback-only 防止）。
            applicationEventPublisher.publishEvent(new MembershipPayerWithdrawalNotificationEvent(
                    subscription.getId(),
                    subscription.getBeneficiaryUserId(),
                    subscription.getScopeKind(),
                    subscription.getScopeId(),
                    subscription.getCurrentPeriodEnd(),
                    payerUserId));
        }
        return applied;
    }

    /**
     * 失敗を永続化する（再試行対象として残す・tx③'）。
     *
     * <p>ログだけで済ませないのがこのメソッドの唯一の存在理由である。行が残っていなければ
     * PR-4 の再試行バッチは対象を拾いようがない。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID subscriptionId, String error) {
        cancellationRepository.findBySubscriptionIdForUpdate(subscriptionId)
                .ifPresent(record -> {
                    record.markFailed(error);
                    cancellationRepository.saveAndFlush(record);
                });
    }

    /**
     * 退会取消による復旧の着手（tx①・P1-3）。行ロック後に「退会処理由来で予約が成立している」ことを
     * 再確認し、Stripe へ発行すべき対象を返す。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PreparedTarget> prepareRestore(UUID subscriptionId, Long payerUserId) {
        Optional<MembershipPayerWithdrawalCancellationEntity> recordOpt =
                cancellationRepository.findBySubscriptionIdForUpdate(subscriptionId);
        if (recordOpt.isEmpty()
                || recordOpt.get().getStatus() != MembershipPayerWithdrawalCancellationStatus.SUCCEEDED
                || recordOpt.get().getRestoredAt() != null
                || !payerUserId.equals(recordOpt.get().getPayerUserId())) {
            return Optional.empty();
        }

        Optional<MembershipSubscriptionEntity> locked =
                membershipSubscriptionRepository.findByIdForUpdate(subscriptionId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        MembershipSubscriptionEntity subscription = locked.get();
        if (!payerUserId.equals(subscription.getPayerUserId())
                || !CANCELLABLE_STATUSES.contains(subscription.getStatus())
                || !Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd())) {
            // 期末が既に到来して終端化した／本人が別途操作した。復旧はしない（勝手に復活させない）。
            log.info("払い手退会取消: 復旧対象外のためスキップ subscriptionId={}, status={}, cancelAtPeriodEnd={}",
                    subscriptionId, subscription.getStatus(), subscription.getCancelAtPeriodEnd());
            return Optional.empty();
        }
        return Optional.of(new PreparedTarget(subscriptionId, subscription.getStripeSubscriptionId()));
    }

    /**
     * 退会取消による復旧の確定（tx③・P1-3）。{@code cancel_at_period_end} を解除し、復旧済みとして記録する。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean applyRestore(UUID subscriptionId, Long payerUserId) {
        MembershipSubscriptionEntity subscription = membershipSubscriptionRepository
                .findByIdForUpdate(subscriptionId)
                .orElseThrow(() -> new IllegalStateException(
                        "復旧対象が見つかりません subscriptionId=" + subscriptionId));

        boolean applied = false;
        if (payerUserId.equals(subscription.getPayerUserId())
                && CANCELLABLE_STATUSES.contains(subscription.getStatus())
                && Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd())) {
            subscription.clearCancelAtPeriodEnd();
            membershipSubscriptionRepository.saveAndFlush(subscription);
            applied = true;
        }

        cancellationRepository.findBySubscriptionIdForUpdate(subscriptionId)
                .ifPresent(record -> {
                    record.markRestored(Instant.now());
                    cancellationRepository.saveAndFlush(record);
                });
        return applied;
    }

    /**
     * Stripe へ発行すべき1件ぶんの対象。
     *
     * @param subscriptionId       継続課金 ID
     * @param stripeSubscriptionId Stripe Subscription ID（未連結なら null＝Stripe 操作は行えない）
     */
    public record PreparedTarget(UUID subscriptionId, String stripeSubscriptionId) {
    }
}
