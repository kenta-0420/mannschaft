package com.mannschaft.app.disclosure.repository;

import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 重要事項説明書 様式テンプレートリポジトリ。
 * F09.14 設計書 §4 様式テンプレート API のクエリパターンに対応。
 */
public interface DisclosureFormTemplateRepository
        extends JpaRepository<DisclosureFormTemplateEntity, Long> {

    /**
     * ID で未削除のテンプレートを取得する。
     */
    Optional<DisclosureFormTemplateEntity> findByIdAndDeletedAtIsNull(Long id);

    /**
     * 様式コード × バージョンで一意取得する。
     */
    Optional<DisclosureFormTemplateEntity> findByCodeAndVersionAndDeletedAtIsNull(
            String code, String version);

    /**
     * システム提供のアクティブ様式一覧（is_system_template=TRUE）。
     */
    List<DisclosureFormTemplateEntity> findByIsSystemTemplateTrueAndIsActiveTrueAndDeletedAtIsNull();

    /**
     * 都道府県コードで絞り込んだアクティブ様式一覧（NULL は全国共通）。
     */
    @Query("""
            SELECT t FROM DisclosureFormTemplateEntity t
            WHERE (:prefectureCode IS NULL OR t.prefectureCode = :prefectureCode OR t.prefectureCode IS NULL)
              AND t.isActive = true
              AND t.deletedAt IS NULL
            ORDER BY t.isStandard DESC, t.prefectureCode ASC, t.effectiveFrom DESC
            """)
    List<DisclosureFormTemplateEntity> findActiveByPrefecture(
            @Param("prefectureCode") String prefectureCode);

    /**
     * 組織カスタムテンプレート一覧（ページング）。
     */
    Page<DisclosureFormTemplateEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNull(
            String scopeType, Long scopeId, Pageable pageable);

    /**
     * 組織カスタムテンプレート件数（10件上限チェック用）。
     */
    long countByScopeTypeAndScopeIdAndDeletedAtIsNull(String scopeType, Long scopeId);

    /**
     * 国交省標準書式取得（is_standard=TRUE のうち最新バージョン）。
     */
    @Query("""
            SELECT t FROM DisclosureFormTemplateEntity t
            WHERE t.isStandard = true
              AND t.isActive = true
              AND t.deletedAt IS NULL
              AND (t.effectiveFrom IS NULL OR t.effectiveFrom <= :asOf)
              AND (t.effectiveUntil IS NULL OR t.effectiveUntil >= :asOf)
            ORDER BY t.effectiveFrom DESC
            """)
    List<DisclosureFormTemplateEntity> findStandardActiveAsOf(@Param("asOf") LocalDate asOf);
}
