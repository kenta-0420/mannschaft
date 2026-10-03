package com.mannschaft.app.chart.batch;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * chart_records.customer_user_id 孤児補正バッチ（Phase D-5）。
 *
 * <p>{@link com.mannschaft.app.chart.event.ChartPurgeEventListener} が
 * {@link com.mannschaft.app.gdpr.event.AccountPurgedEvent} の処理に失敗した場合、
 * {@code customer_user_id} に退会済み（物理削除済み）ユーザーの ID が残存する（孤児）。</p>
 *
 * <p>本バッチは毎日 03:00（JST）に孤児を検出し、{@code customer_user_id} を NULL 化することで
 * GDPR 遵守状態を補正する夜次補正バッチ（三重防御の第三層）である。</p>
 *
 * <h3>三重防御パターン</h3>
 * <ol>
 *   <li>第一層: {@link com.mannschaft.app.chart.event.ChartPurgeEventListener}
 *       — {@code AccountPurgedEvent} を受けてリアルタイムに個別 NULL 化（best-effort）</li>
 *   <li>第二層: メール配信基盤 outbox パターンによるリトライ（Phase 18）</li>
 *   <li>第三層: 本バッチ — 夜次一括補正（確実な網羅）</li>
 * </ol>
 *
 * <h3>孤児の定義</h3>
 * <p>{@code chart_records.customer_user_id IS NOT NULL} かつ
 * {@code customer_user_id} が指す {@code users} レコードが存在しない（物理削除済み）行。
 * FK {@code fk_cr_customer} は V62.013 で撤廃済みのため、物理削除後も
 * {@code customer_user_id} に元の値が残存しうる。</p>
 *
 * <h3>実行タイミング</h3>
 * <ul>
 *   <li>毎日 03:00（JST）— team-member-count-backfill-daily（02:00）と時間をずらし、
 *       夜次バッチの集中を避ける</li>
 *   <li>{@link SchedulerLock} で複数インスタンス起動時の二重実行を防ぐ</li>
 * </ul>
 *
 * <p>設計根拠:
 * {@code docs/architecture/account_purge_cross_domain_refactor.md} §4 Phase D-5</p>
 *
 * @see ChartRecordRepository#anonymizeOrphanCustomerUserId()
 * @see com.mannschaft.app.chart.event.ChartPurgeEventListener
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChartPurgeBackfillBatchService {

    /** ShedLock のジョブ名。 */
    static final String JOB_NAME = "chartPurgeBackfillBatch";

    private final ChartRecordRepository chartRecordRepository;

    /**
     * 毎日 03:00（JST）に実行される孤児補正エントリポイント。
     *
     * <p>{@code AccountPurgedEvent} 処理漏れの {@code chart_records.customer_user_id} を一括補正する。
     * 孤児が 0 件の場合は正常完了としてログを記録する。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると論理削除済みチャートの物理削除 backfill が進まず、消したはずの個人データが残り続けて削除要求との整合が壊れる")
    @BatchEndpoint(
            name = "chart-purge-backfill-daily",
            description = "AccountPurgedEvent 処理漏れの chart_records.customer_user_id を毎日 03:00 に補正する"
    )
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = JOB_NAME, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void backfill() {
        log.info("[ChartPurgeBackfill] 孤児補正バッチ開始");
        int fixed = chartRecordRepository.anonymizeOrphanCustomerUserId();
        log.info("[ChartPurgeBackfill] chart_records 孤児補正完了: {}件", fixed);
    }
}
