package com.mannschaft.app.template.repository;

import com.mannschaft.app.template.FeatureDisplayScope;
import com.mannschaft.app.template.entity.ScopeFeatureDisplayPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** 多態スコープの表示設定をscope単位で一括取得する。クロスドメインFKは持たない。 */
public interface ScopeFeatureDisplayPolicyRepository extends JpaRepository<ScopeFeatureDisplayPolicyEntity, UUID> {
    List<ScopeFeatureDisplayPolicyEntity> findByScopeTypeAndScopeId(FeatureDisplayScope scopeType, Long scopeId);

    /** 初回の並行保存もDBのuniqueキーで直列化する。既存ID/作成日時は維持する。 */
    @Modifying
    @Query(value = """
            INSERT INTO scope_feature_display_policies
                (id, scope_type, scope_id, feature_key, member_visible, supporter_visible, updated_by, created_at, updated_at)
            VALUES (:id, :scopeType, :scopeId, :featureKey, :memberVisible, :supporterVisible, :actor, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE member_visible = VALUES(member_visible), supporter_visible = VALUES(supporter_visible),
                updated_by = VALUES(updated_by), updated_at = VALUES(updated_at)
            """, nativeQuery = true)
    void upsert(@Param("id") UUID id, @Param("scopeType") String scopeType, @Param("scopeId") Long scopeId,
                @Param("featureKey") String featureKey, @Param("memberVisible") boolean memberVisible,
                @Param("supporterVisible") boolean supporterVisible, @Param("actor") Long actor);
}
