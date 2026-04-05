package com.mannschaft.app.onboarding.repository;

import com.mannschaft.app.onboarding.OnboardingTemplateStatus;
import com.mannschaft.app.onboarding.entity.OnboardingTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * オンボーディングテンプレートリポジトリ。
 */
public interface OnboardingTemplateRepository extends JpaRepository<OnboardingTemplateEntity, Long> {

    /**
     * スコープとステータスでテンプレートを取得する。
     */
    List<OnboardingTemplateEntity> findByScopeTypeAndScopeIdAndStatus(String scopeType, Long scopeId, OnboardingTemplateStatus status);

    /**
     * スコープで論理削除されていないテンプレートを取得する。
     */
    List<OnboardingTemplateEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNull(String scopeType, Long scopeId);

    /**
     * スコープで論理削除されていないテンプレート数をカウントする。
     */
    long countByScopeTypeAndScopeIdAndDeletedAtIsNull(String scopeType, Long scopeId);
}
