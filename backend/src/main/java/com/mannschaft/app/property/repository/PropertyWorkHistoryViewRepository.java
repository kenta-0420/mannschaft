package com.mannschaft.app.property.repository;

import com.mannschaft.app.property.entity.PropertyWorkHistoryViewEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 物件履歴閲覧監査ログリポジトリ。
 * F09.13 設計書 §3 property_work_history_views に対応。
 * 90日経過後に物理削除する TTL バッチ用クエリを含む。
 */
public interface PropertyWorkHistoryViewRepository
        extends JpaRepository<PropertyWorkHistoryViewEntity, Long> {

    /**
     * パッケージ閲覧履歴を新しい順に取得する。
     */
    Page<PropertyWorkHistoryViewEntity> findByPackageIdOrderByViewedAtDesc(
            Long packageId, Pageable pageable);

    /**
     * ユーザー別閲覧履歴を新しい順に取得する。
     */
    Page<PropertyWorkHistoryViewEntity> findByUserIdOrderByViewedAtDesc(
            Long userId, Pageable pageable);

    /**
     * パッケージ × ユーザー × 期間で件数を取得する（レート制限・閲覧頻度監視用）。
     */
    long countByUserIdAndViewedAtAfter(Long userId, LocalDateTime since);

    /**
     * 指定日時より古いログを物理削除する（90日 TTL バッチ用）。
     */
    @Modifying
    @Query("DELETE FROM PropertyWorkHistoryViewEntity v WHERE v.viewedAt < :threshold")
    int deleteByViewedAtBefore(@Param("threshold") LocalDateTime threshold);

    /**
     * パッケージ配下の閲覧ログを物理削除する（パッケージ物理削除時の補助）。
     */
    @Modifying
    @Query("DELETE FROM PropertyWorkHistoryViewEntity v WHERE v.packageId = :packageId")
    int deleteByPackageId(@Param("packageId") Long packageId);

    /**
     * パッケージの最近の閲覧ログを取得する。
     */
    List<PropertyWorkHistoryViewEntity> findTop50ByPackageIdOrderByViewedAtDesc(Long packageId);
}
