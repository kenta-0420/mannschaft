package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * F03.11 Phase 5b: ペナルティ設定リポジトリ。
 */
public interface RecruitmentPenaltySettingRepository extends JpaRepository<RecruitmentPenaltySettingEntity, Long> {

    Optional<RecruitmentPenaltySettingEntity> findByScopeTypeAndScopeId(
            RecruitmentScopeType scopeType, Long scopeId);

    /** 発動判定を同一設定内で直列化する。既存ペナルティ行がない場合にもロックできる。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM RecruitmentPenaltySettingEntity s WHERE s.scopeType = :scopeType AND s.scopeId = :scopeId")
    Optional<RecruitmentPenaltySettingEntity> findByScopeForUpdate(
            @Param("scopeType") RecruitmentScopeType scopeType, @Param("scopeId") Long scopeId);

    /**
     * 自動 NO_SHOW 検出が有効な設定をチャンク単位で取得する（バッチ処理用）。
     */
    Page<RecruitmentPenaltySettingEntity> findByAutoNoShowDetectionTrue(Pageable pageable);
}
