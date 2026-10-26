package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.entity.RecruitmentCancellationPolicyTierEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * F03.11 募集型予約: キャンセルポリシー段階リポジトリ (Phase 5a)。
 */
public interface RecruitmentCancellationPolicyTierRepository extends JpaRepository<RecruitmentCancellationPolicyTierEntity, Long> {

    /**
     * §5.9 計算用: 指定 policy の有効な段階を tier_order 昇順で取得。
     */
    List<RecruitmentCancellationPolicyTierEntity> findByPolicyIdOrderByTierOrderAsc(Long policyId);

    /**
     * 4段階上限チェック用。
     */
    long countByPolicyId(Long policyId);

    void deleteByPolicyId(Long policyId);
}
