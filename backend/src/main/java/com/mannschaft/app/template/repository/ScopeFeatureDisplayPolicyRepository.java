package com.mannschaft.app.template.repository;

import com.mannschaft.app.template.FeatureDisplayScope;
import com.mannschaft.app.template.entity.ScopeFeatureDisplayPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 多態スコープの表示設定をscope単位で一括取得する。クロスドメインFKは持たない。 */
public interface ScopeFeatureDisplayPolicyRepository extends JpaRepository<ScopeFeatureDisplayPolicyEntity, UUID> {
    List<ScopeFeatureDisplayPolicyEntity> findByScopeTypeAndScopeId(FeatureDisplayScope scopeType, Long scopeId);
}
