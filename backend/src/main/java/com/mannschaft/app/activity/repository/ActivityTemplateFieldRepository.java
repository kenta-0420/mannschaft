package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.FieldType;
import com.mannschaft.app.activity.entity.ActivityTemplateFieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 活動テンプレートフィールド定義リポジトリ。
 */
public interface ActivityTemplateFieldRepository extends JpaRepository<ActivityTemplateFieldEntity, Long> {

    List<ActivityTemplateFieldEntity> findByTemplateIdOrderBySortOrderAsc(Long templateId);

    Optional<ActivityTemplateFieldEntity> findByTemplateIdAndFieldKey(Long templateId, String fieldKey);

    void deleteByTemplateId(Long templateId);

    long countByTemplateId(Long templateId);

    /**
     * 指定チームの活動テンプレートに属する、指定型のフィールド一覧を取得する。
     * パフォーマンス指標との連携候補取得に使用。
     */
    @Query("""
            SELECT f FROM ActivityTemplateFieldEntity f
            JOIN ActivityTemplateEntity t ON f.templateId = t.id
            WHERE t.scopeType = com.mannschaft.app.activity.ActivityScopeType.TEAM
              AND t.scopeId = :teamId
              AND t.deletedAt IS NULL
              AND f.fieldType = :fieldType
            ORDER BY t.name, f.sortOrder
            """)
    List<ActivityTemplateFieldEntity> findByTeamIdAndFieldType(
            @Param("teamId") Long teamId, @Param("fieldType") FieldType fieldType);
}
