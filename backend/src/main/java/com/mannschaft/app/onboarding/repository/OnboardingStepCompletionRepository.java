package com.mannschaft.app.onboarding.repository;

import com.mannschaft.app.onboarding.entity.OnboardingStepCompletionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * オンボーディングステップ完了リポジトリ。
 */
public interface OnboardingStepCompletionRepository extends JpaRepository<OnboardingStepCompletionEntity, Long> {

    /**
     * 進捗IDで完了ステップを取得する。
     */
    List<OnboardingStepCompletionEntity> findByProgressId(Long progressId);

    /**
     * 進捗IDとステップIDで完了ステップを取得する。
     */
    Optional<OnboardingStepCompletionEntity> findByProgressIdAndStepId(Long progressId, Long stepId);

    /**
     * 進捗IDとステップIDで完了ステップの存在を確認する。
     */
    boolean existsByProgressIdAndStepId(Long progressId, Long stepId);

    /**
     * 進捗IDで完了ステップを一括削除する。
     */
    void deleteByProgressId(Long progressId);
}
