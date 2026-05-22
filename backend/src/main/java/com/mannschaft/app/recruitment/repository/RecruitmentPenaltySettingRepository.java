package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * F03.11 Phase 5b: ペナルティ設定リポジトリ。
 */
public interface RecruitmentPenaltySettingRepository extends JpaRepository<RecruitmentPenaltySettingEntity, Long> {

    Optional<RecruitmentPenaltySettingEntity> findByScopeTypeAndScopeId(
            RecruitmentScopeType scopeType, Long scopeId);

    /**
     * 自動 NO_SHOW 検出が有効な設定をチャンク単位で取得する（バッチ処理用）。
     */
    Page<RecruitmentPenaltySettingEntity> findByAutoNoShowDetectionTrue(Pageable pageable);
}
