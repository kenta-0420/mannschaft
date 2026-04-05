package com.mannschaft.app.facility.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.facility.DayType;
import com.mannschaft.app.facility.FacilityErrorCode;
import com.mannschaft.app.facility.FacilityMapper;
import com.mannschaft.app.facility.dto.TimeRateEntry;
import com.mannschaft.app.facility.dto.TimeRateResponse;
import com.mannschaft.app.facility.dto.UpdateTimeRatesRequest;
import com.mannschaft.app.facility.dto.UpdateUsageRuleRequest;
import com.mannschaft.app.facility.dto.UsageRuleResponse;
import com.mannschaft.app.facility.entity.FacilityTimeRateEntity;
import com.mannschaft.app.facility.entity.FacilityUsageRuleEntity;
import com.mannschaft.app.facility.repository.FacilityTimeRateRepository;
import com.mannschaft.app.facility.repository.FacilityUsageRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 施設ルール・料金管理サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FacilityRuleService {

    private final FacilityUsageRuleRepository usageRuleRepository;
    private final FacilityTimeRateRepository timeRateRepository;
    private final FacilityMapper facilityMapper;
    private final ObjectMapper objectMapper;

    /**
     * 利用ルールを取得する。
     */
    public UsageRuleResponse getUsageRule(Long facilityId) {
        FacilityUsageRuleEntity entity = usageRuleRepository.findByFacilityId(facilityId)
                .orElseThrow(() -> new BusinessException(FacilityErrorCode.USAGE_RULE_NOT_FOUND));
        return facilityMapper.toUsageRuleResponse(entity);
    }

    /**
     * 利用ルールを更新する。
     */
    @Transactional
    public UsageRuleResponse updateUsageRule(Long facilityId, UpdateUsageRuleRequest request) {
        FacilityUsageRuleEntity entity = usageRuleRepository.findByFacilityId(facilityId)
                .orElseThrow(() -> new BusinessException(FacilityErrorCode.USAGE_RULE_NOT_FOUND));

        String daysOfWeekJson = serializeJson(request.getAvailableDaysOfWeek());
        String blackoutDatesJson = serializeJson(request.getBlackoutDates());

        entity.update(
                request.getMaxHoursPerBooking(),
                request.getMinHoursPerBooking(),
                request.getMaxBookingsPerMonthPerUser(),
                request.getMaxConsecutiveSlots(),
                request.getMinAdvanceHours(),
                request.getMaxAdvanceDays(),
                request.getMaxStayNights(),
                request.getCancellationDeadlineHours(),
                request.getAvailableTimeFrom(),
                request.getAvailableTimeTo(),
                daysOfWeekJson != null ? daysOfWeekJson : entity.getAvailableDaysOfWeek(),
                blackoutDatesJson,
                request.getNotes()
        );

        return facilityMapper.toUsageRuleResponse(entity);
    }

    /**
     * 時間帯別料金一覧を取得する。
     */
    public List<TimeRateResponse> getTimeRates(Long facilityId) {
        List<FacilityTimeRateEntity> entities = timeRateRepository
                .findByFacilityIdOrderByDayTypeAscTimeFromAsc(facilityId);
        return facilityMapper.toTimeRateResponseList(entities);
    }

    /**
     * 時間帯別料金を一括置換する。
     */
    @Transactional
    public List<TimeRateResponse> replaceTimeRates(Long facilityId, UpdateTimeRatesRequest request) {
        timeRateRepository.deleteByFacilityId(facilityId);

        List<FacilityTimeRateEntity> newEntities = new ArrayList<>();
        for (TimeRateEntry entry : request.getRates()) {
            FacilityTimeRateEntity entity = FacilityTimeRateEntity.builder()
                    .facilityId(facilityId)
                    .dayType(DayType.valueOf(entry.getDayType()))
                    .timeFrom(entry.getTimeFrom())
                    .timeTo(entry.getTimeTo())
                    .ratePerSlot(entry.getRatePerSlot())
                    .build();
            newEntities.add(entity);
        }

        List<FacilityTimeRateEntity> saved = timeRateRepository.saveAll(newEntities);
        return facilityMapper.toTimeRateResponseList(saved);
    }

    private String serializeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("JSONシリアライズに失敗: {}", e.getMessage());
            return null;
        }
    }
}
