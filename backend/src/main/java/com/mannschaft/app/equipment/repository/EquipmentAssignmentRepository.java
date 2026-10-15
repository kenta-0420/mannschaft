package com.mannschaft.app.equipment.repository;

import com.mannschaft.app.equipment.entity.EquipmentAssignmentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 備品貸出・返却履歴リポジトリ。
 */
public interface EquipmentAssignmentRepository extends JpaRepository<EquipmentAssignmentEntity, Long> {

    /**
     * 備品IDで貸出履歴をページング取得する。
     */
    Page<EquipmentAssignmentEntity> findByEquipmentItemIdOrderByAssignedAtDesc(Long equipmentItemId, Pageable pageable);

    /**
     * 備品IDで未返却の貸出一覧を取得する。
     */
    List<EquipmentAssignmentEntity> findByEquipmentItemIdAndReturnedAtIsNull(Long equipmentItemId);

    /**
     * ユーザーIDで未返却の貸出一覧を取得する（全スコープ横断）。
     */
    Page<EquipmentAssignmentEntity> findByAssignedToUserIdAndReturnedAtIsNullOrderByAssignedAtDesc(
            Long assignedToUserId, Pageable pageable);

    /**
     * 備品IDの未返却貸出数量合計を取得する。
     */
    @Query("SELECT COALESCE(SUM(a.quantity), 0) FROM EquipmentAssignmentEntity a WHERE a.equipmentItemId = :itemId AND a.returnedAt IS NULL")
    int sumActiveAssignedQuantity(@Param("itemId") Long equipmentItemId);

    /**
     * 返却遅延の貸出一覧を取得する（チーム備品）。
     */
    @Query("SELECT a FROM EquipmentAssignmentEntity a " +
            "JOIN EquipmentItemEntity e ON a.equipmentItemId = e.id " +
            "WHERE e.teamId = :teamId AND a.returnedAt IS NULL " +
            "AND a.expectedReturnAt IS NOT NULL AND a.expectedReturnAt < :today " +
            "ORDER BY a.expectedReturnAt ASC")
    Page<EquipmentAssignmentEntity> findOverdueByTeamId(
            @Param("teamId") Long teamId, @Param("today") LocalDate today, Pageable pageable);

    /**
     * 返却遅延の貸出一覧を取得する（組織備品）。
     */
    @Query("SELECT a FROM EquipmentAssignmentEntity a " +
            "JOIN EquipmentItemEntity e ON a.equipmentItemId = e.id " +
            "WHERE e.organizationId = :orgId AND a.returnedAt IS NULL " +
            "AND a.expectedReturnAt IS NOT NULL AND a.expectedReturnAt < :today " +
            "ORDER BY a.expectedReturnAt ASC")
    Page<EquipmentAssignmentEntity> findOverdueByOrganizationId(
            @Param("orgId") Long orgId, @Param("today") LocalDate today, Pageable pageable);

    /**
     * 備品IDに未返却の貸出があるかどうかを判定する。
     */
    boolean existsByEquipmentItemIdAndReturnedAtIsNull(Long equipmentItemId);

    /**
     * ランキング集計用: 過去90日の消費イベント数を備品IDごとに返す（opt-outチーム除外）。
     * 消費の判定: returnedAt IS NOT NULL（返却 = 消費として記録）かつ assignedAt が90日以内。
     *
     * @param since         集計開始日時（90日前）
     * @param optOutTeamIds opt-outチームのteamIdリスト（空の場合は全件）
     * @return [equipmentItemId, count] のリスト
     */
    @Query("""
            SELECT a.equipmentItemId, COUNT(a.id)
            FROM EquipmentAssignmentEntity a
            JOIN EquipmentItemEntity e ON a.equipmentItemId = e.id
            WHERE a.assignedAt >= :since
              AND a.returnedAt IS NOT NULL
              AND e.teamId IS NOT NULL
              AND (:#{#optOutTeamIds.size()} = 0 OR e.teamId NOT IN :optOutTeamIds)
            GROUP BY a.equipmentItemId
            """)
    List<Object[]> countConsumeEventsByItemId(
            @Param("since") LocalDateTime since,
            @Param("optOutTeamIds") List<Long> optOutTeamIds);
}
