package com.mannschaft.app.safetycheck.repository;

import com.mannschaft.app.safetycheck.SafetyCheckScopeType;
import com.mannschaft.app.safetycheck.SafetyCheckStatus;
import com.mannschaft.app.safetycheck.entity.SafetyCheckEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 安否確認リポジトリ。
 */
public interface SafetyCheckRepository extends JpaRepository<SafetyCheckEntity, Long> {

    /**
     * スコープ別の安否確認一覧を作成日時降順で取得する。
     */
    Page<SafetyCheckEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            SafetyCheckScopeType scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープ別かつステータス指定で安否確認一覧を取得する。
     */
    Page<SafetyCheckEntity> findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
            SafetyCheckScopeType scopeType, Long scopeId, SafetyCheckStatus status, Pageable pageable);

    /**
     * アクティブな安否確認一覧を取得する（リマインド処理用）。
     */
    List<SafetyCheckEntity> findByStatus(SafetyCheckStatus status);

    /**
     * スコープ別の安否確認履歴を取得する（クローズ済み）。
     */
    @Query("SELECT sc FROM SafetyCheckEntity sc WHERE sc.scopeType = :scopeType AND sc.scopeId = :scopeId "
            + "AND sc.status = 'CLOSED' ORDER BY sc.closedAt DESC")
    Page<SafetyCheckEntity> findClosedByScopeOrderByClosedAtDesc(
            @Param("scopeType") SafetyCheckScopeType scopeType,
            @Param("scopeId") Long scopeId,
            Pageable pageable);

    @Query("SELECT sc FROM SafetyCheckEntity sc WHERE sc.title LIKE %:keyword% OR sc.message LIKE %:keyword%")
    java.util.List<SafetyCheckEntity> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
