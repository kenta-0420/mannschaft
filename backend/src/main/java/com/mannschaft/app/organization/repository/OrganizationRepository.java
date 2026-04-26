package com.mannschaft.app.organization.repository;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 組織リポジトリ。
 */
public interface OrganizationRepository extends JpaRepository<OrganizationEntity, Long> {

    List<OrganizationEntity> findByVisibility(OrganizationEntity.Visibility visibility);

    boolean existsByName(String name);

    @Query("SELECT o FROM OrganizationEntity o WHERE o.name LIKE %:keyword% OR o.nameKana LIKE %:keyword%")
    Page<OrganizationEntity> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 論理削除済みを含めてIDで検索する（restore用）。
     */
    @Query(value = "SELECT * FROM organizations WHERE id = :id", nativeQuery = true)
    Optional<OrganizationEntity> findByIdIncludingDeleted(@Param("id") Long id);

    /**
     * 論理削除済み組織を復元する。deleted_at を NULL に戻す。
     * @return 更新件数（0 = 対象なし or 削除済みでない）
     */
    @Modifying
    @Query(value = "UPDATE organizations SET deleted_at = NULL WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    int restoreById(@Param("id") Long id);

    /**
     * 論理削除済みを含めた存在確認（restore前の 404 判定用）。
     */
    @Query(value = "SELECT COUNT(*) FROM organizations WHERE id = :id", nativeQuery = true)
    long countByIdIncludingDeleted(@Param("id") Long id);

    // ========================================
    // F01.2 階層表示API用
    // ========================================

    /**
     * 親組織IDのみを軽量に取得する（祖先チェーン構築用）。
     *
     * <p>{@code SQLRestriction("deleted_at IS NULL")} により論理削除済み組織はヒットしない。</p>
     *
     * @param id 対象組織ID
     * @return 親組織ID。トップレベル組織や対象不在の場合は空。
     */
    @Query("SELECT o.parentOrganizationId FROM OrganizationEntity o WHERE o.id = :id")
    Optional<Long> findParentOrganizationIdById(@Param("id") Long id);

    /**
     * 直近の子組織を取得する（{@code parent_organization_id = :parentId} かつ未削除）。
     */
    List<OrganizationEntity> findByParentOrganizationIdAndDeletedAtIsNull(Long parentId, Pageable pageable);

    /**
     * 複数IDを一括取得（祖先チェーンを1回の SQL でまとめて取得する用途）。
     */
    List<OrganizationEntity> findByIdInAndDeletedAtIsNull(Collection<Long> ids);
}
