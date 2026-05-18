package com.mannschaft.app.residencestatus.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.residencestatus.service.ResidentActivityAggregatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 居住者アクティビティスナップショット 30 日ローテーションバッチ（F09.16 S3-B）。
 *
 * <p>毎日 05:00 に 30 日以前の snapshot を論理削除する。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ResidentActivityRotationBatch {

    private final ResidentActivityAggregatorService aggregatorService;

    /**
     * 毎日 05:00 に 30 日以前の snapshot を論理削除する。
     */
    @BatchEndpoint(name = "residencestatus-activity-rotation-daily", description = "30 日以前の居住者アクティビティ snapshot を毎日 05:00 に論理削除する")
    @Scheduled(cron = "0 0 5 * * *")
    public void rotateOldSnapshots() {
        log.info("[ResidentActivityRotationBatch] ローテーション 開始");
        try {
            aggregatorService.deleteOldSnapshots();
            log.info("[ResidentActivityRotationBatch] ローテーション 完了");
        } catch (Exception e) {
            log.error("[ResidentActivityRotationBatch] エラー", e);
        }
    }
}
