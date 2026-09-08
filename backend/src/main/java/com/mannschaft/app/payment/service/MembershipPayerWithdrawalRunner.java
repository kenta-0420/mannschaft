package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.service.MembershipPayerWithdrawalTxService.PreparedTarget;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 柱③-B（CMP-260901-1538）PR-3: 払い手退会に伴う期末解約の<b>1件ぶきの手順</b>を組み立てる
 * オーケストレータ（設計書 {@code billing_payer_handover_design.md} §6.1・Codex 検分1巡目 P1-2 の是正）。
 *
 * <p><b>本クラスに {@code @Transactional} は付けない。</b>Stripe 呼び出しを行ロック保持中の
 * トランザクションに抱え込まないためであり、DB 側の2つの区間はそれぞれ
 * {@link MembershipPayerWithdrawalTxService}（別 Bean ＝ プロキシ経由）の
 * {@code REQUIRES_NEW} で独立させる。</p>
 *
 * <pre>
 *   tx① prepare      : 行ロック → 再検証 → 処理状態を PENDING で永続化（commit）
 *   ─  Stripe        : cancel_at_period_end=true（tx 外・Idempotency-Key はサブスク ID 由来で固定）
 *   tx③ applyScheduled: 行ロック → 再検証 → DB 反映 + SUCCEEDED（commit）／失敗なら markFailed
 * </pre>
 *
 * <p>tx① が commit 済みであることが要点である。②③のどこで落ちても
 * {@code membership_payer_withdrawal_cancellations} に {@code PENDING} 行が残るため、
 * PR-4 の夜次再試行バッチが機械的に拾い直せる（P1-1）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MembershipPayerWithdrawalRunner {

    private final MembershipPayerWithdrawalTxService txService;
    private final StripePaymentProvider stripePaymentProvider;

    /** 1件ぶんの処理結果。 */
    public enum Outcome {
        /** 期末解約を予約できた。 */
        SCHEDULED,
        /** 対象外だった（既に予約済み・終端・payer 不一致など）。失敗ではない。 */
        SKIPPED,
        /** 失敗した（処理状態は FAILED/PENDING で残るため再試行できる）。 */
        FAILED,
        /**
         * Stripe 呼び出しの間に退会が取り消された（世代が変わった）ため中止し、予約の取り消しへ切り替えた。
         * 失敗ではない（利用者の意思に追随した正しい結末）。
         */
        ABORTED
    }

    /**
     * Stripe に触れる前に、対象<b>全件</b>の作業行を1トランザクションで {@code PENDING} として確定させる
     * （検分2巡目 P1-2）。あわせて「処理時点で本当に退会申請中か」を判定する。
     *
     * @return 実際に予約対象として確定した継続課金 ID（退会申請中でなければ空）
     */
    public List<UUID> reserveAll(List<UUID> subscriptionIds, Long payerUserId) {
        try {
            return txService.reserveAll(subscriptionIds, payerUserId);
        } catch (Exception e) {
            // ここで落ちると作業行が1件も残らない。握りつぶさず、上位が「0件」として扱えるようにする。
            log.error("払い手退会に伴う期末解約: 作業行の先行永続化に失敗しました payerUserId={}", payerUserId, e);
            return List.of();
        }
    }

    /**
     * 1つの継続課金を期末解約する。<b>例外を外へ投げない</b>——1件の失敗で他契約の処理を止めないため。
     * ただし失敗は必ず ERROR ログと DB の処理状態の双方に残し、握りつぶさない。
     */
    public Outcome cancelOne(UUID subscriptionId, Long payerUserId) {
        Optional<PreparedTarget> prepared;
        try {
            prepared = txService.prepare(subscriptionId, payerUserId);
        } catch (Exception e) {
            log.error("払い手退会に伴う期末解約: 予約着手に失敗しました subscriptionId={}, payerUserId={}",
                    subscriptionId, payerUserId, e);
            return Outcome.FAILED;
        }
        if (prepared.isEmpty()) {
            return Outcome.SKIPPED;
        }

        String stripeSubscriptionId = prepared.get().stripeSubscriptionId();
        Long currentPeriodEnd = null;
        if (stripeSubscriptionId != null) {
            try {
                StripePaymentProvider.SubscriptionInfo info = stripePaymentProvider
                        .cancelSubscriptionAtPeriodEnd(stripeSubscriptionId,
                                idempotencyKey("withdrawal-payer-cancel", subscriptionId,
                                        prepared.get().attemptToken()));
                currentPeriodEnd = (info != null) ? info.currentPeriodEnd() : null;
            } catch (Exception e) {
                // Stripe 側の成否が不明なまま終わることもある。処理状態は FAILED として残し、
                // PR-4 の再試行バッチが Stripe 実状態と照合して回復する。
                log.error("払い手退会に伴う期末解約: Stripe への期末解約発行に失敗しました "
                        + "subscriptionId={}, stripeSub={}", subscriptionId, stripeSubscriptionId, e);
                markFailedQuietly(subscriptionId, prepared.get().withdrawalAttemptAt(), e);
                return Outcome.FAILED;
            }
        } else {
            // Stripe 未連結（自前バッチ運用の退避策など）。DB 側の予約だけは立てて整合を残す。
            log.warn("払い手退会に伴う期末解約: stripe_subscription_id 未連結のため DB のみ予約 subscriptionId={}",
                    subscriptionId);
        }

        try {
            MembershipPayerWithdrawalTxService.ApplyOutcome outcome = txService.applyScheduled(
                    subscriptionId, payerUserId, prepared.get().withdrawalAttemptAt(), currentPeriodEnd);
            if (outcome == MembershipPayerWithdrawalTxService.ApplyOutcome.ABORTED_GENERATION_CHANGED) {
                // Stripe を呼んでいる間に退会が取り消された。Stripe には予約が入っている可能性があるため、
                // その場で取り消しに進む（放置すると「取消済みなのに期末で終了する」が残る）。
                // ここで失敗しても行は RESTORING（非終端）なので、PR-4 の照合が拾い直せる。
                log.warn("払い手退会に伴う期末解約: 退会が取り消されたため予約の取り消しへ切り替えます subscriptionId={}",
                        subscriptionId);
                restoreOne(subscriptionId, payerUserId);
                return Outcome.ABORTED;
            }
            return outcome == MembershipPayerWithdrawalTxService.ApplyOutcome.APPLIED
                    ? Outcome.SCHEDULED : Outcome.SKIPPED;
        } catch (Exception e) {
            // 「Stripe 成功・DB 失敗」。この非原子性こそ処理状態を永続化した理由である。
            log.error("払い手退会に伴う期末解約: Stripe 成功後の DB 反映に失敗しました subscriptionId={}",
                    subscriptionId, e);
            markFailedQuietly(subscriptionId, prepared.get().withdrawalAttemptAt(), e);
            return Outcome.FAILED;
        }
    }

    /**
     * 退会取消により、退会処理由来で予約した期末解約を1件解除する（P1-3）。
     *
     * <p>本人が退会前に明示解約した契約はそもそも処理状態の行を持たないため、ここへ来ない
     * （＝勝手に復活させない）。</p>
     */
    public boolean restoreOne(UUID subscriptionId, Long payerUserId) {
        Optional<PreparedTarget> prepared;
        try {
            prepared = txService.prepareRestore(subscriptionId, payerUserId);
        } catch (Exception e) {
            log.error("払い手退会取消: 復旧着手に失敗しました subscriptionId={}", subscriptionId, e);
            return false;
        }
        if (prepared.isEmpty()) {
            return false;
        }

        // ここに到達した時点で処理状態は RESTORING として commit 済みである（prepareRestore）。
        // これにより「Stripe 解除成功・DB 反映前に停止」でも行が非終端で残り、PR-4 の照合が拾える。
        String stripeSubscriptionId = prepared.get().stripeSubscriptionId();
        if (stripeSubscriptionId != null) {
            try {
                stripePaymentProvider.revertSubscriptionCancelAtPeriodEnd(stripeSubscriptionId,
                        idempotencyKey("withdrawal-payer-restore", subscriptionId,
                                prepared.get().attemptToken()));
            } catch (Exception e) {
                // Stripe が期末解約のままなら DB だけ戻すと乖離する。DB は触らず RESTORING で残す。
                log.error("払い手退会取消: Stripe の期末解約解除に失敗しました subscriptionId={}, stripeSub={}",
                        subscriptionId, stripeSubscriptionId, e);
                markRestoreFailedQuietly(subscriptionId, prepared.get().withdrawalAttemptAt(), e);
                return false;
            }
        }

        try {
            return txService.applyRestore(subscriptionId, payerUserId,
                    prepared.get().withdrawalAttemptAt());
        } catch (Exception e) {
            // Stripe は解除済み・DB は cancel_at_period_end=true のまま。この非対称こそ
            // RESTORING を Stripe 呼び出しの前に刻んだ理由である（検分2巡目 P1-3）。
            log.error("払い手退会取消: Stripe 解除後の DB 反映に失敗しました subscriptionId={}", subscriptionId, e);
            markRestoreFailedQuietly(subscriptionId, prepared.get().withdrawalAttemptAt(), e);
            return false;
        }
    }

    /**
     * 本人（または後見保護者）が明示的に期末解約を決めたときに、「退会処理由来」という由来の記録を
     * 無効化する（検分2巡目 P1-1）。
     *
     * <p>解約 API そのものを道連れにしないため例外は外へ出さない。無効化に失敗しても解約自体は成立し、
     * 最悪でも「遅れて届いた退会取消が1件を戻す」に留まる（利用者は再度解約できる）。ただし黙らせず
     * ERROR で残す。</p>
     */
    public void supersedeByUserDecision(UUID subscriptionId) {
        try {
            txService.supersedeByUserDecision(subscriptionId);
        } catch (Exception e) {
            log.error("払い手退会由来の期末解約記録の無効化に失敗しました subscriptionId={}", subscriptionId, e);
        }
    }

    /**
     * Stripe の冪等キー。<b>退会試行ごとに必ず異なる値</b>を含める（Codex 検分4巡目 P1-1・5巡目 P1-3）。
     *
     * <p>是正の経緯:</p>
     * <ol>
     *   <li>当初は {@code subscriptionId} だけだった。「世代Aで解約予約 → 取消で解除 → 24時間以内に
     *       世代Bで再退会」で世代Bが世代Aと同じキーになり、Stripe は同一キーに<b>最初の応答を
     *       そのまま返す</b>ので3回目の更新が実行されない。</li>
     *   <li>次に退会世代（{@code users.deleted_at}）の epoch millis を足したが、<b>本番の
     *       {@code users.deleted_at} は {@code DATETIME}（小数秒なし）</b>であり、同一秒内の
     *       「退会A → 取消 → 再退会B」では A と B の世代値が同一になる。列の精度に依存する設計が誤りだった。</li>
     *   <li>次に作業行の試行番号（{@code attempt_count}）に替えた。時刻には依存しなくなったが、
     *       <b>試行のたびに動く値</b>を選んだのが誤りだった。<b>結果不明となった Stripe 呼び出しの
     *       再試行やイベント再送でキーが {@code n → n+1} と変わり、Stripe 側で同一操作として
     *       重複排除されない</b>（＝二重実行）。並行する2処理が行ロックを順に通過したあと、
     *       異なるキーで Stripe を並行呼び出しすることもできた。</li>
     *   <li>最終形は<b>退会試行ごとに一度だけ払い出す不変のトークン</b>
     *       （{@code withdrawal_attempt_token}）である。冪等キーの要件
     *       「<b>同一世代・同一操作の再試行では固定、別世代では変更</b>」をそのまま満たす。</li>
     * </ol>
     *
     * <p>解約と解除で接頭辞を分けるため、両者のキーも衝突しない。</p>
     */
    private static String idempotencyKey(String prefix, UUID subscriptionId, UUID attemptToken) {
        return prefix + "-" + subscriptionId + "-" + attemptToken;
    }

    /** 失敗の記録自体が失敗しても、元の失敗ログを消さないよう分けて捕捉する。 */
    private void markFailedQuietly(UUID subscriptionId, Instant withdrawalAttemptAt, Exception cause) {
        try {
            txService.markFailed(subscriptionId, withdrawalAttemptAt, describe(cause));
        } catch (Exception e) {
            log.error("払い手退会に伴う期末解約: 失敗状態の記録にも失敗しました subscriptionId={}", subscriptionId, e);
        }
    }

    /** 復旧失敗は {@code RESTORING} のまま理由だけを刻む（{@code FAILED} は解約未了を意味し逆向きになる）。 */
    private void markRestoreFailedQuietly(UUID subscriptionId, Instant withdrawalAttemptAt,
            Exception cause) {
        try {
            txService.markRestoreFailed(subscriptionId, withdrawalAttemptAt, describe(cause));
        } catch (Exception e) {
            log.error("払い手退会取消: 失敗状態の記録にも失敗しました subscriptionId={}", subscriptionId, e);
        }
    }

    private static String describe(Exception cause) {
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
