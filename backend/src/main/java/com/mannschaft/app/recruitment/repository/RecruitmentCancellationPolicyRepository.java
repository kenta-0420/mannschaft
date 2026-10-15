package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * F03.11 募集型予約: キャンセルポリシーリポジトリ (Phase 5a)。
 */
public interface RecruitmentCancellationPolicyRepository extends JpaRepository<RecruitmentCancellationPolicyEntity, Long> {

    List<RecruitmentCancellationPolicyEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            RecruitmentScopeType scopeType, Long scopeId);

    Optional<RecruitmentCancellationPolicyEntity> findByIdAndScopeTypeAndScopeId(
            Long id, RecruitmentScopeType scopeType, Long scopeId);
}
