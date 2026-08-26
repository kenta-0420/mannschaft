package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentReminderEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentReminderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * F03.11 募集型予約: リマインド通知バッチ (Phase 2)。
 *
 * <p>毎分実行し、{@code sent_at IS NULL AND remind_at <= NOW()} のリマインダーを
 * 最大100件処理して {@code RECRUITMENT_REMINDER} 通知を送信する。</p>
 *
 * <p>ShedLock による分散ロックで多重起動を防止する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecruitmentReminderBatch {

    /** 1 回のバッチで処理する最大件数。
     * fixedDelay=1分・上限100件で、通常の発生量に対して十分な処理能力を持つ。 */
    private static final int BATCH_SIZE = 100;

    private final RecruitmentReminderRepository reminderRepository;
    private final RecruitmentListingRepository listingRepository;
    private final RecruitmentParticipantRepository participantRepository;
    private final NotificationHelper notificationHelper;
    // Issue #2715 ロットA: 通知本文の i18n 化。auth の UserRepository を直接呼ばず、
    // common.i18n 配下の共有サービス経由で受信者 locale を解決する（ArchUnit D-5 対応）。
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    /**
     * 送信すべきリマインダーを処理する。
     * {@code fixedDelay = 60_000} ms = 1分間隔（前回実行完了から1分後に次の実行）。
     *
     * <p>対象の絞り込みは {@link RecruitmentReminderRepository#findSendableReminders} が行う。
     * <b>既に開始した募集は対象外</b>であり、長期停止から再開しても
     * 過去の募集へ「24時間後に開催」を一斉送信しない。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_RECRUITMENT_ENABLED",
            reason = "止まるのは開始前リマインドの送信のみで DB の募集データは書き換えない。募集機能を閉じている間は通知を受け取る画面も閉じており、再開時に古い分を吐き出さないことはクエリ側（開始済みの募集を対象外にする下限）で保証している")
    @BatchEndpoint(name = "recruitment-reminder", description = "募集型予約の未送信リマインドを毎分処理する")
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "recruitment-reminder-batch",
            lockAtLeastFor = "PT50S",
            lockAtMostFor = "PT2M")
    @Transactional
    public void reminderBatch() {
        LocalDateTime now = LocalDateTime.now();
        List<RecruitmentReminderEntity> pending =
                reminderRepository.findSendableReminders(now, PageRequest.of(0, BATCH_SIZE));

        if (pending.isEmpty()) {
            return;
        }

        log.info("F03.11 リマインダーバッチ開始: 対象件数={}", pending.size());
        int successCount = 0;
        int failCount = 0;

        for (RecruitmentReminderEntity reminder : pending) {
            try {
                processReminder(reminder);
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.warn("F03.11 リマインダー送信失敗（継続）: reminderId={}, error={}",
                        reminder.getId(), e.getMessage());
            }
        }

        log.info("F03.11 リマインダーバッチ完了: 成功={}, 失敗={}", successCount, failCount);
    }

    /**
     * 単一リマインダーを処理する。
     */
    private void processReminder(RecruitmentReminderEntity reminder) {
        // 募集情報取得
        RecruitmentListingEntity listing = listingRepository.findById(reminder.getListingId())
                .orElse(null);
        if (listing == null) {
            // 募集が削除済み → sent_at を更新してスキップ
            reminder.markSent(null);
            reminderRepository.save(reminder);
            return;
        }

        // 参加者情報取得
        RecruitmentParticipantEntity participant = participantRepository.findById(reminder.getParticipantId())
                .orElse(null);
        if (participant == null || participant.getUserId() == null) {
            // 参加者が削除済み or チーム参加 → スキップ
            reminder.markSent(null);
            reminderRepository.save(reminder);
            return;
        }

        NotificationScopeType scopeType = listing.getScopeType() == RecruitmentScopeType.TEAM
                ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION;

        Locale locale = Locale.forLanguageTag(userLocaleCache.getLocale(participant.getUserId()));
        String title = messageSource.getMessage(
                "notification.recruitment.reminder.title", new Object[]{listing.getTitle()},
                "リマインド: " + listing.getTitle(), locale);
        String body = messageSource.getMessage(
                "notification.recruitment.reminder.body", new Object[]{listing.getTitle()},
                listing.getTitle() + " が24時間後に開催されます。", locale);
        String actionUrl = "/recruitment-listings/" + listing.getId();

        // 通知作成・配信
        // NotificationHelper の内部で NotificationEntity が保存され、ID が返る
        // ここでは簡易的に notifyAll を使わず notify で1件ずつ処理
        notificationHelper.notify(
                participant.getUserId(),
                "RECRUITMENT_REMINDER",
                title, body,
                "RECRUITMENT_LISTING", listing.getId(),
                scopeType, listing.getScopeId(),
                actionUrl, null
        );

        // sent_at を更新 (notification_id は NotificationHelper から取得できないため null)
        reminder.markSent(null);
        reminderRepository.save(reminder);

        log.debug("F03.11 リマインダー送信: reminderId={}, userId={}, listingId={}",
                reminder.getId(), participant.getUserId(), listing.getId());
    }
}
