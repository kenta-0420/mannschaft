package com.mannschaft.app.property.repository;

import com.mannschaft.app.property.VendorCategory;
import com.mannschaft.app.property.entity.VendorEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 業者マスタリポジトリ。
 * F09.13 設計書 §4 業者マスタ API のクエリパターンに対応。
 */
public interface VendorRepository
        extends JpaRepository<VendorEntity, Long>, JpaSpecificationExecutor<VendorEntity> {

    /**
     * ID で未削除の業者を取得する。
     */
    Optional<VendorEntity> findByIdAndDeletedAtIsNull(Long id);

    /**
     * スコープに紐づく未削除業者を取得する（有効・無効問わず）。
     */
    List<VendorEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByNameKanaAsc(
            String scopeType, Long scopeId);

    /**
     * スコープに紐づく有効業者をページング取得する。
     */
    Page<VendorEntity> findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(
            String scopeType, Long scopeId, Pageable pageable);

    /**
     * カテゴリで有効業者をフィルタする。
     */
    List<VendorEntity> findByScopeTypeAndScopeIdAndCategoryAndIsActiveTrueAndDeletedAtIsNull(
            String scopeType, Long scopeId, VendorCategory category);

    /**
     * 業者名重複チェック（同一スコープ・未削除）。
     */
    Optional<VendorEntity> findByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
            String scopeType, Long scopeId, String name);

    /**
     * オートコンプリート検索。name または nameKana に q を含む有効業者を取得する。
     */
    @Query("""
            SELECT v FROM VendorEntity v
            WHERE v.scopeType = :scopeType
              AND v.scopeId = :scopeId
              AND v.isActive = true
              AND v.deletedAt IS NULL
              AND (LOWER(v.name) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(v.nameKana) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY v.nameKana ASC
            """)
    List<VendorEntity> searchByKeyword(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("q") String q,
            Pageable pageable);
}
