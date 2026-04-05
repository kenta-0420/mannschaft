package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 活動記録テンプレートリポジトリ。
 */
public interface ActivityTemplateRepository extends JpaRepository<ActivityTemplateEntity, Long> {

    List<ActivityTemplateEntity> findByScopeTypeAndScopeIdOrderBySortOrderAsc(
            ActivityScopeType scopeType, Long scopeId);

    long countByScopeTypeAndScopeId(ActivityScopeType scopeType, Long scopeId);
}
