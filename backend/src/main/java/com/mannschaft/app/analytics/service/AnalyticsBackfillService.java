package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.AnalyticsErrorCode;
import com.mannschaft.app.analytics.dto.BackfillJobResponse;
import com.mannschaft.app.analytics.dto.BackfillRequest;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * 過去データの再集計（バックフィル）。受付・検証のみを担い、実行は非同期 Bean へ委譲する。
 *
 * <p>Issue #2990 L4: 実行本体は {@link AnalyticsBackfillRunner} へ切り出した。是正前は本クラス内の
 * {@code @Async protected executeAsync} を自己呼び出ししており、プロキシを経ないため
 * {@code @Async} が失効し、最大183日ぶんの集計と通知送信が HTTP リクエストスレッド上で
 * 同期実行されていた（詳細は {@link AnalyticsBackfillRunner} の javadoc）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsBackfillService {

    private final AnalyticsBackfillRunner backfillRunner;

    private static final long MAX_BACKFILL_DAYS = 183; // 6ヶ月

    /**
     * バックフィルを開始する。実行は非同期（{@code job-pool}）。
     */
    public BackfillJobResponse startBackfill(BackfillRequest request) {
        if (request.getFrom().isAfter(request.getTo())) {
            throw new BusinessException(AnalyticsErrorCode.ANALYTICS_005);
        }
        long days = ChronoUnit.DAYS.between(request.getFrom(), request.getTo()) + 1;
        if (days > MAX_BACKFILL_DAYS) {
            throw new BusinessException(AnalyticsErrorCode.ANALYTICS_004);
        }
        if (!backfillRunner.tryAcquire()) {
            throw new BusinessException(AnalyticsErrorCode.ANALYTICS_003);
        }

        String jobId = "backfill-" + LocalDate.now().toString().replace("-", "") + "-"
                + LocalTime.now().toString().replace(":", "").substring(0, 6);

        try {
            // 別 Bean のプロキシ経由で呼ぶ（自己呼び出しでは @Async が失効する）。
            backfillRunner.executeAsync(request, jobId);
        } catch (RuntimeException e) {
            // job-pool は AbortPolicy。投入拒否時に実行権を握ったままにしない。
            backfillRunner.release();
            throw e;
        }

        return new BackfillJobResponse(
                jobId, "RUNNING", request.getFrom(), request.getTo(),
                request.getTargets().stream().map(Enum::name).toList(),
                LocalDateTime.now()
        );
    }
}
