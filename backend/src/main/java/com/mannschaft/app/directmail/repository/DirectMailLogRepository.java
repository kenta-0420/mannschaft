package com.mannschaft.app.directmail.repository;

import com.mannschaft.app.directmail.entity.DirectMailLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ダイレクトメール配信ログリポジトリ。
 */
public interface DirectMailLogRepository extends JpaRepository<DirectMailLogEntity, Long> {

    /**
     * スコープ別のメール一覧をページネーション付きで取得する。
     */
    Page<DirectMailLogEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            String scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープとIDでメールを取得する。
     */
    Optional<DirectMailLogEntity> findByIdAndScopeTypeAndScopeId(Long id, String scopeType, Long scopeId);

    /**
     * 予約送信の対象を取得する。
     */
    List<DirectMailLogEntity> findByStatusAndScheduledAtBefore(String status, LocalDateTime now);

    /**
     * スコープ別のメール数を取得する。
     */
    long countByScopeTypeAndScopeId(String scopeType, Long scopeId);
}
