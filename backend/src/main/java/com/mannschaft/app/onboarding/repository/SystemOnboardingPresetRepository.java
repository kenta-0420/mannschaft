package com.mannschaft.app.onboarding.repository;

import com.mannschaft.app.onboarding.OnboardingPresetCategory;
import com.mannschaft.app.onboarding.entity.SystemOnboardingPresetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * システムオンボーディングプリセットリポジトリ。
 */
public interface SystemOnboardingPresetRepository extends JpaRepository<SystemOnboardingPresetEntity, Long> {

    /**
     * 有効かつ論理削除されていないプリセットをsortOrder順に取得する。
     */
    List<SystemOnboardingPresetEntity> findByIsActiveTrueAndDeletedAtIsNullOrderBySortOrder();

    /**
     * カテゴリで有効かつ論理削除されていないプリセットを取得する。
     */
    List<SystemOnboardingPresetEntity> findByCategoryAndIsActiveTrueAndDeletedAtIsNull(OnboardingPresetCategory category);
}
