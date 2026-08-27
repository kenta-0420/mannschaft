package com.mannschaft.app.residencestatus.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.residencestatus.service.AnnualReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 締切超過キャンペーン自動クローズバッチ（F09.16 S3-A）。
 *
 * <p>毎日 04:00 に実行し、deadlineAt を超過した未クローズのキャンペーンを自動クローズする。
 * クローズ後は {@link com.mannschaft.app.residencestatus.event.AnnualReviewClosedEvent} が発火する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnnualReviewDeadlineCloseBatch {

    private final AnnualReviewService annualReviewService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。期限超過の年次見直しキャンペーンのクローズであり、再開後に同じ条件で拾い直せる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "residencestatus-annual-review-close-daily", description = "期限超過の年次見直しキャンペーンを毎日 04:00 に自動クローズする")
    @Scheduled(cron = "0 0 4 * * *")
    // 起動間隔は日次 04:00。期限超過キャンペーンのクローズのみで対象は少数。余裕を取り 30 分を上限とする。
    @SchedulerLock(name = "residenceAnnualReviewCloseDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void autoClose() {
        log.info("[AnnualReviewDeadlineCloseBatch] 開始");
        try {
            annualReviewService.autoCloseExpiredReviews();
            log.info("[AnnualReviewDeadlineCloseBatch] 完了");
        } catch (Exception e) {
            log.error("[AnnualReviewDeadlineCloseBatch] エラー", e);
        }
    }
}
