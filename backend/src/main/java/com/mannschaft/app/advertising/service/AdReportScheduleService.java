package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.dto.CreateReportScheduleRequest;
import com.mannschaft.app.advertising.dto.ReportScheduleResponse;
import com.mannschaft.app.advertising.entity.AdReportScheduleEntity;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.repository.AdReportScheduleRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 広告レポートスケジュールサービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdReportScheduleService {

    private final AdReportScheduleRepository adReportScheduleRepository;
    private final AdvertiserAccountRepository advertiserAccountRepository;
    private final ObjectMapper objectMapper;

    /**
     * レポートスケジュール一覧を取得する。
     */
    public List<ReportScheduleResponse> findByOrganizationId(Long organizationId) {
        AdvertiserAccountEntity account = advertiserAccountRepository.findByOrganizationId(organizationId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_005));
        return adReportScheduleRepository.findByAdvertiserAccountId(account.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * レポートスケジュールを作成する。
     */
    @Transactional
    public ReportScheduleResponse create(Long organizationId, Long userId, CreateReportScheduleRequest request) {
        AdvertiserAccountEntity account = advertiserAccountRepository.findByOrganizationId(organizationId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_005));

        if (adReportScheduleRepository.countByAdvertiserAccountId(account.getId()) >= 3) {
            throw new BusinessException(AdvertisingErrorCode.AD_015);
        }

        // JSON serialization for recipients and includeCampaigns
        String recipientsJson = toJson(request.recipients());
        String campaignsJson = request.includeCampaigns() != null ? toJson(request.includeCampaigns()) : null;

        AdReportScheduleEntity entity = AdReportScheduleEntity.builder()
                .advertiserAccountId(account.getId())
                .frequency(request.frequency())
                .recipients(recipientsJson)
                .includeCampaigns(campaignsJson)
                .createdBy(userId)
                .build();

        AdReportScheduleEntity saved = adReportScheduleRepository.save(entity);
        return toResponse(saved);
    }

    /**
     * レポートスケジュールを削除する（論理削除）。
     */
    @Transactional
    public void delete(Long scheduleId, Long organizationId) {
        AdvertiserAccountEntity account = advertiserAccountRepository.findByOrganizationId(organizationId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_005));

        AdReportScheduleEntity schedule = adReportScheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_016));

        if (!schedule.getAdvertiserAccountId().equals(account.getId())) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        schedule.softDelete();
    }

    private ReportScheduleResponse toResponse(AdReportScheduleEntity entity) {
        List<String> recipients = parseJsonList(entity.getRecipients(), String.class);
        List<Long> campaigns = entity.getIncludeCampaigns() != null
                ? parseJsonList(entity.getIncludeCampaigns(), Long.class)
                : null;
        return new ReportScheduleResponse(
                entity.getId(),
                entity.getFrequency(),
                recipients,
                campaigns,
                entity.isEnabled(),
                entity.getLastSentAt()
        );
    }

    private <T> String toJson(List<T> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    private <T> List<T> parseJsonList(String json, Class<T> elementType) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON deserialization failed", e);
        }
    }
}
