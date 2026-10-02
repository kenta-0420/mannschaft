package com.mannschaft.app.errorreport.batch;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.errorreport.repository.ErrorReportOccurrenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link com.mannschaft.app.errorreport.event.ErrorReportPurgeEventListener} の
 * 処理漏れを夜次補正するバッチ（Phase D-7）。
 *
 * <p>{@code error_report_occurrences} テーブルに残存する孤児レコード
 * （{@code users} テーブルに対応する行が存在しない {@code user_id} を参照し、
 * かつ {@code ip_address} / {@code user_agent} が非 NULL の行）を検出し、
 * これらの PII カラムを NULL 化する。</p>
 *
 * <p><b>孤児の定義:</b>
 * {@code error_report_occurrences.user_id} が指す {@code users} レコードが
 * 物理削除済み（存在しない）で、かつ {@code ip_address} または {@code user_agent}
 * が非 NULL であること。
 *
 * {@code user_id} FK（{@code fk_ero_user_id}）は V62.015 で撤廃済みのため、
 * ユーザー物理削除後も {@code user_id} に元の値が残る点に注意。
 * よって孤児の検出は LEFT JOIN + {@code u.id IS NULL} で行う。</p>
 *
 * <p>設計根拠:
 * {@code docs/architecture/account_purge_cross_domain_refactor.md} §4 Phase D-7</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErrorReportPurgeBackfillBatchService {

    private final ErrorReportOccurrenceRepository errorReportOccurrenceRepository;

    /**
     * {@code error_report_occurrences} 孤児匿名化補正バッチ。毎日 03:00（JST）に実行する。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>孤児レコード（退会済みユーザーの行で PII が残存するもの）を一括 UPDATE で匿名化</li>
     *   <li>匿名化件数を INFO ログに記録する</li>
     * </ol>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると論理削除済みエラーレポートの物理削除 backfill が進まず、消したはずの本文と個人データが残り続ける")
    @BatchEndpoint(
            name = "error-report-purge-backfill-daily",
            description = "AccountPurgedEvent 処理漏れの error_report_occurrences を毎日 03:00 に補正する（GDPR）"
    )
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "errorReportPurgeBackfillBatch", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void backfill() {
        int anonymized = errorReportOccurrenceRepository.anonymizeOrphanByUserId();
        log.info("error_report_occurrences 孤児匿名化補正: {}件", anonymized);
    }
}
