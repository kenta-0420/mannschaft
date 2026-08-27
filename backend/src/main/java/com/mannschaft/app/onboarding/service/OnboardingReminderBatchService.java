package com.mannschaft.app.onboarding.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.onboarding.OnboardingProgressStatus;
import com.mannschaft.app.onboarding.entity.OnboardingProgressEntity;
import com.mannschaft.app.onboarding.event.OnboardingReminderNotificationEvent;
import com.mannschaft.app.onboarding.repository.OnboardingProgressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * オンボーディングリマインダーバッチサービス。
 * 期限前リマインダーおよび期限超過通知を毎日送信する。
 *
 * <h2>Issue #2834 / CMP-056 第2群ロット2 による是正</h2>
 * <p>是正前は<b>バッチ全体を 1 つの {@code @Transactional} で包みながら進捗単位で catch</b> していた。
 * 1 件の失敗は握りつぶされたように見えて、実際には rollback-only が残るため
 * <b>全進捗の {@code last_reminded_at} がコミット時に巻き戻り</b>、当日中の重複防止が効かなくなっていた
 * （同日中に管理者が手動リマインドを打つと二重に届く）。非トランザクションのオーケストレータ ＋
 * 進捗ごと {@link OnboardingReminderRunner}（{@code REQUIRES_NEW}）＋ {@code AFTER_COMMIT} 通知の形へ
 * 是正した（CMP-035 の金型）。</p>
 *
 * <h2>分類の判定</h2>
 * <p>本バッチは通知だけでなく<b>業務状態（{@code onboarding_progresses.last_reminded_at}）を更新する</b>。
 * この列は「今日すでにリマインド済みか」の判定に使われる冪等キーであり、通知と同時に確定しなければ
 * ならない。よって確定設計の「バッチで業務状態も更新する」に該当し、非TXループ →
 * 進捗ごと REQUIRES_NEW → その中の {@code AFTER_COMMIT} で通知、を採る。</p>
 *
 * <h2>配送経路は手動リマインドと共用する</h2>
 * <p>是正前は {@code notificationHelper.notify} をこのクラスから直接呼んでおり、第1群で是正済みの
 * 手動リマインド（{@code OnboardingProgressService#sendReminders}）と<b>同じ
 * {@code ONBOARDING_REMINDER} 通知が2つの経路から出ていた</b>。本ロットでは重複した配送経路を作らず、
 * 第1群で新設した {@link OnboardingReminderNotificationEvent} /
 * {@code OnboardingReminderNotificationListener} を再利用する。文言の違い（期限日を埋める・
 * 超過通知は種別が {@code ONBOARDING_OVERDUE}）はイベントの
 * {@link OnboardingReminderNotificationEvent.Kind} で表現する。</p>
 *
 * <h2>外向き契約</h2>
 * <p>{@code processReminders} は是正前後とも戻り値 {@code void}。{@code @BatchEndpoint} 経由の
 * 管理コンソール実行も戻り値を持たないため、FE / OpenAPI への波及はない。ログ上の
 * {@code reminder} / {@code overdue} は<b>「通知の到達件数」ではなく「リマインドを確定した進捗数」</b>を
 * 意味するようになった（非同期化により、バッチ終了時点では配送結果が判明しないため）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingReminderBatchService {

    private final OnboardingProgressRepository progressRepository;
    private final OnboardingReminderRunner onboardingReminderRunner;

    /**
     * 毎日9時（JST）に実行。期限前リマインダーと期限超過通知を送信する。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。オンボーディングの期限リマインド送信。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "onboarding-reminder-daily", description = "オンボーディング期限前リマインドと超過通知を毎日 09:00 に送信する")
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "onboardingReminderBatch", lockAtMostFor = "30m", lockAtLeastFor = "5m")
    public void processReminders() {
        log.info("オンボーディングリマインダーバッチ開始");
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Tokyo"));

        // 対象抽出はオーケストレータ側（TX 外）。以降の更新はここには参加しない。
        // 期限超過通知: deadline_at < now かつ IN_PROGRESS
        List<Long> overdueIds = progressRepository
                .findByStatusAndDeadlineAtBefore(OnboardingProgressStatus.IN_PROGRESS, now)
                .stream().map(OnboardingProgressEntity::getId).toList();
        // 期限前リマインダー: テンプレートの reminder_days_before の判定は Runner が独立TX内で行う
        List<Long> upcomingIds = progressRepository
                .findByStatusAndDeadlineAtBetween(OnboardingProgressStatus.IN_PROGRESS, now, now.plusDays(30))
                .stream().map(OnboardingProgressEntity::getId).toList();

        Counter overdueResult = remindAll(overdueIds, OnboardingReminderNotificationEvent.Kind.OVERDUE, now);
        Counter reminderResult = remindAll(
                upcomingIds, OnboardingReminderNotificationEvent.Kind.DEADLINE_APPROACHING, now);

        int failed = overdueResult.failed + reminderResult.failed;
        String summary = "オンボーディングリマインダーバッチ完了: reminder={}, overdue={}, 失敗={}, "
                + "firstFailedProgressId={}";
        Long firstFailedProgressId = overdueResult.firstFailedProgressId != null
                ? overdueResult.firstFailedProgressId : reminderResult.firstFailedProgressId;
        if (failed > 0) {
            log.error(summary, reminderResult.confirmed, overdueResult.confirmed, failed, firstFailedProgressId);
        } else {
            log.info(summary, reminderResult.confirmed, overdueResult.confirmed, failed, firstFailedProgressId);
        }
    }

    /** 進捗IDの一覧を1件ずつ独立トランザクションで確定する（失敗は記録して次へ）。 */
    private Counter remindAll(List<Long> progressIds,
                              OnboardingReminderNotificationEvent.Kind kind,
                              LocalDateTime now) {
        Counter counter = new Counter();
        for (Long progressId : progressIds) {
            try {
                if (onboardingReminderRunner.remindOne(progressId, kind, now)) {
                    counter.confirmed++;
                }
            } catch (Exception e) {
                // catch は必ずオーケストレータ側（TX 外）で行う。Runner の内側で catch すると
                // rollback-only のトランザクションで記録が消える。
                counter.failed++;
                if (counter.firstFailedProgressId == null) {
                    counter.firstFailedProgressId = progressId;
                }
                log.error("オンボーディングリマインドの確定に失敗（継続）: kind={}, progressId={}",
                        kind, progressId, e);
            }
        }
        return counter;
    }

    /** 1 種別ぶんの集計値。 */
    private static final class Counter {
        private int confirmed;
        private int failed;
        private Long firstFailedProgressId;
    }
}
