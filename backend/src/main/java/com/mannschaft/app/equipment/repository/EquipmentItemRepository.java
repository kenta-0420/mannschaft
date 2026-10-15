package com.mannschaft.app.equipment.repository;

import com.mannschaft.app.equipment.EquipmentStatus;
import com.mannschaft.app.equipment.entity.EquipmentItemEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 備品マスターリポジトリ。
 */
public interface EquipmentItemRepository extends JpaRepository<EquipmentItemEntity, Long> {

    /**
     * チームIDで備品一覧をページング取得する。
     */
    Page<EquipmentItemEntity> findByTeamId(Long teamId, Pageable pageable);

    /**
     * 組織IDで備品一覧をページング取得する。
     */
    Page<EquipmentItemEntity> findByOrganizationId(Long organizationId, Pageable pageable);

    /**
     * チームIDとカテゴリで備品一覧をページング取得する。
     */
    Page<EquipmentItemEntity> findByTeamIdAndCategory(Long teamId, String category, Pageable pageable);

    /**
     * 組織IDとカテゴリで備品一覧をページング取得する。
     */
    Page<EquipmentItemEntity> findByOrganizationIdAndCategory(Long organizationId, String category, Pageable pageable);

    /**
     * チームIDとステータスで備品一覧をページング取得する。
     */
    Page<EquipmentItemEntity> findByTeamIdAndStatus(Long teamId, EquipmentStatus status, Pageable pageable);

    /**
     * 組織IDとステータスで備品一覧をページング取得する。
     */
    Page<EquipmentItemEntity> findByOrganizationIdAndStatus(Long organizationId, EquipmentStatus status, Pageable pageable);

    /**
     * チームIDと名前部分一致で備品一覧をページング取得する。
     */
    Page<EquipmentItemEntity> findByTeamIdAndNameContaining(Long teamId, String nameLike, Pageable pageable);

    /**
     * 組織IDと名前部分一致で備品一覧をページング取得する。
     */
    Page<EquipmentItemEntity> findByOrganizationIdAndNameContaining(Long organizationId, String nameLike, Pageable pageable);

    /**
     * チームIDとIDで備品を取得する。
     */
    Optional<EquipmentItemEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * 組織IDとIDで備品を取得する。
     */
    Optional<EquipmentItemEntity> findByIdAndOrganizationId(Long id, Long organizationId);

    /**
     * チーム内のカテゴリ一覧をDISTINCTで取得する。
     */
    @Query("SELECT DISTINCT e.category FROM EquipmentItemEntity e WHERE e.teamId = :teamId AND e.category IS NOT NULL ORDER BY e.category ASC")
    List<String> findDistinctCategoriesByTeamId(@Param("teamId") Long teamId);

    /**
     * 組織内のカテゴリ一覧をDISTINCTで取得する。
     */
    @Query("SELECT DISTINCT e.category FROM EquipmentItemEntity e WHERE e.organizationId = :orgId AND e.category IS NOT NULL ORDER BY e.category ASC")
    List<String> findDistinctCategoriesByOrganizationId(@Param("orgId") Long orgId);

    /**
     * QRコードで備品を取得する。
     */
    Optional<EquipmentItemEntity> findByQrCode(String qrCode);

    /**
     * チームIDで備品を取得する（QRコード一覧用、ページングなし）。
     */
    List<EquipmentItemEntity> findByTeamIdAndStatusNot(Long teamId, EquipmentStatus status);

    /**
     * 組織IDで備品を取得する（QRコード一覧用、ページングなし）。
     */
    List<EquipmentItemEntity> findByOrganizationIdAndStatusNot(Long organizationId, EquipmentStatus status);

    /**
     * チームIDとカテゴリで備品を取得する（QRコード一覧用）。
     */
    List<EquipmentItemEntity> findByTeamIdAndCategoryAndStatusNot(Long teamId, String category, EquipmentStatus status);

    /**
     * 組織IDとカテゴリで備品を取得する（QRコード一覧用）。
     */
    List<EquipmentItemEntity> findByOrganizationIdAndCategoryAndStatusNot(Long organizationId, String category, EquipmentStatus status);

    /**
     * チームIDと名前部分一致で備品を取得する（QRコード一覧用）。
     */
    List<EquipmentItemEntity> findByTeamIdAndNameContainingAndStatusNot(Long teamId, String nameLike, EquipmentStatus status);

    /**
     * 組織IDと名前部分一致で備品を取得する（QRコード一覧用）。
     */
    List<EquipmentItemEntity> findByOrganizationIdAndNameContainingAndStatusNot(Long organizationId, String nameLike, EquipmentStatus status);

    /**
     * ランキング集計用: チーム所属かつ論理削除されていない全備品を返す（opt-outチーム除外）。
     *
     * @param optOutTeamIds opt-outチームIDリスト（空の場合は全件対象）
     * @return 備品エンティティリスト
     */
    @Query("""
            SELECT e FROM EquipmentItemEntity e
            WHERE e.teamId IS NOT NULL
              AND (:#{#optOutTeamIds.size()} = 0 OR e.teamId NOT IN :optOutTeamIds)
            """)
    List<EquipmentItemEntity> findAllForRankingBatch(@Param("optOutTeamIds") List<Long> optOutTeamIds);
}
