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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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
     * バッチジョブの開始を記録する（BatchJobLogger）。
     *
     * @param jobName ジョブ名
     * @return 作成されたログエンティティ
     */
    @Transactional
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
    @Transactional
    public void completeJob(BatchJobLogEntity logEntity, int processedCount) {
        logEntity.complete(processedCount);
        batchJobLogRepository.save(logEntity);
        log.info("バッチジョブ完了: id={}, processedCount={}", logEntity.getId(), processedCount);
    }

    /**
     * バッチジョブの失敗を記録する。
     *
     * @param logEntity    ログエンティティ
     * @param errorMessage エラーメッセージ
     */
    @Transactional
    public void failJob(BatchJobLogEntity logEntity, String errorMessage) {
        logEntity.fail(errorMessage);
        batchJobLogRepository.save(logEntity);
        log.error("バッチジョブ失敗: id={}, error={}", logEntity.getId(), errorMessage);
    }
}
