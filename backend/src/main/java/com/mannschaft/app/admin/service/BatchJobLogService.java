package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.AdminMapper;
import com.mannschaft.app.admin.BatchJobStatus;
import com.mannschaft.app.admin.dto.BatchJobLogResponse;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.repository.BatchJobLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * バッチジョブログサービス。ジョブログの記録・取得を担当する。
 * BatchJobLogger共通ユーティリティとしても機能する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BatchJobLogService {

    private final BatchJobLogRepository batchJobLogRepository;
    private final AdminMapper adminMapper;

    /**
     * 業務ローカル時刻の壁時計（{@code ClockConfig#wallClock}）。
     * {@code batch_job_logs.started_at} / {@code finished_at} と同一の時間基準。
     *
     * <p>既定の {@code Clock} Bean は UTC 固定（{@code ClockConfig#utcClock}）であり、
     * JVM 既定ゾーン基準の壁時計として書かれている {@code LocalDateTime} 列と
     * 直接比較するとオフセット分（JST なら 9 時間）ずれる。そのため
     * {@code @Qualifier("wallClock")} で明示的に壁時計を選ぶ
     * （金型: {@code BlogScheduledPublishBatchService} / {@code BlogPostRevisionService}）。</p>
     */
    @Qualifier("wallClock")
    private final Clock wallClock;

    /**
     * バッチジョブログ一覧を取得する。
     *
     * @param page ページ番号
     * @param size ページサイズ
     * @return ジョブログ一覧
     */
    public List<BatchJobLogResponse> getLogs(int page, int size) {
        Page<BatchJobLogEntity> logPage = batchJobLogRepository.findAllByOrderByStartedAtDesc(
                PageRequest.of(page, size));
        return adminMapper.toBatchJobLogResponseList(logPage.getContent());
    }

    /**
     * ジョブ名でログ一覧を取得する。
     *
     * @param jobName ジョブ名
     * @return ジョブログ一覧
     */
    public List<BatchJobLogResponse> getLogsByJobName(String jobName) {
        return adminMapper.toBatchJobLogResponseList(
                batchJobLogRepository.findByJobNameOrderByStartedAtDesc(jobName));
    }

    /**
     * ジョブ名で直近 1 件の実行履歴を取得する（F10.X 第二陣 — バッチキック API の status 表示用）。
     *
     * <p>Entity のまま返すのは、DTO 変換が必要な利用箇所と、Entity 直参照したい利用箇所
     * （{@code Repository.findFirstBy...} 相当）の両方に応えるため。</p>
     *
     * <p><b>順序は {@code started_at DESC, id DESC} の全順序である。</b>
     * {@code started_at} は秒精度しか持たず、同一秒に複数行が並びうるため
     * （理由の詳細は {@code BatchJobLogRepository#findFirstByJobNameOrderByStartedAtDescIdDesc}）。</p>
     */
    public Optional<BatchJobLogEntity> findLatestByJobName(String jobName) {
        return batchJobLogRepository.findFirstByJobNameOrderByStartedAtDescIdDesc(jobName);
    }

    /**
     * バッチジョブの開始を記録する（BatchJobLogger）。
     *
     * @param jobName ジョブ名
     * @return 作成されたログエンティティ
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchJobLogEntity startJob(String jobName) {
        BatchJobLogEntity entity = BatchJobLogEntity.builder()
                .jobName(jobName)
                .status(BatchJobStatus.RUNNING)
                .startedAt(LocalDateTime.now())
                .build();
        entity = batchJobLogRepository.save(entity);
        log.info("バッチジョブ開始: id={}, jobName={}", entity.getId(), jobName);
        return entity;
    }

    /**
     * バッチジョブの完了を記録する。
     *
     * @param logEntity      ログエンティティ
     * @param processedCount 処理件数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeJob(BatchJobLogEntity logEntity, int processedCount) {
        logEntity.complete(processedCount);
        batchJobLogRepository.save(logEntity);
        log.info("バッチジョブ完了: id={}, processedCount={}", logEntity.getId(), processedCount);
    }

    /**
     * {@code @BackgroundFeaturePolicy} による停止／再開を記録する（Gate 基盤工事④-A）。
     *
     * <p>{@code BackgroundFeatureSkipRecorder} が「状態が変わった時だけ」呼ぶ。
     * 実行を試みて失敗したのではなく<b>意図して実行しなかった</b>ことが後から読み取れる形で
     * 1 行だけ残すこと（FAILED にしてはならない。運用が障害と誤読し、
     * {@code BatchFailedEvent} の通知と区別が付かなくなる）。</p>
     *
     * <h2>向きを status に載せる（読み戻せることが要件である）</h2>
     * <p>本メソッドが書く行は<b>実行そのものではなく、実行しなかった／再開したという境界の目印</b>である。
     * 停止は {@link BatchJobStatus#SKIPPED}、再開は {@link BatchJobStatus#RESUMED} とする。</p>
     *
     * <p><b>両方向を同じ status にしてはならない。</b>
     * {@code BackgroundFeatureSkipRecorder} は「直近 1 行」を読んで前回状態を判定するため、
     * 向きが status から読み取れないと状態判定そのものが成り立たない。
     * SUCCESS は {@code processedCount=0} の実行が 1 回あったように読めて実績を捏造し、
     * FAILED は運用が障害と誤読して {@code BatchFailedEvent} の通知と区別が付かなくなるため、
     * いずれも使わない（再開後の実際の実行は {@code BatchExecutionAspect} が別行として記録する）。</p>
     *
     * <h2>時刻は壁時計 Clock から取る</h2>
     * <p>{@code docs/architecture/datetime_policy_utc_instant_vs_wallclock.md} の方針により、
     * 引数なしの {@code LocalDateTime.now()} は新規に増やさない（番人 {@code DateTimeAndZoneGuardTest} /
     * CMP-023 の凍結台帳は<b>返済対象の技術負債</b>であって、件数を積み増す先ではない）。
     * {@code started_at} / {@code finished_at} は JVM 既定ゾーン基準の壁時計として書かれた
     * {@code LocalDateTime} 列なので、UTC 固定の既定 Clock ではなく {@link #wallClock} を用いる。</p>
     *
     * @param jobName ジョブ名
     * @param skipped スキップへ移った記録なら {@code true}、実行へ戻った記録なら {@code false}
     * @param reason  理由（無効だったフラグキーを含む人間可読の文字列。再開時は null 可）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFeaturePolicyOutcome(String jobName, boolean skipped, String reason) {
        LocalDateTime now = LocalDateTime.now(wallClock);
        BatchJobLogEntity entity = BatchJobLogEntity.builder()
                .jobName(jobName)
                .status(skipped ? BatchJobStatus.SKIPPED : BatchJobStatus.RESUMED)
                .startedAt(now)
                .finishedAt(now)
                .processedCount(0)
                .errorMessage(reason)
                .build();
        entity = batchJobLogRepository.save(entity);
        log.info("バッチジョブ停止/再開を記録: id={}, jobName={}, skipped={}, reason={}",
                entity.getId(), jobName, skipped, reason);
    }

    /**
     * バッチジョブの失敗を記録する。
     *
     * @param logEntity    ログエンティティ
     * @param errorMessage エラーメッセージ
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failJob(BatchJobLogEntity logEntity, String errorMessage) {
        logEntity.fail(errorMessage);
        batchJobLogRepository.save(logEntity);
        log.error("バッチジョブ失敗: id={}, error={}", logEntity.getId(), errorMessage);
    }
}
