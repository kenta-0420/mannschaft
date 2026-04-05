package com.mannschaft.app.analytics.repository;

import com.mannschaft.app.analytics.entity.AnalyticsAlertRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * アラートルールリポジトリ。
 */
public interface AnalyticsAlertRuleRepository extends JpaRepository<AnalyticsAlertRuleEntity, Long> {

    List<AnalyticsAlertRuleEntity> findByDeletedAtIsNull();

    List<AnalyticsAlertRuleEntity> findByEnabledTrueAndDeletedAtIsNull();

    Optional<AnalyticsAlertRuleEntity> findByIdAndDeletedAtIsNull(Long id);
}
