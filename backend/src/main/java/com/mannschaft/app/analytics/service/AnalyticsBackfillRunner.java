package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.BackfillTarget;
import com.mannschaft.app.analytics.dto.BackfillRequest;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * バックフィルの非同期実行 Bean（Issue #2990 L4）。
 *
 * <h2>是正前の欠陥: {@code @Async} が失効し API リクエストスレッドで全期間を同期実行していた</h2>
 * <p>是正前は {@link AnalyticsBackfillService} が<b>自クラス内の</b> {@code @Async protected
 * executeAsync(...)} を無修飾で呼んでいた。Spring のプロキシを経ないため {@code @Async} は失効し、
 * 最大 183 日ぶんの日次集計とコホート再計算、さらに SYSTEM_ADMIN 全員への通知送信が
 * <b>HTTP リクエストスレッド上で同期実行</b>されていた。
 * {@code startBackfill} は {@code status="RUNNING"} を返す契約なのに、実際にはジョブが
 * 完全に終わるまでレスポンスを返さず、長期間指定ではリクエストタイムアウトに至る。</p>
 *
 * <p>なお {@code startBackfill} 側にも呼び出し元コントローラ側にも {@code @Transactional} は無いため、
 * <b>この件は「業務処理が巻き戻る」種類の欠陥ではない</b>（通知の失敗で消える業務トランザクションが
 * そもそも存在しない）。実害は同期実行によるレイテンシとリクエストスレッドの占有である。</p>
 *
 * <h2>executor に {@code job-pool} を選ぶ理由</h2>
 * <p>{@code @Async} を無指定にすると {@code @Primary} の {@code event-pool}（core=2/max=5）へ載る。
 * バックフィルは数分〜数十分級の重い処理であり、{@code event-pool} を占有すると他ドメインの
 * AFTER_COMMIT 通知配送リスナーが軒並み詰まる（Issue #2953 の event-pool 自己飽和と同じ経路）。
 * {@code job-pool} は「定期実行タスクや重い処理」用のプールであり、通知配送と資源を分離できる。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsBackfillRunner {

    private final DailyAggregationBatchService dailyBatch;
    private final MonthlyCohortBatchService cohortBatch;
    private final NotificationService notificationService;
    private final UserRoleRepository userRoleRepository;
    private final MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;

    /** 多重起動防止フラグ。{@link #tryAcquire()} で取得し {@link #executeAsync} の finally で解放する。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 実行権を取得する。
     *
     * @return 取得できたら {@code true}、既に実行中なら {@code false}
     */
    public boolean tryAcquire() {
        return running.compareAndSet(false, true);
    }

    /**
     * 実行権を解放する。
     *
     * <p>{@code job-pool} は AbortPolicy のため {@link #executeAsync} の投入自体が拒否されうる。
     * その場合 {@code finally} に到達しないので、呼び出し元が本メソッドで明示的に解放する。</p>
     */
    public void release() {
        running.set(false);
    }

    /**
     * バックフィルを実行する（別スレッド）。
     *
     * @param request バックフィル要求
     * @param jobId   ジョブID（ログ・通知本文に使う）
     */
    @Async("job-pool")
    public void executeAsync(BackfillRequest request, String jobId) {
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
                    // 通知失敗を隔離し、他の SYSTEM_ADMIN への配信を継続する。
                    // 本メソッドは業務トランザクションの外（job-pool の別スレッド）で走るため、
                    // ここでの catch は「巻き戻りを防いでいる」のではなく、単に他の受信者への配信を続けるためのもの。
                    log.warn("バックフィル完了通知送信失敗（継続）: userId={}, jobId={}, error={}",
                            adminUserId, jobId, e.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }
}
