package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationReminderRunner.ReminderCommit;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationReminderRunner.SenderAlertContext;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * F04.9 確認通知リマインドバッチサービス。
 *
 * <p>1分間隔で ACTIVE 状態の確認通知を検索し、
 * 未確認受信者への1回目・2回目リマインド送信と送信者アラートを処理する。</p>
 *
 * <p>ShedLock により複数インスタンス起動時の二重実行を防ぐ。</p>
 *
 * <h2>トランザクション構成（CMP-260925-0905）</h2>
 * <p>本クラスは<b>非トランザクションのオーケストレータ</b>であり {@code @Transactional} を持たない
 * （同一クラス内の自己呼び出しでは {@code @Transactional} が効かず、遅延読み込みが失敗していた）。
 * 送信対象の絞り込みは通知1件につき1回の一括クエリ＋メモリ上の判定で行い、対象者だけを
 * {@link ConfirmableNotificationReminderRunner}（{@code REQUIRES_NEW}）へチャンクで渡す。
 * 送信済み記録と通知行は Runner の同一トランザクションで確定する。WebSocket / Push の配信だけを
 * commit 後にここから起動するため、配信プールが拒否・失敗しても通知行は受信箱に残る。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmableNotificationReminderBatchService {

    private final ConfirmableNotificationRepository notificationRepository;
    private final ConfirmableNotificationRecipientRepository recipientRepository;
    private final ConfirmableNotificationReminderRunner reminderRunner;
    /** commit 後の WebSocket / Push 配信のみに使う。 */
    private final NotificationDispatchService dispatchService;
    /** 送信者アラート本文の locale 解決（D-5: auth の UserRepository を直接呼ばない）。 */
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;
    /** 送信者アラートの作成・配信（非トランザクションのオーケストレータから呼ぶ）。 */
    private final NotificationHelper notificationHelper;

    /** リマインドの1バッチあたりの受信者数上限（軍議第8版確定稿 §9.4: 「受信者500人ごと」）。 */
    static final int REMINDER_BATCH_SIZE = 500;

    /**
     * リマインドバッチを実行する。
     *
     * <p>1分間隔で起動し、ACTIVE 状態の通知を対象にリマインド送信・アラート送信を行う。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。確認通知の未確認者リマインド送信。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "notification-confirmable-reminder", description = "確認通知の未確認受信者リマインドを 1 分毎に送信する")
    @Scheduled(fixedDelay = 60_000) // 1分間隔
    @SchedulerLock(
            name = "confirmableNotificationReminderBatch",
            lockAtLeastFor = "PT50S",
            lockAtMostFor = "PT2M")
    public void runBatch() {
        LocalDateTime now = LocalDateTime.now();
        log.debug("確認通知リマインドバッチ開始: {}", now);

        // STEP0: ACTIVE 状態の通知のみを対象にする（パフォーマンス最適化）
        List<Long> activeNotificationIds = notificationRepository.findByStatus(ConfirmableNotificationStatus.ACTIVE)
                .stream().map(ConfirmableNotificationEntity::getId).toList();

        if (activeNotificationIds.isEmpty()) {
            return;
        }

        int reminderCount = 0;
        int alertCount = 0;
        int failedCount = 0;

        for (Long notificationId : activeNotificationIds) {
            // STEP1: 未確認受信者を通知1件につき1クエリで取得し、送信対象はメモリ上で絞り込む（N+1 回避）。
            List<Long> firstTargets = new ArrayList<>();
            List<Long> secondTargets = new ArrayList<>();
            try {
                for (ConfirmableNotificationRecipientEntity r
                        : recipientRepository.findActiveUnconfirmedByNotificationId(notificationId)) {
                    if (r.needsFirstReminder(now)) {
                        firstTargets.add(r.getRecipientUserId());
                    } else if (r.needsSecondReminder(now)) {
                        secondTargets.add(r.getRecipientUserId());
                    }
                }
            } catch (Exception e) {
                failedCount++;
                log.error("確認通知リマインド対象の取得に失敗: notificationId={}", notificationId, e);
                continue;
            }
            int[] counts = new int[2];
            sendInChunks(notificationId, firstTargets, true, now, counts);
            sendInChunks(notificationId, secondTargets, false, now, counts);
            reminderCount += counts[0];
            failedCount += counts[1];

            // STEP2: 送信者アラート（判定は Runner の読み取り TX、文面の組み立てと通知の作成は非トランザクションで行う）。
            try {
                var alert = reminderRunner.evaluateSenderAlert(notificationId);
                if (alert.isPresent()) {
                    sendSenderAlert(alert.get());
                    alertCount++;
                }
            } catch (Exception e) {
                failedCount++;
                log.error("送信者アラート送信失敗（継続）: notificationId={}", notificationId, e);
            }
        }

        if (reminderCount > 0 || alertCount > 0 || failedCount > 0) {
            log.info("確認通知リマインドバッチ完了: activeNotifications={}, reminders={}, alerts={}, failed={}",
                    activeNotificationIds.size(), reminderCount, alertCount, failedCount);
        }
    }

    /** 送信者アラートの文面（送信者のロケール）を組み立てて通知する。 */
    private void sendSenderAlert(SenderAlertContext ctx) {
        Locale locale = Locale.forLanguageTag(userLocaleCache.getLocale(ctx.senderId()));
        String title = messageSource.getMessage(
                "notification.confirmable.senderAlert.title", null, "確認通知のアラート", locale);
        String body = messageSource.getMessage(
                "notification.confirmable.senderAlert.body",
                new Object[]{ctx.confirmRate(), ctx.alertThreshold(), ctx.notificationTitle()},
                "確認率が " + ctx.confirmRate() + "% です（閾値: " + ctx.alertThreshold() + "%）: "
                        + ctx.notificationTitle(),
                locale);
        notificationHelper.notify(
                ctx.senderId(), "CONFIRMABLE_NOTIFICATION_SENDER_ALERT", NotificationPriority.HIGH,
                title, body, "CONFIRMABLE_NOTIFICATION", ctx.notificationId(),
                ctx.scopeType(), ctx.scopeId(), ctx.actionUrl(), null);
        log.info("送信者アラート送信: notificationId={}, confirmRate={}%, threshold={}%",
                ctx.notificationId(), ctx.confirmRate(), ctx.alertThreshold());
    }

    /**
     * 送信対象を {@link #REMINDER_BATCH_SIZE} 人ずつ Runner へ渡す。チャンクが失敗したらそのチャンクは
     * ロールバック済み（何も確定していない）なので、1人ずつに分けて再試行し、失敗した人だけを特定して
     * ERROR ログに残す（1人の失敗が他の受信者を巻き添えにしない）。
     *
     * @param counts [0]=送信を確定した人数, [1]=失敗件数
     */
    private void sendInChunks(Long notificationId, List<Long> userIds, boolean isFirst,
                              LocalDateTime now, int[] counts) {
        for (int from = 0; from < userIds.size(); from += REMINDER_BATCH_SIZE) {
            List<Long> chunk = userIds.subList(from, Math.min(from + REMINDER_BATCH_SIZE, userIds.size()));
            try {
                counts[0] += commitAndDispatch(notificationId, chunk, isFirst, now);
            } catch (Exception e) {
                log.error("リマインドのチャンク確定に失敗（1人ずつに分けて再試行）: notificationId={}, size={}",
                        notificationId, chunk.size(), e);
                if (chunk.size() == 1) {
                    counts[1]++;
                    continue;
                }
                for (Long userId : chunk) {
                    try {
                        counts[0] += commitAndDispatch(notificationId, List.of(userId), isFirst, now);
                    } catch (Exception single) {
                        counts[1]++;
                        log.error("リマインド確定失敗（この受信者のみスキップ・次回バッチで再試行）: "
                                + "notificationId={}, userId={}", notificationId, userId, single);
                    }
                }
            }
        }
    }

    /** Runner で commit した後に、WebSocket / Push の配信だけを起動する。配信の失敗は通知行に影響しない。 */
    private int commitAndDispatch(Long notificationId, List<Long> userIds, boolean isFirst, LocalDateTime now) {
        ReminderCommit commit = reminderRunner.commitReminders(notificationId, userIds, isFirst, now);
        if (!commit.notifications().isEmpty()) {
            try {
                dispatchService.dispatchBatch(commit.notifications());
            } catch (Exception e) {
                // 通知行は commit 済みで受信箱に残る。リアルタイム配信（WebSocket / Push）だけが欠ける。
                log.error("リマインド通知のリアルタイム配信の起動に失敗（通知行は作成済み）: notificationId={}, count={}",
                        notificationId, commit.notifications().size(), e);
            }
        }
        return commit.userIds().size();
    }

    /**
     * CMP-260920-1040: 通知1件・受信者チャンクごとの「送信済み確定＋通知行INSERT」を独立トランザクションで行う
     * （実体は {@link ConfirmableNotificationReminderRunner#commitReminders}）。
     *
     * @return 実際に送信を確定した user_id
     */
    public List<Long> processRemindersTransactional(
            Long notificationId, List<Long> candidateUserIds, boolean isFirstReminder, LocalDateTime now) {
        return reminderRunner.commitReminders(notificationId, candidateUserIds, isFirstReminder, now).userIds();
    }
}
