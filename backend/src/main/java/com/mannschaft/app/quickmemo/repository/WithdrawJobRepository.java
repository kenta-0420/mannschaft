package com.mannschaft.app.quickmemo.repository;

import com.mannschaft.app.quickmemo.entity.WithdrawJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 退会 SAGA ジョブリポジトリ。
 */
public interface WithdrawJobRepository extends JpaRepository<WithdrawJobEntity, Long> {

    /**
     * ユーザーと状態でジョブを取得する。
     */
    Optional<WithdrawJobEntity> findByUserIdAndStatus(Long userId, String status);

    /**
     * ユーザーと状態のジョブが存在するか確認する。
     */
    boolean existsByUserIdAndStatus(Long userId, String status);

    /**
     * 処理待ち・リトライ可能なジョブを取得する（バッチ用）。
     */
    @Query("""
            SELECT j FROM WithdrawJobEntity j
            WHERE j.status = 'PENDING'
               OR (j.status = 'FAILED' AND j.retryCount < 3)
            ORDER BY j.createdAt ASC
            """)
    List<WithdrawJobEntity> findPendingOrRetryableJobs();
}
