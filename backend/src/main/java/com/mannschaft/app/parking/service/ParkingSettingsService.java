package com.mannschaft.app.parking.service;

import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.dto.ParkingSettingsResponse;
import com.mannschaft.app.parking.dto.UpdateSettingsRequest;
import com.mannschaft.app.parking.entity.ParkingSettingsEntity;
import com.mannschaft.app.parking.repository.ParkingSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 駐車場設定サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ParkingSettingsService {

    private final ParkingSettingsRepository settingsRepository;
    private final ParkingMapper parkingMapper;

    /**
     * 設定を取得する。存在しない場合はデフォルト値で作成する。
     */
    public ParkingSettingsResponse getSettings(String scopeType, Long scopeId) {
        ParkingSettingsEntity entity = settingsRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseGet(() -> ParkingSettingsEntity.builder()
                        .scopeType(scopeType)
                        .scopeId(scopeId)
                        .build());
        return parkingMapper.toSettingsResponse(entity);
    }

    /**
     * 設定を更新する。
     */
    @Transactional
    public ParkingSettingsResponse updateSettings(String scopeType, Long scopeId, UpdateSettingsRequest request) {
        ParkingSettingsEntity entity = settingsRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseGet(() -> ParkingSettingsEntity.builder()
                        .scopeType(scopeType)
                        .scopeId(scopeId)
                        .build());
        entity.update(request.getMaxSpacesPerUser(), request.getMaxVisitorReservationsPerDay(),
                request.getVisitorReservationMaxDaysAhead(), request.getVisitorReservationRequiresApproval());
        ParkingSettingsEntity saved = settingsRepository.save(entity);
        log.info("駐車場設定更新: scopeType={}, scopeId={}", scopeType, scopeId);
        return parkingMapper.toSettingsResponse(saved);
    }
}
