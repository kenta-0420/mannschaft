package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.recruitment.PenaltyLiftReason;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentPenaltySettingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentUserPenaltyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * F03.11 Phase 5b: ペナルティ発動・解除・再計算サービス。
 *
 * PESSIMISTIC_WRITE でアクティブペナルティ重複を防止する（三重防御の1層目はDB Lock）。
 * 通知は F04.9 実装後に統合予定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecruitmentPenaltyService {

    private final RecruitmentUserPenaltyRepository penaltyRepository;
    private final RecruitmentPenaltySettingRepository settingRepository;
    private final RecruitmentNoShowRecordRepository noShowRepository;
    private final AccessControlService accessControlService;

    // ===========================================
    // ペナルティ発動判定（NO_SHOW 確定後に呼ぶ）
    // ===========================================

    /**
     * 指定ユーザーの NO_SHOW 件数を確認し、閾値超過なら新規ペナルティを発動する。
     * PESSIMISTIC_WRITE で同時発動を防止。
     */
    @Transactional
    public Optional<RecruitmentUserPenaltyEntity> evaluateAndApplyPenalty(
            Long userId, RecruitmentScopeType scopeType, Long scopeId) {

        RecruitmentPenaltySettingEntity setting = settingRepository
                .findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElse(null);

        if (setting == null || !setting.isEnabled()) {
            return Optional.empty();
        }

        // 集計期間内の確定 NO_SHOW 件数
        LocalDateTime since = LocalDateTime.now().minusDays(setting.getThresholdPeriodDays());
        long noShowCount = noShowRepository.countConfirmedNoShows(userId, since);

        if (noShowCount < setting.getThresholdCount()) {
            return Optional.empty();
        }

        // PESSIMISTIC_WRITE でアクティブペナルティを確認（重複防止）
        Optional<RecruitmentUserPenaltyEntity> existing =
                penaltyRepository.findActivePenaltyForUpdate(userId, scopeType, scopeId, LocalDateTime.now());

        if (existing.isPresent()) {
            // 既にアクティブペナルティあり → 発動しない
            log.info("F03.11 Phase5b ペナルティ発動スキップ（既存あり）: userId={}", userId);
            return existing;
        }

        // 新規ペナルティ作成
        LocalDateTime now = LocalDateTime.now();
        RecruitmentUserPenaltyEntity penalty = RecruitmentUserPenaltyEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .triggeredBySettingId(setting.getId())
                .triggeredNoShowCount((int) noShowCount)
                .startedAt(now)
                .expiresAt(now.plusDays(setting.getPenaltyDurationDays()))
                .build();

        RecruitmentUserPenaltyEntity saved = penaltyRepository.save(penalty);

        // TODO: F04.9 実装後に RECRUITMENT_PENALTY_APPLIED (URGENT確認通知) を送信
        log.warn("F03.11 Phase5b ペナルティ発動: userId={}, scope={}/{}, noShowCount={}, expires={}",
                userId, scopeType, scopeId, noShowCount, saved.getExpiresAt());

        return Optional.of(saved);
    }

    // ===========================================
    // 手動解除
    // ===========================================

    /**
     * 管理者がペナルティを手動解除する。
     */
    @Transactional
    public RecruitmentUserPenaltyEntity liftPenalty(Long penaltyId, Long adminUserId) {
        RecruitmentUserPenaltyEntity penalty = penaltyRepository.findById(penaltyId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.PENALTY_NOT_FOUND));

        if (!penalty.isActive()) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        // 権限チェック
        accessControlService.checkAdminOrAbove(adminUserId, penalty.getScopeId(), penalty.getScopeType().name());

        penalty.lift(adminUserId, PenaltyLiftReason.ADMIN_MANUAL);
        RecruitmentUserPenaltyEntity saved = penaltyRepository.save(penalty);

        // TODO: F04.9 実装後に RECRUITMENT_PENALTY_LIFTED 通知を送信
        log.info("F03.11 Phase5b ペナルティ手動解除: penaltyId={}, liftedBy={}", penaltyId, adminUserId);

        return saved;
    }

    // ===========================================
    // 照会
    // ===========================================

    /** スコープのアクティブペナルティ一覧（管理者用）。 */
    public List<RecruitmentUserPenaltyEntity> getActivePenalties(
            RecruitmentScopeType scopeType, Long scopeId, Long adminUserId) {
        accessControlService.checkAdminOrAbove(adminUserId, scopeId, scopeType.name());
        return penaltyRepository.findActivePenaltiesByScope(scopeType, scopeId, LocalDateTime.now());
    }

    /** ユーザー自身のペナルティ履歴。 */
    public List<RecruitmentUserPenaltyEntity> getMyPenalties(Long userId) {
        return penaltyRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }
}
