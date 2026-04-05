package com.mannschaft.app.onboarding.repository;

import com.mannschaft.app.onboarding.entity.OnboardingTemplateStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * オンボーディングテンプレートステップリポジトリ。
 */
public interface OnboardingTemplateStepRepository extends JpaRepository<OnboardingTemplateStepEntity, Long> {

    /**
     * テンプレートIDでステップをsortOrder順に取得する。
     */
    List<OnboardingTemplateStepEntity> findByTemplateIdOrderBySortOrder(Long templateId);

    /**
     * テンプレートIDでステップ数をカウントする。
     */
    long countByTemplateId(Long templateId);

    /**
     * テンプレートIDでステップを一括削除する。
     */
    void deleteByTemplateId(Long templateId);

    /**
     * テンプレートID・ステップ種別・参照IDでステップを取得する。
     */
    List<OnboardingTemplateStepEntity> findByTemplateIdAndStepTypeAndReferenceId(Long templateId, com.mannschaft.app.onboarding.OnboardingStepType stepType, Long referenceId);

    /**
     * テンプレートID・ステップ種別でステップを取得する。
     */
    List<OnboardingTemplateStepEntity> findByTemplateIdAndStepType(Long templateId, com.mannschaft.app.onboarding.OnboardingStepType stepType);
}
