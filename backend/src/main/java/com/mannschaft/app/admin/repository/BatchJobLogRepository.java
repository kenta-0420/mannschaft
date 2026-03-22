package com.mannschaft.app.admin.repository;

import com.mannschaft.app.admin.BatchJobStatus;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * バッチジョブログリポジトリ。
 */
public interface BatchJobLogRepository extends JpaRepository<BatchJobLogEntity, Long> {

    /**
     * ジョブ名で実行履歴を取得する。
     */
    List<BatchJobLogEntity> findByJobNameOrderByStartedAtDesc(String jobName);

    /**
     * ステータス別にジョブログを取得する。
     */
    Page<BatchJobLogEntity> findByStatusOrderByStartedAtDesc(BatchJobStatus status, Pageable pageable);

    /**
     * 全ジョブログをページングで取得する。
     */
    Page<BatchJobLogEntity> findAllByOrderByStartedAtDesc(Pageable pageable);
}
