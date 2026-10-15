package com.mannschaft.app.onboarding.repository;

import com.mannschaft.app.onboarding.OnboardingProgressStatus;
import com.mannschaft.app.onboarding.entity.OnboardingProgressEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * オンボーディング進捗リポジトリ。
 */
public interface OnboardingProgressRepository extends JpaRepository<OnboardingProgressEntity, Long> {

    /**
     * テンプレートIDとユーザーIDで進捗を取得する。
     */
    Optional<OnboardingProgressEntity> findByTemplateIdAndUserId(Long templateId, Long userId);

    /**
     * スコープとステータスで進捗をページング取得する。
     */
    Page<OnboardingProgressEntity> findByScopeTypeAndScopeIdAndStatus(String scopeType, Long scopeId, OnboardingProgressStatus status, Pageable pageable);

    /**
     * ユーザーIDとステータスで進捗を取得する。
     */
    List<OnboardingProgressEntity> findByUserIdAndStatus(Long userId, OnboardingProgressStatus status);

    /**
     * ステータスと期限日時で期限超過の進捗を取得する。
     */
    List<OnboardingProgressEntity> findByStatusAndDeadlineAtBefore(OnboardingProgressStatus status, LocalDateTime deadline);

    /**
     * ステータスと期限日時の範囲で進捗を取得する。
     */
    List<OnboardingProgressEntity> findByStatusAndDeadlineAtBetween(OnboardingProgressStatus status, LocalDateTime from, LocalDateTime to);

    /**
     * ユーザーIDで進捗を取得する。
     */
    List<OnboardingProgressEntity> findByUserId(Long userId);

    /**
     * スコープで進捗をページング取得する（ステータス指定なし）。
     */
    Page<OnboardingProgressEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープとステータスで進捗をリスト取得する。
     */
    List<OnboardingProgressEntity> findByScopeTypeAndScopeIdAndStatus(String scopeType, Long scopeId, OnboardingProgressStatus status);

    /**
     * テンプレートIDとステータスで進捗数をカウントする。
     */
    long countByTemplateIdAndStatus(Long templateId, OnboardingProgressStatus status);

    /**
     * ユーザーID・スコープ・ステータスで進捗を取得する。
     */
    List<OnboardingProgressEntity> findByUserIdAndScopeTypeAndScopeIdAndStatus(Long userId, String scopeType, Long scopeId, OnboardingProgressStatus status);
}
