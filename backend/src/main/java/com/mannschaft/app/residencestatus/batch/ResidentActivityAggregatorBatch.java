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
 * 居住者アクティビティ日次集計バッチ（F09.16 S3-B）。
 *
 * <p>毎日 03:00 に全居住者のスナップショットを日次生成する。
 *
 * <p>v1 は placeholder（score=0）を UPSERT するのみ。
 * 実際のスコア算定は将来の Activity イベント購読実装後に差し替える。
 *
 * <p>TODO: 組織・居住者の走査は residencestatus ドメイン外の情報が必要なため、
 *     将来 OrganizationResidentListPort（インターフェース）を介した
 *     EventListener 化が必要。クロスドメイン参照を避けるため v1 は空実装とする。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ResidentActivityAggregatorBatch {

    private final ResidentActivityAggregatorService aggregatorService;

    /**
     * 毎日 03:00 に全居住者のスナップショットを日次生成する。
     *
     * <p>v1 stub: 組織・居住者の走査手段が別ドメインのため空実装。
     * 将来は OrganizationResidentListPort 経由で居住者一覧を取得し、
     * {@link ResidentActivityAggregatorService#upsertDailySnapshot} を呼び出す。
     * TODO: 将来は EventListener 化予定（クロスドメイン依存の解消）
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対象日を指定できない日次スナップショット生成であり、30日で rotation 削除される。止めた期間の居住者アクティビティは復元できない")
    @BatchEndpoint(name = "residencestatus-activity-aggregator-daily", description = "全居住者アクティビティスナップショットを毎日 03:00 に生成する（v1 stub）")
    @Scheduled(cron = "0 0 3 * * *")
    // 起動間隔は日次 03:00。全居住者ぶんのスナップショット生成であり利用者数に比例して伸びる。余裕を取り 2 時間を上限とする。
    @SchedulerLock(name = "residenceActivityAggregatorDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT2H")
    public void aggregateDaily() {
        log.info("[ResidentActivityAggregatorBatch] 日次集計 開始");
        // TODO: 組織・居住者を走査して upsertDailySnapshot を呼ぶ
        // 現 v1 は組織リスト取得手段が別ドメインのため、空実装でコンパイルのみ確認
        // TODO: 将来 EventListener 化予定（クロスドメイン依存を解消）
        log.info("[ResidentActivityAggregatorBatch] 日次集計 完了（v1 stub）");
    }
}
