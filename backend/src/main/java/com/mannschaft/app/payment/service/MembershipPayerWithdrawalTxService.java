package com.mannschaft.app.payment.service;

import com.mannschaft.app.auth.service.WithdrawalStateQueryService;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 柱③-B（CMP-260901-1538）PR-3: 払い手退会に伴う期末解約の<b>トランザクション単位</b>
 * （設計書 {@code billing_payer_handover_design.md} §6.1）。
 *
 * <h2>なぜ Runner と Bean を分けるのか（検分1巡目 P1-2）</h2>
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
 * <h2>行ロックと「今どちらの意思か」の再確認（検分2巡目 P1-1）</h2>
 * <p>各トランザクションは対象行を {@code SELECT ... FOR UPDATE}（{@code findByIdForUpdate}）で
 * ロックしてから状態を<b>取り直して</b>検証する。これが無いと {@code customer.subscription.deleted}
 * webhook（同じ行のロックを取る）と競合し、古い ACTIVE エンティティを保持したままの通常 UPDATE が
 * webhook の CANCELLED を上書きしうる。</p>
 *
 * <p>さらに、退会と退会取消はどちらも共用 {@code event-pool} 上の非同期処理で<b>到達順が保証されない</b>。
 * そこで各トランザクションは、行ロックを保持したまま
 * {@link WithdrawalStateQueryService#findPendingWithdrawalAttempt} で<b>処理時点の真値</b>を引き、
 * 「解約は退会申請中のときだけ」「復旧は退会申請中でないときだけ」進める。同一サブスクに対する両処理は
 * 同じ行ロックで直列化されるため、後から走ったほうは必ず commit 済みの真値を見る。</p>
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
    private final WithdrawalStateQueryService withdrawalStateQueryService;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 対象<b>全件</b>の作業行を、Stripe 呼び出しに入る前に一括で {@code PENDING} として永続化する
     * （検分2巡目 P1-2）。
     *
     * <h2>なぜ契約ごとの {@code prepare} だけでは足りないのか</h2>
     * <p>{@code prepare} は各契約の処理が始まって初めて行を作る。したがって「複数契約の途中で
     * プロセスが停止し、まだ {@code prepare} に到達していない契約」には行が残らず、PR-4 の再試行バッチが
     * {@code PENDING/FAILED} を走査しても<b>永久に拾えない</b>。ループに入る前に全件ぶんの行を1つの
     * トランザクションで commit しておけば、この穴は閉じる。</p>
     *
     * <p>それでも「退会本体の commit 後・非同期タスクが始まる前の停止」「{@code event-pool} の投入拒否」
     * では本メソッド自体が呼ばれない。その最後の穴は、作業行ではなく<b>退会状態そのもの</b>を起点にする
     * {@link MembershipSubscriptionService#findWithdrawalCancelBacklog()} が塞ぐ。</p>
     *
     * @return 世代の検証を通り、実際に予約対象として確定した継続課金 ID（退会申請中でなければ空）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> reserveAll(Collection<UUID> subscriptionIds, Long payerUserId) {
        Optional<Instant> attempt = withdrawalStateQueryService.findPendingWithdrawalAttempt(payerUserId);
        if (attempt.isEmpty()) {
            // 退会取消が先に確定している。古い退会イベントで解約を作ってはならない。
            log.info("払い手退会に伴う期末解約: 処理時点で退会申請中ではないため中止します payerUserId={}", payerUserId);
            return List.of();
        }
        Instant withdrawalAttemptAt = attempt.get();

        List<UUID> reserved = new java.util.ArrayList<>();
        for (UUID subscriptionId : subscriptionIds) {
            MembershipPayerWithdrawalCancellationEntity record =
                    cancellationRepository.findBySubscriptionIdForUpdate(subscriptionId)
                            .orElseGet(() -> MembershipPayerWithdrawalCancellationEntity.builder()
                                    .subscriptionId(subscriptionId)
                                    .payerUserId(payerUserId)
                                    .attemptCount(0)
                                    .withdrawalAttemptAt(withdrawalAttemptAt)
                                    .status(MembershipPayerWithdrawalCancellationStatus.PENDING)
                                    .build());
            if (withdrawalAttemptAt.equals(record.getWithdrawalAttemptAt())
                    && record.getStatus() == MembershipPayerWithdrawalCancellationStatus.SUCCEEDED) {
                // 同じ退会試行で既に確定済み（イベント再送）。行を PENDING へ差し戻さない。
                continue;
            }
            record.setPayerUserId(payerUserId);
            record.setWithdrawalAttemptAt(withdrawalAttemptAt);
            record.setStatus(MembershipPayerWithdrawalCancellationStatus.PENDING);
            record.setScheduledAt(null);
            record.setRestoredAt(null);
            cancellationRepository.saveAndFlush(record);
            reserved.add(subscriptionId);
        }
        log.info("払い手退会に伴う期末解約: 作業行を先行永続化しました payerUserId={}, 世代={}, 件数={}",
                payerUserId, withdrawalAttemptAt, reserved.size());
        return List.copyOf(reserved);
    }

    /**
     * 1件ぶんの予約着手（tx①）。行ロック後に「今も退会申請中か」と契約状態を再検証する。
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

        // 行ロックを保持したまま「今この人は退会申請中か」を引く。退会取消が先に確定していれば
        // ここで止まる（イベントの到達順に依存しない・検分2巡目 P1-1）。
        Optional<Instant> attempt = withdrawalStateQueryService.findPendingWithdrawalAttempt(payerUserId);
        if (attempt.isEmpty()) {
            log.info("払い手退会に伴う期末解約: 処理時点で退会申請中ではないためスキップ subscriptionId={}", subscriptionId);
            return Optional.empty();
        }

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
                                .withdrawalAttemptAt(attempt.get())
                                .status(MembershipPayerWithdrawalCancellationStatus.PENDING)
                                .build());
        // 退会 → 取消 → 再退会で払い手が同じ行を再利用する。payer と世代は毎回上書きする。
        record.setPayerUserId(payerUserId);
        record.markAttempt(attempt.get(), subscription.getStripeSubscriptionId());
        cancellationRepository.saveAndFlush(record);

        return Optional.of(new PreparedTarget(
                subscriptionId, subscription.getStripeSubscriptionId(), attempt.get()));
    }

    /**
     * 1件ぶんの確定（tx③）。Stripe 成功後に行ロックを取り直して DB へ反映し、{@code SUCCEEDED} を記録する。
     *
     * @param currentPeriodEndEpochSec Stripe が返した期末 unix 秒（不明なら null）
     * @return DB へ期末解約を反映したら true（既に終端・既に予約済みなら false）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApplyOutcome applyScheduled(UUID subscriptionId, Long payerUserId,
            Instant withdrawalAttemptAt, Long currentPeriodEndEpochSec) {

        MembershipSubscriptionEntity subscription = membershipSubscriptionRepository
                .findByIdForUpdate(subscriptionId)
                .orElseThrow(() -> new IllegalStateException(
                        "期末解約の確定対象が見つかりません subscriptionId=" + subscriptionId));

        if (!payerUserId.equals(subscription.getPayerUserId())) {
            throw new IllegalStateException(
                    "期末解約の確定対象の payer が変わっています subscriptionId=" + subscriptionId);
        }

        // 【要】ここでも世代を再検証する（Codex 検分3巡目 P1-2）。
        // tx① で真値を確かめてもロックはそこで解放され、Stripe 呼び出しの間に退会が取り消されうる。
        // 再検証が無いと「退会取消済みなのに期末解約され、作業行は終端 SUCCEEDED」という、
        // backlog にも再試行にも載らない回復不能な状態が作れてしまう。
        Optional<Instant> current = withdrawalStateQueryService.findPendingWithdrawalAttempt(payerUserId);
        if (current.isEmpty() || !current.get().equals(withdrawalAttemptAt)) {
            log.warn("払い手退会に伴う期末解約: Stripe 呼び出しの間に退会状態が変わったため DB へ反映しません "
                            + "subscriptionId={}, 着手時の世代={}, 現在={}",
                    subscriptionId, withdrawalAttemptAt, current.orElse(null));
            // Stripe 側には既に cancel_at_period_end=true が入っている可能性がある。
            // RESTORING（＝取り消す必要がある）として非終端で残し、復旧経路に拾わせる。
            cancellationRepository.findBySubscriptionIdForUpdate(subscriptionId)
                    .ifPresent(record -> {
                        record.markRestoring();
                        cancellationRepository.saveAndFlush(record);
                    });
            return ApplyOutcome.ABORTED_GENERATION_CHANGED;
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
            // webhook が先に終端化した／既に誰かが期末解約を予約していた。
            // 【重要】この場合を SUCCEEDED にしてはならない（Codex 検分3巡目 P1-3）。
            // 「既に cancel_at_period_end=true」は本人の明示解約かもしれず、SUCCEEDED にすると
            // 退会取消の復旧処理が【本人の意思】を退会由来と誤認して解除してしまう。
            log.info("払い手退会に伴う期末解約: 確定時点で自分が反映すべき状態ではないため DB 更新を行いません "
                    + "subscriptionId={}, status={}, cancelAtPeriodEnd={}",
                    subscriptionId, subscription.getStatus(), subscription.getCancelAtPeriodEnd());
        }

        final boolean scheduledByUs = applied;
        cancellationRepository.findBySubscriptionIdForUpdate(subscriptionId)
                .ifPresent(record -> {
                    if (scheduledByUs) {
                        record.markSucceeded(Instant.now());
                    } else {
                        // 自分が予約したのではない＝復旧の対象にしてはいけない。
                        record.markSuperseded();
                    }
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
        return applied ? ApplyOutcome.APPLIED : ApplyOutcome.SKIPPED;
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
     * 退会取消による復旧の着手（tx①・検分1巡目 P1-3／2巡目 P1-1・P1-3）。
     *
     * <p>行ロックを取ったうえで次の3つを確かめ、すべて満たすときだけ Stripe へ進む。</p>
     * <ol>
     *   <li><b>いま退会申請中ではないこと</b>——再退会が始まっているのに古い取消イベントで
     *       解約を解除してはならない</li>
     *   <li>この行が<b>退会処理由来</b>であり、{@code SUPERSEDED}（本人の新しい意思で上書き済み）でないこと</li>
     *   <li>サブスクが実際に期末解約予約のままであること</li>
     * </ol>
     *
     * <p><b>Stripe 呼び出しの前に {@code RESTORING} を commit する</b>のが要点である。これが無いと
     * 「Stripe 解除成功・DB 反映前に停止」で {@code SUCCEEDED} のまま残り、再試行対象（非終端）にも
     * 入らず、取消イベントも再配送されないため永久に不整合が残る（検分2巡目 P1-3）。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PreparedTarget> prepareRestore(UUID subscriptionId, Long payerUserId) {
        Optional<MembershipPayerWithdrawalCancellationEntity> recordOpt =
                cancellationRepository.findBySubscriptionIdForUpdate(subscriptionId);
        if (recordOpt.isEmpty()
                || !recordOpt.get().isRestorable()
                || !payerUserId.equals(recordOpt.get().getPayerUserId())) {
            return Optional.empty();
        }

        // 再退会が始まっていたら復旧しない（イベントの到達順に依存しない・検分2巡目 P1-1）。
        if (withdrawalStateQueryService.findPendingWithdrawalAttempt(payerUserId).isPresent()) {
            log.info("払い手退会取消: 処理時点で再び退会申請中のため復旧しません subscriptionId={}", subscriptionId);
            return Optional.empty();
        }

        MembershipPayerWithdrawalCancellationEntity record = recordOpt.get();
        Optional<MembershipSubscriptionEntity> locked =
                membershipSubscriptionRepository.findByIdForUpdate(subscriptionId);
        if (locked.isEmpty()) {
            // 対象が消えた。非終端のまま残すと照合バッチが永久に拾い続ける（検分3巡目 P2）。
            terminateAsSuperseded(record, "対象の継続課金が存在しません");
            return Optional.empty();
        }
        MembershipSubscriptionEntity subscription = locked.get();
        if (!payerUserId.equals(subscription.getPayerUserId())
                || !CANCELLABLE_STATUSES.contains(subscription.getStatus())) {
            // payer が変わった／期末が到来して終端化した。もう戻せないので非終端から降ろす。
            terminateAsSuperseded(record,
                    "復旧できない状態です（status=" + subscription.getStatus() + "）");
            return Optional.empty();
        }

        // DB が期末解約のままかどうかで扱いを分ける。
        // - SUCCEEDED: 自分が予約した確証がある。予約が外れていれば本人が別の判断をしたということ。
        // - PENDING/RESTORING: Stripe 側の状態が不明（tx② に到達しなかった窓）。DB が false でも
        //   Stripe には予約が入っている可能性があるため、必ず Stripe の取り消しまで進める。
        boolean dbScheduled = Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd());
        if (record.getStatus() == MembershipPayerWithdrawalCancellationStatus.SUCCEEDED && !dbScheduled) {
            terminateAsSuperseded(record, "本人の操作で期末解約の予約が既に解除されています");
            return Optional.empty();
        }
        if (!dbScheduled && subscription.getStripeSubscriptionId() == null) {
            // Stripe 未連結かつ DB も予約なし＝取り消すものが何も無い。
            terminateAsSuperseded(record, "取り消すべき予約がありません");
            return Optional.empty();
        }

        record.markRestoring();
        cancellationRepository.saveAndFlush(record);

        return Optional.of(new PreparedTarget(
                subscriptionId, subscription.getStripeSubscriptionId(), record.getWithdrawalAttemptAt()));
    }

    /** 非終端のまま照合バッチに拾われ続けないよう、復旧不能な行を終端化する（検分3巡目 P2）。 */
    private void terminateAsSuperseded(MembershipPayerWithdrawalCancellationEntity record, String reason) {
        log.info("払い手退会取消: 復旧対象外のため作業行を終端化します subscriptionId={}, 理由={}",
                record.getSubscriptionId(), reason);
        record.markSuperseded();
        record.setLastError(reason);
        cancellationRepository.saveAndFlush(record);
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
     * 復旧の失敗を永続化する（{@code RESTORING} のまま残し、{@code last_error} に理由を刻む）。
     *
     * <p>{@code FAILED} へは倒さない。{@code FAILED} は「解約が未了」を意味し、再試行バッチの扱いが
     * 逆向きになるためである。{@code RESTORING} のままなら「解除の途中」という正しい意味で拾える。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRestoreFailed(UUID subscriptionId, String error) {
        cancellationRepository.findBySubscriptionIdForUpdate(subscriptionId)
                .ifPresent(record -> {
                    record.setStatus(MembershipPayerWithdrawalCancellationStatus.RESTORING);
                    record.setLastError(error != null && error.length() > 1000
                            ? error.substring(0, 1000) : error);
                    cancellationRepository.saveAndFlush(record);
                });
    }

    /**
     * 本人の明示的な操作により、この行が示す「退会処理由来」という由来を無効化する
     * （{@code SUPERSEDED}・検分2巡目 P1-1）。
     *
     * <p>退会取消の復旧処理は {@code cancel_at_period_end=true} しか見ないため、<b>退会取消のあとに
     * 本人が改めて明示解約した</b>場合、その新しい意思まで解除してしまう。人が新しい判断を下した瞬間に
     * 由来の記録を終端化することで、以後の復旧対象から外す。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void supersedeByUserDecision(UUID subscriptionId) {
        // 【要】PENDING も対象に含める（Codex 検分3巡目 P1-3）。進行中の古い退会処理が PENDING の間に
        // 「退会取消 → 本人の明示解約」が入ると、SUCCEEDED/RESTORING だけを対象にした無効化は no-op に
        // なり、その後に復旧処理がその行を退会由来と誤認して【本人の明示解約】を解除してしまう。
        cancellationRepository.findBySubscriptionIdForUpdate(subscriptionId)
                .filter(MembershipPayerWithdrawalCancellationEntity::isRestorable)
                .ifPresent(record -> {
                    record.markSuperseded();
                    cancellationRepository.saveAndFlush(record);
                    log.info("払い手退会由来の期末解約記録を本人操作により無効化しました subscriptionId={}", subscriptionId);
                });
    }

    /**
     * Stripe へ発行すべき1件ぶんの対象。
     *
     * @param subscriptionId       継続課金 ID
     * @param stripeSubscriptionId Stripe Subscription ID（未連結なら null＝Stripe 操作は行えない）
     * @param withdrawalAttemptAt  この作業が属する退会試行の世代。<b>Stripe 呼び出しを跨いで持ち回り、
     *                             確定トランザクションで再検証する</b>ためのもの（Codex 検分3巡目 P1-2）
     */
    public record PreparedTarget(UUID subscriptionId, String stripeSubscriptionId,
            Instant withdrawalAttemptAt) {
    }

    /** 確定トランザクションの結末。 */
    public enum ApplyOutcome {
        /** DB へ期末解約を反映し、作業行を {@code SUCCEEDED} にした。 */
        APPLIED,
        /** 反映すべき状態になかった（webhook が先に終端化した等）。作業行は {@code SUPERSEDED}。 */
        SKIPPED,
        /**
         * Stripe 呼び出しの間に退会が取り消された（または別の退会試行に変わった）。
         * DB へは反映せず、作業行を {@code RESTORING}（＝Stripe 側の予約を取り消す必要がある）にした。
         */
        ABORTED_GENERATION_CHANGED
    }
}
