package com.mannschaft.app.residencestatus.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.residencestatus.service.ResidentActivityAggregatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。30日以前の居住者アクティビティ snapshot の論理削除であり、再開後に同じ条件で拾い直せる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "residencestatus-activity-rotation-daily", description = "30 日以前の居住者アクティビティ snapshot を毎日 05:00 に論理削除する")
    @Scheduled(cron = "0 0 5 * * *")
    // 起動間隔は日次 05:00。30 日以前スナップショットの論理削除（一括 UPDATE）のみで最悪でも数分。余裕を取り 30 分を上限とする。
    @SchedulerLock(name = "residenceActivityRotationDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
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
