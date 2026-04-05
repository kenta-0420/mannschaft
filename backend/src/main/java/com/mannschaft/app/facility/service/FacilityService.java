package com.mannschaft.app.facility.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.facility.FacilityErrorCode;
import com.mannschaft.app.facility.FacilityMapper;
import com.mannschaft.app.facility.FacilityType;
import com.mannschaft.app.facility.dto.AvailabilityResponse;
import com.mannschaft.app.facility.dto.CreateFacilityRequest;
import com.mannschaft.app.facility.dto.FacilityDetailResponse;
import com.mannschaft.app.facility.dto.FacilityResponse;
import com.mannschaft.app.facility.dto.UpdateFacilityRequest;
import com.mannschaft.app.facility.entity.FacilityUsageRuleEntity;
import com.mannschaft.app.facility.entity.SharedFacilityEntity;
import com.mannschaft.app.facility.repository.FacilityUsageRuleRepository;
import com.mannschaft.app.facility.repository.SharedFacilityRepository;
import com.mannschaft.app.facility.BookingStatus;
import com.mannschaft.app.facility.entity.FacilityBookingEntity;
import com.mannschaft.app.facility.repository.FacilityBookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 施設管理サービス。施設CRUD・空き状況を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FacilityService {

    private static final int SLOT_MINUTES = 30;

    private final SharedFacilityRepository facilityRepository;
    private final FacilityUsageRuleRepository usageRuleRepository;
    private final FacilityBookingRepository bookingRepository;
    private final FacilityMapper facilityMapper;
    private final ObjectMapper objectMapper;

    /**
     * 施設一覧をページング取得する。
     */
    public Page<FacilityResponse> listFacilities(String scopeType, Long scopeId, Pageable pageable) {
        Page<SharedFacilityEntity> page = facilityRepository
                .findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(scopeType, scopeId, pageable);
        return page.map(facilityMapper::toFacilityResponse);
    }

    /**
     * 施設詳細を取得する。
     */
    public FacilityDetailResponse getFacility(String scopeType, Long scopeId, Long facilityId) {
        SharedFacilityEntity entity = findFacilityOrThrow(scopeType, scopeId, facilityId);
        FacilityDetailResponse response = facilityMapper.toFacilityDetailResponse(entity);
        return response;
    }

    /**
     * 施設を作成する。
     */
    @Transactional
    public FacilityResponse createFacility(String scopeType, Long scopeId, Long userId,
                                            CreateFacilityRequest request) {
        if (facilityRepository.existsByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
                scopeType, scopeId, request.getName())) {
            throw new BusinessException(FacilityErrorCode.FACILITY_NAME_DUPLICATE);
        }

        String imageUrlsJson = serializeImageUrls(request.getImageUrls());

        SharedFacilityEntity entity = SharedFacilityEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.getName())
                .facilityType(FacilityType.valueOf(request.getFacilityType()))
                .facilityTypeLabel(request.getFacilityTypeLabel())
                .capacity(request.getCapacity())
                .floor(request.getFloor())
                .locationDetail(request.getLocationDetail())
                .description(request.getDescription())
                .imageUrls(imageUrlsJson)
                .ratePerSlot(request.getRatePerSlot())
                .ratePerNight(request.getRatePerNight())
                .checkInTime(request.getCheckInTime())
                .checkOutTime(request.getCheckOutTime())
                .cleaningBufferMinutes(request.getCleaningBufferMinutes() != null ? request.getCleaningBufferMinutes() : 0)
                .autoApprove(request.getAutoApprove() != null ? request.getAutoApprove() : false)
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 0)
                .createdBy(userId)
                .build();

        SharedFacilityEntity saved = facilityRepository.save(entity);

        // デフォルト利用ルールを自動作成
        FacilityUsageRuleEntity rule = FacilityUsageRuleEntity.builder()
                .facilityId(saved.getId())
                .build();
        usageRuleRepository.save(rule);

        return facilityMapper.toFacilityResponse(saved);
    }

    /**
     * 施設を一括作成する。
     */
    @Transactional
    public List<FacilityResponse> bulkCreateFacilities(String scopeType, Long scopeId, Long userId,
                                                        List<CreateFacilityRequest> requests) {
        List<FacilityResponse> results = new ArrayList<>();
        for (CreateFacilityRequest request : requests) {
            results.add(createFacility(scopeType, scopeId, userId, request));
        }
        return results;
    }

    /**
     * 施設を更新する。
     */
    @Transactional
    public FacilityDetailResponse updateFacility(String scopeType, Long scopeId, Long facilityId,
                                                  UpdateFacilityRequest request) {
        SharedFacilityEntity entity = findFacilityOrThrow(scopeType, scopeId, facilityId);

        String imageUrlsJson = serializeImageUrls(request.getImageUrls());

        entity.update(
                request.getName(),
                FacilityType.valueOf(request.getFacilityType()),
                request.getFacilityTypeLabel(),
                request.getCapacity(),
                request.getFloor(),
                request.getLocationDetail(),
                request.getDescription(),
                imageUrlsJson,
                request.getRatePerSlot(),
                request.getRatePerNight(),
                request.getCheckInTime(),
                request.getCheckOutTime(),
                request.getCleaningBufferMinutes() != null ? request.getCleaningBufferMinutes() : 0,
                request.getAutoApprove() != null ? request.getAutoApprove() : false,
                request.getIsActive() != null ? request.getIsActive() : true,
                request.getDisplayOrder() != null ? request.getDisplayOrder() : 0
        );

        return facilityMapper.toFacilityDetailResponse(entity);
    }

    /**
     * 施設を削除する（論理削除）。
     */
    @Transactional
    public void deleteFacility(String scopeType, Long scopeId, Long facilityId) {
        SharedFacilityEntity entity = findFacilityOrThrow(scopeType, scopeId, facilityId);
        entity.softDelete();
    }

    /**
     * 空き状況を取得する。
     */
    public AvailabilityResponse getAvailability(String scopeType, Long scopeId,
                                                 Long facilityId, LocalDate date) {
        SharedFacilityEntity facility = findFacilityOrThrow(scopeType, scopeId, facilityId);

        FacilityUsageRuleEntity rule = usageRuleRepository.findByFacilityId(facilityId)
                .orElseThrow(() -> new BusinessException(FacilityErrorCode.USAGE_RULE_NOT_FOUND));

        List<BookingStatus> excludeStatuses = List.of(BookingStatus.CANCELLED, BookingStatus.REJECTED);
        List<FacilityBookingEntity> existingBookings = bookingRepository
                .findByFacilityIdAndBookingDateAndStatusNotIn(facilityId, date, excludeStatuses);

        List<AvailabilityResponse.AvailabilitySlot> slots = new ArrayList<>();
        LocalTime current = rule.getAvailableTimeFrom();
        LocalTime end = rule.getAvailableTimeTo();

        while (current.isBefore(end)) {
            LocalTime slotEnd = current.plusMinutes(SLOT_MINUTES);
            if (slotEnd.isAfter(end)) {
                break;
            }

            boolean available = isSlotAvailable(current, slotEnd, existingBookings,
                    facility.getCleaningBufferMinutes());

            BigDecimal rate = facility.getRatePerSlot() != null ? facility.getRatePerSlot() : BigDecimal.ZERO;

            slots.add(new AvailabilityResponse.AvailabilitySlot(current, slotEnd, available, rate));
            current = slotEnd;
        }

        return new AvailabilityResponse(facilityId, date, slots);
    }

    /**
     * 施設エンティティを取得する（内部用）。
     */
    public SharedFacilityEntity findFacilityOrThrow(String scopeType, Long scopeId, Long facilityId) {
        return facilityRepository.findByIdAndScopeTypeAndScopeId(facilityId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(FacilityErrorCode.FACILITY_NOT_FOUND));
    }

    /**
     * 施設エンティティをIDで取得する（内部用）。
     */
    public SharedFacilityEntity findFacilityByIdOrThrow(Long facilityId) {
        return facilityRepository.findById(facilityId)
                .orElseThrow(() -> new BusinessException(FacilityErrorCode.FACILITY_NOT_FOUND));
    }

    private boolean isSlotAvailable(LocalTime slotFrom, LocalTime slotTo,
                                     List<FacilityBookingEntity> bookings, int bufferMinutes) {
        for (FacilityBookingEntity booking : bookings) {
            LocalTime bookingStart = booking.getTimeFrom();
            LocalTime bookingEnd = booking.getTimeTo().plusMinutes(bufferMinutes);

            if (slotFrom.isBefore(bookingEnd) && slotTo.isAfter(bookingStart)) {
                return false;
            }
        }
        return true;
    }

    private String serializeImageUrls(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(imageUrls);
        } catch (JsonProcessingException e) {
            log.warn("画像URL のシリアライズに失敗: {}", e.getMessage());
            return null;
        }
    }
}
