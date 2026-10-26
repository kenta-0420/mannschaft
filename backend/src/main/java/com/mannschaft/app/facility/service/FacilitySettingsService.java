package com.mannschaft.app.facility.service;

import com.mannschaft.app.facility.FacilityMapper;
import com.mannschaft.app.facility.dto.FacilitySettingsResponse;
import com.mannschaft.app.facility.dto.UpdateSettingsRequest;
import com.mannschaft.app.facility.entity.FacilitySettingsEntity;
import com.mannschaft.app.facility.repository.FacilitySettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 施設予約設定サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FacilitySettingsService {

    private final FacilitySettingsRepository settingsRepository;
    private final FacilityMapper facilityMapper;

    /**
     * 設定を取得する。存在しない場合はデフォルト設定を作成して返す。
     */
    public FacilitySettingsResponse getSettings(String scopeType, Long scopeId) {
        FacilitySettingsEntity entity = settingsRepository
                .findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseGet(() -> createDefaultSettings(scopeType, scopeId));
        return facilityMapper.toSettingsResponse(entity);
    }

    /**
     * 設定を更新する。
     */
    @Transactional
    public FacilitySettingsResponse updateSettings(String scopeType, Long scopeId,
                                                    UpdateSettingsRequest request) {
        FacilitySettingsEntity entity = settingsRepository
                .findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseGet(() -> createDefaultSettings(scopeType, scopeId));

        entity.update(
                request.getRequiresApproval() != null ? request.getRequiresApproval() : entity.getRequiresApproval(),
                request.getMaxBookingsPerDayPerUser() != null ? request.getMaxBookingsPerDayPerUser() : entity.getMaxBookingsPerDayPerUser(),
                request.getAllowStripePayment() != null ? request.getAllowStripePayment() : entity.getAllowStripePayment(),
                request.getCancellationDeadlineHours() != null ? request.getCancellationDeadlineHours() : entity.getCancellationDeadlineHours(),
                request.getNoShowPenaltyEnabled() != null ? request.getNoShowPenaltyEnabled() : entity.getNoShowPenaltyEnabled(),
                request.getNoShowPenaltyThreshold() != null ? request.getNoShowPenaltyThreshold() : entity.getNoShowPenaltyThreshold(),
                request.getNoShowPenaltyDays() != null ? request.getNoShowPenaltyDays() : entity.getNoShowPenaltyDays()
        );

        return facilityMapper.toSettingsResponse(entity);
    }

    private FacilitySettingsEntity createDefaultSettings(String scopeType, Long scopeId) {
        FacilitySettingsEntity entity = FacilitySettingsEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .build();
        return settingsRepository.save(entity);
    }
}
