package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.AnalyticsErrorCode;
import com.mannschaft.app.analytics.BackfillTarget;
import com.mannschaft.app.analytics.dto.BackfillJobResponse;
import com.mannschaft.app.analytics.dto.BackfillRequest;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 過去データの再集計（バックフィル）。非同期実行。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsBackfillService {

    private final DailyAggregationBatchService dailyBatch;
    private final MonthlyCohortBatchService cohortBatch;
    private final NotificationService notificationService;
    private final UserRoleRepository userRoleRepository;
    private final MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;
    private static final long MAX_BACKFILL_DAYS = 183; // 6ヶ月

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * バックフィルを開始する。非同期実行。
     */
    public BackfillJobResponse startBackfill(BackfillRequest request) {
        if (request.getFrom().isAfter(request.getTo())) {
            throw new BusinessException(AnalyticsErrorCode.ANALYTICS_005);
        }
        long days = ChronoUnit.DAYS.between(request.getFrom(), request.getTo()) + 1;
        if (days > MAX_BACKFILL_DAYS) {
            throw new BusinessException(AnalyticsErrorCode.ANALYTICS_004);
        }
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(AnalyticsErrorCode.ANALYTICS_003);
        }

        String jobId = "backfill-" + LocalDate.now().toString().replace("-", "") + "-"
                + LocalTime.now().toString().replace(":", "").substring(0, 6);

        executeAsync(request, jobId);

        return new BackfillJobResponse(
                jobId, "RUNNING", request.getFrom(), request.getTo(),
                request.getTargets().stream().map(Enum::name).toList(),
                LocalDateTime.now()
        );
    }

    @Async
    protected void executeAsync(BackfillRequest request, String jobId) {
        try {
            log.info("バックフィル開始: jobId={}, from={}, to={}, targets={}",
                    jobId, request.getFrom(), request.getTo(), request.getTargets());

            LocalDate current = request.getFrom();
            int processedDays = 0;
            long totalDays = ChronoUnit.DAYS.between(request.getFrom(), request.getTo()) + 1;

            while (!current.isAfter(request.getTo())) {
                try {
                    if (request.getTargets().stream().anyMatch(t -> t != BackfillTarget.COHORTS)) {
                        dailyBatch.aggregateForDate(current);
                    }
                    processedDays++;
                    if (processedDays % 10 == 0) {
                        log.info("バックフィル進捗: {}/{} 日完了", processedDays, totalDays);
                    }
                } catch (Exception e) {
                    log.warn("バックフィル: date={} でエラー発生、スキップ", current, e);
                }
                current = current.plusDays(1);
            }

            // COHORTS が含まれる場合はコホート再計算
            if (request.getTargets().contains(BackfillTarget.COHORTS)) {
                cohortBatch.recalculateForMonth(request.getTo().withDayOfMonth(1));
            }

            log.info("バックフィル完了: jobId={}", jobId);

            // SYSTEM_ADMIN へのプッシュ通知
            List<Long> systemAdmins = userRoleRepository.findSystemAdminUserIds();
            // Issue #2715 CMP-055 ロットC-6: 受信者ごとに locale が異なるため、ループの外で一括解決する（N+1 防止）。
            // Codex 検分是正（PR #2873）: バルク取得自体を try で隔離し、失敗時は既定 locale ("ja") で継続する。
            Map<Long, String> locales;
            try {
                locales = userLocaleCache.getLocales(systemAdmins);
            } catch (Exception e) {
                log.warn("locale 一括解決に失敗（既定 locale で継続）: error={}", e.getMessage());
                locales = Map.of();
            }
            for (Long adminUserId : systemAdmins) {
                try {
                    Locale locale = Locale.forLanguageTag(locales.getOrDefault(adminUserId, "ja"));
                    String title = messageSource.getMessage(
                            "notification.analytics.backfillCompleted.title", null,
                            "バックフィル完了", locale);
                    String body = messageSource.getMessage(
                            "notification.analytics.backfillCompleted.body",
                            new Object[]{jobId, request.getFrom(), request.getTo()},
                            "バックフィルジョブ " + jobId + " が完了しました（期間: " + request.getFrom()
                                    + " 〜 " + request.getTo() + "）。",
                            locale);
                    notificationService.createNotification(
                            adminUserId, "BACKFILL_COMPLETED", NotificationPriority.LOW,
                            title, body,
                            "BACKFILL_JOB", null,
                            NotificationScopeType.SYSTEM, null,
                            "/system-admin/analytics", null
                    );
                } catch (Exception e) {
                    // 通知失敗を隔離し、他の SYSTEM_ADMIN への配信を継続する
                    // （非DB例外・MessageFormatエラー等を隔離するもので、本処理の巻き戻りは防がない）。
                    log.warn("バックフィル完了通知送信失敗（継続）: userId={}, jobId={}, error={}",
                            adminUserId, jobId, e.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }
}
