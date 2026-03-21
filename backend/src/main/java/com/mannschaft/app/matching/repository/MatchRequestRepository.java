package com.mannschaft.app.matching.repository;

import com.mannschaft.app.matching.MatchRequestStatus;
import com.mannschaft.app.matching.entity.MatchRequestEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 募集投稿リポジトリ。
 */
public interface MatchRequestRepository extends JpaRepository<MatchRequestEntity, Long> {

    /**
     * チームの募集一覧をページング取得する。
     */
    Page<MatchRequestEntity> findByTeamIdOrderByCreatedAtDesc(Long teamId, Pageable pageable);

    /**
     * 悲観ロック付きで募集を取得する。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT mr FROM MatchRequestEntity mr WHERE mr.id = :id")
    Optional<MatchRequestEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * 募集の検索（NGチーム除外付き）。
     */
    @Query("""
            SELECT mr FROM MatchRequestEntity mr
            WHERE mr.status = :status
              AND mr.teamId NOT IN :excludedTeamIds
              AND (:prefectureCode IS NULL OR mr.prefectureCode = :prefectureCode)
              AND (:cityCode IS NULL OR mr.cityCode = :cityCode)
              AND (:activityType IS NULL OR mr.activityType = :activityType)
              AND (:category IS NULL OR mr.category = :category)
              AND (:level IS NULL OR mr.level = :level)
              AND (:visibility IS NULL OR mr.visibility = :visibility)
              AND (mr.expiresAt IS NULL OR mr.expiresAt > :now)
            ORDER BY mr.createdAt DESC
            """)
    Page<MatchRequestEntity> searchRequests(
            @Param("status") MatchRequestStatus status,
            @Param("excludedTeamIds") List<Long> excludedTeamIds,
            @Param("prefectureCode") String prefectureCode,
            @Param("cityCode") String cityCode,
            @Param("activityType") com.mannschaft.app.matching.ActivityType activityType,
            @Param("category") com.mannschaft.app.matching.MatchCategory category,
            @Param("level") com.mannschaft.app.matching.MatchLevel level,
            @Param("visibility") com.mannschaft.app.matching.MatchVisibility visibility,
            @Param("now") LocalDateTime now,
            Pageable pageable);

    /**
     * キーワード検索（FULLTEXT）。
     */
    @Query(value = """
            SELECT mr.* FROM match_requests mr
            WHERE mr.status = :status
              AND mr.deleted_at IS NULL
              AND mr.team_id NOT IN (:excludedTeamIds)
              AND (mr.expires_at IS NULL OR mr.expires_at > :now)
              AND MATCH(mr.title, mr.activity_detail) AGAINST(:keyword IN BOOLEAN MODE)
            ORDER BY mr.created_at DESC
            """, nativeQuery = true)
    Page<MatchRequestEntity> searchByKeyword(
            @Param("status") String status,
            @Param("excludedTeamIds") List<Long> excludedTeamIds,
            @Param("keyword") String keyword,
            @Param("now") LocalDateTime now,
            Pageable pageable);

    /**
     * activity_detail のサジェスト用（使用頻度順）。
     */
    @Query(value = """
            SELECT mr.activity_detail AS activityDetail, COUNT(*) AS usageCount
            FROM match_requests mr
            WHERE mr.deleted_at IS NULL
              AND mr.activity_detail IS NOT NULL
              AND mr.activity_detail LIKE CONCAT(:query, '%')
              AND (:activityType IS NULL OR mr.activity_type = :activityType)
            GROUP BY mr.activity_detail
            ORDER BY usageCount DESC
            LIMIT 10
            """, nativeQuery = true)
    List<Object[]> findActivitySuggestions(
            @Param("query") String query,
            @Param("activityType") String activityType);

    /**
     * 期限切れバッチ用：OPEN で期限超過の募集を取得する。
     */
    @Query("SELECT mr FROM MatchRequestEntity mr WHERE mr.status = 'OPEN' AND mr.expiresAt IS NOT NULL AND mr.expiresAt < :now")
    List<MatchRequestEntity> findExpiredOpenRequests(@Param("now") LocalDateTime now);
}
