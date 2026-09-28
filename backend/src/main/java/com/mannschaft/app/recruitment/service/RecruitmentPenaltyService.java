package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.recruitment.PenaltyApplyScope;
import com.mannschaft.app.recruitment.PenaltyLiftReason;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import com.mannschaft.app.recruitment.event.RecruitmentPenaltyAppliedNotificationEvent;
import com.mannschaft.app.recruitment.event.RecruitmentPenaltyLiftedNotificationEvent;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentPenaltySettingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentUserPenaltyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * F03.11 Phase 5b: ペナルティ発動・解除・再計算サービス。
 *
 * ユーザー行と発動元設定行の PESSIMISTIC_WRITE、および DB 一意制約で二重発動を防ぐ。
 * 新規発動イベントはコミット後の確認通知配送に引き渡す。
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
    private final ApplicationEventPublisher eventPublisher;

    // ===========================================
    // ペナルティ発動判定（NO_SHOW 確定後に呼ぶ）
    // ===========================================

    /**
     * 指定ユーザーの NO_SHOW 件数を確認し、閾値超過なら新規ペナルティを発動する。
     * ユーザーと設定の行ロックで同時発動を直列化する。
     */
    @Transactional
    public Optional<RecruitmentUserPenaltyEntity> evaluateAndApplyPenalty(
            Long userId, RecruitmentScopeType scopeType, Long scopeId) {

        if (scopeType != RecruitmentScopeType.TEAM && scopeType != RecruitmentScopeType.ORGANIZATION) {
            return Optional.empty();
        }

        // GLOBAL は複数スコープの設定から同時発動し得るため、設定行より先にユーザー行をロックする。
        penaltyRepository.lockUserForPenalty(userId);

        RecruitmentPenaltySettingEntity setting = settingRepository
                .findByScopeForUpdate(scopeType, scopeId)
                .orElse(null);

        if (setting == null || !setting.isEnabled()) {
            return Optional.empty();
        }

        // 集計期間内の確定 NO_SHOW 件数
        boolean allScopes = setting.getApplyScope() == PenaltyApplyScope.ALL_SCOPES;
        long noShowCount = noShowRepository.countConfirmedNoShowsForPenalty(
                userId, setting.getThresholdPeriodDays(), allScopes, scopeType.name(), scopeId);

        if (noShowCount < setting.getThresholdCount()) {
            return Optional.empty();
        }

        RecruitmentScopeType effectiveScopeType = allScopes ? RecruitmentScopeType.GLOBAL : scopeType;
        Long effectiveScopeId = allScopes ? null : scopeId;
        LocalDateTime now = LocalDateTime.now(UserZoneLocalDateTimeParser.SERVER_ZONE);

        // 未解除行をロックする。期限切れ行は UNIQUE のスロットを解放してから新規作成する。
        Optional<RecruitmentUserPenaltyEntity> existing = penaltyRepository
                .findUnliftedPenaltyForUpdate(userId, effectiveScopeType, effectiveScopeId);

        if (existing.isPresent()) {
            if (existing.get().getExpiresAt().isAfter(now)) {
                log.info("F03.11 Phase5b ペナルティ発動スキップ（既存あり）: userId={}", userId);
                return existing;
            }
            RecruitmentUserPenaltyEntity expired = existing.get();
            expired.lift(null, PenaltyLiftReason.AUTO_EXPIRED);
            penaltyRepository.saveAndFlush(expired);
            RecruitmentPenaltySettingEntity previousSetting = settingRepository
                    .findById(expired.getTriggeredBySettingId()).orElse(null);
            if (previousSetting != null) {
                eventPublisher.publishEvent(new RecruitmentPenaltyLiftedNotificationEvent(
                        expired.getId(), userId, previousSetting.getScopeType(), previousSetting.getScopeId(),
                        PenaltyLiftReason.AUTO_EXPIRED));
            }
        }

        // 新規ペナルティ作成
        RecruitmentUserPenaltyEntity penalty = RecruitmentUserPenaltyEntity.builder()
                .userId(userId)
                .scopeType(effectiveScopeType)
                .scopeId(effectiveScopeId)
                .triggeredBySettingId(setting.getId())
                .triggeredNoShowCount((int) noShowCount)
                .startedAt(now)
                .expiresAt(now.plusDays(setting.getPenaltyDurationDays()))
                .build();

        RecruitmentUserPenaltyEntity saved = penaltyRepository.saveAndFlush(penalty);

        eventPublisher.publishEvent(new RecruitmentPenaltyAppliedNotificationEvent(
                saved.getId(), userId, scopeType, scopeId,
                saved.getExpiresAt().atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant()));
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
        RecruitmentPenaltySettingEntity sourceSetting = settingRepository
                .findById(penalty.getTriggeredBySettingId())
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.PENALTY_SETTING_NOT_FOUND));
        accessControlService.checkAdminOrAbove(
                adminUserId, sourceSetting.getScopeId(), sourceSetting.getScopeType().name());

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
        return penaltyRepository.findActivePenaltiesByScope(
                scopeType, scopeId, LocalDateTime.now(UserZoneLocalDateTimeParser.SERVER_ZONE));
    }

    /** ユーザー自身のペナルティ履歴。 */
    public List<RecruitmentUserPenaltyEntity> getMyPenalties(Long userId) {
        return penaltyRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }
}
