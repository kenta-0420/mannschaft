package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.FieldScope;
import com.mannschaft.app.activity.entity.ActivityCustomFieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 活動記録カスタムフィールド定義リポジトリ。
 */
public interface ActivityCustomFieldRepository extends JpaRepository<ActivityCustomFieldEntity, Long> {

    List<ActivityCustomFieldEntity> findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(Long teamId);

    List<ActivityCustomFieldEntity> findByOrganizationIdAndIsActiveTrueOrderBySortOrderAsc(Long organizationId);

    List<ActivityCustomFieldEntity> findByTeamIdAndScopeAndIsActiveTrueOrderBySortOrderAsc(Long teamId, FieldScope scope);

    List<ActivityCustomFieldEntity> findByOrganizationIdAndScopeAndIsActiveTrueOrderBySortOrderAsc(Long organizationId, FieldScope scope);
}
