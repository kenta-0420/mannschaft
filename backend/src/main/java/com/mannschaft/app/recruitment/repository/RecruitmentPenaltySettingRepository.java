package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * F03.11 Phase 5b: ペナルティ設定リポジトリ。
 */
public interface RecruitmentPenaltySettingRepository extends JpaRepository<RecruitmentPenaltySettingEntity, Long> {

    Optional<RecruitmentPenaltySettingEntity> findByScopeTypeAndScopeId(
            RecruitmentScopeType scopeType, Long scopeId);
}
