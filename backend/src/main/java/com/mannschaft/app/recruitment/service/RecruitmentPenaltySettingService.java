package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.recruitment.PenaltyApplyScope;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentPenaltySettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F03.11 Phase 5b: ペナルティ設定管理サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecruitmentPenaltySettingService {

    private final RecruitmentPenaltySettingRepository settingRepository;
    private final AccessControlService accessControlService;

    /** ペナルティ設定取得（なければデフォルト値で返す）。 */
    public RecruitmentPenaltySettingEntity getSetting(
            RecruitmentScopeType scopeType, Long scopeId, Long userId) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());
        return settingRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseGet(() -> {
                    RecruitmentPenaltySettingEntity defaultSetting =
                            RecruitmentPenaltySettingEntity.builder()
                                    .scopeType(scopeType).scopeId(scopeId).build();
                    return defaultSetting;
                });
    }

    /** ペナルティ設定を更新（なければ新規作成）。 */
    @Transactional
    public RecruitmentPenaltySettingEntity upsertSetting(
            RecruitmentScopeType scopeType, Long scopeId, Long userId,
            boolean enabled, int thresholdCount, int thresholdPeriodDays,
            int penaltyDurationDays, PenaltyApplyScope applyScope,
            boolean autoNoShowDetection, int disputeAllowedDays) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());

        RecruitmentPenaltySettingEntity setting = settingRepository
                .findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseGet(() -> RecruitmentPenaltySettingEntity.builder()
                        .scopeType(scopeType).scopeId(scopeId).build());

        setting.update(enabled, thresholdCount, thresholdPeriodDays,
                penaltyDurationDays, applyScope, autoNoShowDetection, disputeAllowedDays);

        return settingRepository.save(setting);
    }
}
