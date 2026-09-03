package com.mannschaft.app.notification.credit.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseEntity;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseStatus;
import com.mannschaft.app.notification.credit.repository.NotificationCreditPurchaseRepository;
import com.mannschaft.app.notification.credit.service.NotificationCreditAlertSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * F09.13 通知クレジット有効期限バッチ。
 *
 * <p>毎日 AM 3:00（JST）に以下を実行する:</p>
 * <ol>
 *   <li>有効期限30日前アラート（未送信のもの）</li>
 *   <li>有効期限7日前アラート（未送信のもの）</li>
 *   <li>有効期限切れの失効処理（{@code remaining_credits} を {@code credit_balance} から差し引く）</li>
 * </ol>
 *
 * <p>ShedLock により複数インスタンス起動時の二重実行を防ぐ。</p>
 *
 * <h2>Issue #2990 L4: 単一 {@code @Transactional} + {@code @Async} 失効の是正</h2>
 * <p>是正前の本クラスには次の二重の欠陥があった。</p>
 * <ol>
 *   <li><b>バッチ全体が単一の {@code @Transactional}</b> だった。項目ごとの {@code try/catch} は
 *       隔離として機能せず、1 件でも DB 例外が出れば（catch してログを出しても）そのトランザクションは
 *       rollback-only のままコミットへ進み {@code UnexpectedRollbackException} となる。
 *       結果として<b>全組織ぶんのアラート送信済フラグ・{@code expired_at}・残高からの失効分の
 *       差し引きがまとめて巻き戻る</b>。</li>
 *   <li>期限アラート2種が<b>本クラス内の {@code @Async protected} メソッドの自己呼び出し</b>だった。
 *       Spring のプロキシを経ないため {@code @Async} は失効し、通知送信は上記の単一トランザクションの
 *       内側で<b>同期実行</b>されていた。つまり台帳上は「非同期だから安全」に見えて、実態は
 *       {@code ROLLBACK_COUPLED}（通知の失敗で残高更新ごと巻き戻る）だった。</li>
 * </ol>
 * <p>是正後は L2 で確立した型に揃える:</p>
 * <ul>
 *   <li>本クラスは <b>{@code @Transactional} を持たないオーケストレータ</b>。対象一覧の読み取りと
 *       ループ制御だけを行う</li>
 *   <li>項目ごとの永続化は {@link NotificationCreditExpiryRunner}（{@code REQUIRES_NEW}）が担う。
 *       1 件の失敗は他の項目を巻き添えにしない</li>
 *   <li>通知は<b>項目TXのコミット後</b>に {@link NotificationCreditAlertSender}
 *       （別 Bean・{@code @Async("event-pool")}）へ委譲する。凍結台帳ヘッダの契約
 *       「項目TX完了を待つ非トランザクションのバッチオーケストレータからのみ実通知を行う」に従う
 *       （{@code NotificationCreditMonthlyResetBatch} と同じ経路）</li>
 * </ul>
 *
 * <h2>検分是正: 投入拒否による通知の永久欠落を塞ぐ</h2>
 * <p>フラグ（{@code alert_sent_30d} / {@code alert_sent_7d}）と {@code expired_at} は項目TXで
 * <b>先に確定</b>する。一方 {@code event-pool} は AbortPolicy であり、飽和時の
 * {@code RejectedExecutionException} は {@code @Async} メソッド本体の try/catch より<b>前</b>
 * （プロキシの投入時点）で発生する。是正前はこれをバッチ側の catch でログするだけだったため、
 * フラグは立ったまま次回バッチの検索条件（{@code AlertSent30dFalse} 等）から外れ、
 * <b>通知は二度と再試行されなかった</b>。失効通知に至ってはフラグを戻すこともできない
 * （残高からの差し引きが済んでおり巻き戻せない）。</p>
 *
 * <p><b>採った方針: 二重送信のリスクを取ってでも永久欠落を避ける</b>。投入拒否を捕らえ、
 * 同期版（{@code sendExpiryAlertNow} / {@code sendCreditExpiredAlertNow}）で
 * バッチスレッド上から送り直す。この判断の根拠:</p>
 * <ul>
 *   <li>通知クレジットの期限・失効は<b>金銭に直結する告知</b>であり、届かないと組織は使えない残高を
 *       抱えたまま気づけない。届かない害が重複して届く害を明確に上回る</li>
 *   <li>そもそも投入拒否は「タスクが一度も実行されていない」ことが保証される事象なので、
 *       このフォールバック経路自体は二重送信を<b>作らない</b>。二重送信のリスクを取ったのは
 *       「フラグを先に確定させる（＝送信前に立てる）」という元の設計を維持した点であり、
 *       送信中のプロセス異常終了などでは重複しうる。それでも欠落よりは重複を選ぶ</li>
 *   <li>フラグを送信成功後に立てる案は採らなかった。非同期送信の成否をバッチが待てず、
 *       待たせると是正前と同じ「バッチが通知の完了を待つ」構造に戻るため</li>
 * </ul>
 * <p>バッチは非トランザクションのオーケストレータなので、同期送信が長引いても
 * 巻き戻る対象は無い（ShedLock の {@code lockAtMostFor=PT20M} の範囲で完了する想定）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationCreditExpiryBatch {

    private final NotificationCreditPurchaseRepository purchaseRepository;
    private final NotificationCreditExpiryRunner expiryRunner;
    private final NotificationCreditAlertSender alertSender;

    /**
     * 有効期限バッチを実行する（毎日 AM 3:00 JST）。
     *
     * <p>本メソッド自体は対象一覧の読み取りとループ制御のみのため {@code @Transactional} を付けない
     * （付けると項目ごとの {@code REQUIRES_NEW} の意味が薄れ、通知の失敗が全体を巻き戻す
     * 是正前の欠陥へ戻る）。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると期限切れクレジットが credit_balance から差し引かれず、実際には使えない残高が組織に残り続ける")
    @BatchEndpoint(name = "notification-credit-expiry-daily", description = "通知クレジットの期限アラートと失効処理を毎日 03:00 に実行する")
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(
            name = "notificationCreditExpiryBatch",
            lockAtLeastFor = "PT5M",
            lockAtMostFor = "PT20M")
    public void runBatch() {
        LocalDateTime now = LocalDateTime.now();
        log.info("通知クレジット有効期限バッチ開始: {}", now);

        // ─── 30日前アラート ───
        process30DayAlert(now);

        // ─── 7日前アラート ───
        process7DayAlert(now);

        // ─── 失効処理 ───
        processExpiry(now);

        log.info("通知クレジット有効期限バッチ完了: {}", now);
    }

    // ─────────────────────────────────────────────────────────
    // プライベートメソッド
    // ─────────────────────────────────────────────────────────

    /**
     * 有効期限30日前アラートを処理する。
     */
    private void process30DayAlert(LocalDateTime now) {
        List<NotificationCreditPurchaseEntity> targets =
                purchaseRepository.findByExpiresAtBetweenAndPaymentStatusAndAlertSent30dFalse(
                        now, now.plusDays(30), NotificationCreditPurchaseStatus.PAID);

        processAlerts(targets, 30);
    }

    /**
     * 有効期限7日前アラートを処理する。
     */
    private void process7DayAlert(LocalDateTime now) {
        List<NotificationCreditPurchaseEntity> targets =
                purchaseRepository.findByExpiresAtBetweenAndPaymentStatusAndAlertSent7dFalse(
                        now, now.plusDays(7), NotificationCreditPurchaseStatus.PAID);

        processAlerts(targets, 7);
    }

    /**
     * 期限アラートを項目ごとに処理する（30日前 / 7日前の共通処理）。
     *
     * <p>フラグ更新は項目TX（{@code REQUIRES_NEW}）で確定させ、その<b>コミット後</b>に通知を送る。
     * 通知の失敗はフラグ更新を巻き戻さない。</p>
     */
    private void processAlerts(List<NotificationCreditPurchaseEntity> targets, int daysRemaining) {
        int sent = 0;
        for (NotificationCreditPurchaseEntity purchase : targets) {
            try {
                NotificationCreditExpiryRunner.AlertTarget target =
                        expiryRunner.markAlertSent(purchase.getId(), daysRemaining);
                if (target == null) {
                    continue;
                }
                // 項目TXはここで既にコミット済み。通知は別 Bean の @Async("event-pool") へ委譲する。
                // 投入拒否時は同期送信へフォールバックする（下記 javadoc 参照）。
                try {
                    alertSender.sendExpiryAlert(target.organizationId(), target.purchaseId(),
                            target.expiresOn(), daysRemaining);
                } catch (RejectedExecutionException ree) {
                    log.warn("{}日前アラートの非同期投入が拒否されたため同期送信へフォールバック: purchaseId={}",
                            daysRemaining, target.purchaseId(), ree);
                    alertSender.sendExpiryAlertNow(target.organizationId(), target.purchaseId(),
                            target.expiresOn(), daysRemaining);
                }
                sent++;
            } catch (Exception e) {
                log.error("{}日前アラート処理失敗: purchaseId={}", daysRemaining, purchase.getId(), e);
            }
        }

        if (sent > 0) {
            log.info("{}日前アラート送信: {}件", daysRemaining, sent);
        }
    }

    /**
     * 有効期限切れの失効処理を実施する（FIFO）。
     */
    private void processExpiry(LocalDateTime now) {
        List<NotificationCreditPurchaseEntity> expiredTargets =
                purchaseRepository.findByExpiresAtBeforeAndPaymentStatusAndExpiredAtIsNull(
                        now, NotificationCreditPurchaseStatus.PAID);

        int processed = 0;
        for (NotificationCreditPurchaseEntity purchase : expiredTargets) {
            try {
                NotificationCreditExpiryRunner.ExpiryOutcome outcome =
                        expiryRunner.expireOne(purchase.getId());
                if (outcome == null) {
                    continue;
                }
                processed++;
                if (outcome.expiredCredits() > 0) {
                    // 項目TXはここで既にコミット済み。通知は別 Bean の @Async("event-pool") へ委譲する。
                    // 投入拒否時は同期送信へフォールバックする（下記 javadoc 参照）。
                    try {
                        alertSender.sendCreditExpiredAlert(outcome.organizationId(), outcome.expiredCredits());
                    } catch (RejectedExecutionException ree) {
                        log.warn("失効通知の非同期投入が拒否されたため同期送信へフォールバック: purchaseId={}",
                                purchase.getId(), ree);
                        alertSender.sendCreditExpiredAlertNow(
                                outcome.organizationId(), outcome.expiredCredits());
                    }
                }
            } catch (Exception e) {
                log.error("失効処理失敗: purchaseId={}", purchase.getId(), e);
            }
        }

        if (processed > 0) {
            log.info("クレジット失効処理完了: {}件", processed);
        }
    }
}
