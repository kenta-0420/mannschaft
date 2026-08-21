package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.AdminMapper;
import com.mannschaft.app.admin.BatchJobStatus;
import com.mannschaft.app.admin.dto.BatchJobLogResponse;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.repository.BatchJobLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
     */
    public Optional<BatchJobLogEntity> findLatestByJobName(String jobName) {
        return batchJobLogRepository.findFirstByJobNameOrderByStartedAtDesc(jobName);
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
     * <h2>どちらの向きの遷移も {@link BatchJobStatus#SKIPPED} で記録する</h2>
     * <p>本メソッドが書く行は<b>実行そのものではなく、実行しなかった／再開したという境界の目印</b>である。
     * SUCCESS を使うと {@code processedCount=0} の実行が 1 回あったように読め、実績を捏造する。
     * FAILED を使うと運用が障害と誤読し {@code BatchFailedEvent} の通知と区別が付かなくなる。
     * どちらも「この行は実行ではない」ことを表せないため、両方向とも SKIPPED とし、
     * 向きは {@code errorMessage} の文面で読み取れるようにする
     * （再開後の実際の実行は {@code BatchExecutionAspect} が別行として記録する）。</p>
     *
     * @param jobName ジョブ名
     * @param skipped スキップへ移った記録なら {@code true}、実行へ戻った記録なら {@code false}
     * @param reason  理由（無効だったフラグキーを含む人間可読の文字列。再開時は null 可）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFeaturePolicyOutcome(String jobName, boolean skipped, String reason) {
        LocalDateTime now = LocalDateTime.now();
        BatchJobLogEntity entity = BatchJobLogEntity.builder()
                .jobName(jobName)
                .status(BatchJobStatus.SKIPPED)
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
