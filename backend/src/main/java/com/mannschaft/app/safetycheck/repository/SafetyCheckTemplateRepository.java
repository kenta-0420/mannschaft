package com.mannschaft.app.safetycheck.repository;

import com.mannschaft.app.safetycheck.SafetyCheckScopeType;
import com.mannschaft.app.safetycheck.entity.SafetyCheckTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 安否確認テンプレートリポジトリ。
 */
public interface SafetyCheckTemplateRepository extends JpaRepository<SafetyCheckTemplateEntity, Long> {

    /**
     * システムデフォルトテンプレートを表示順で取得する。
     */
    List<SafetyCheckTemplateEntity> findByIsSystemDefaultTrueOrderBySortOrderAsc();

    /**
     * スコープ別テンプレートとシステムデフォルトを合わせて取得する。
     */
    @Query("SELECT t FROM SafetyCheckTemplateEntity t WHERE t.isSystemDefault = true "
            + "OR (t.scopeType = :scopeType AND t.scopeId = :scopeId) ORDER BY t.sortOrder ASC")
    List<SafetyCheckTemplateEntity> findAvailableTemplates(
            @Param("scopeType") SafetyCheckScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * 全テンプレートを表示順で取得する（管理者用）。
     */
    List<SafetyCheckTemplateEntity> findAllByOrderBySortOrderAsc();
}
