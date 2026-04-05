package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.ParkingErrorCode;
import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.SpaceType;
import com.mannschaft.app.parking.VisitorReservationStatus;
import com.mannschaft.app.parking.dto.AvailabilityResponse;
import com.mannschaft.app.parking.dto.CreateVisitorReservationRequest;
import com.mannschaft.app.parking.dto.VisitorReservationResponse;
import com.mannschaft.app.parking.entity.ParkingSettingsEntity;
import com.mannschaft.app.parking.entity.ParkingSpaceEntity;
import com.mannschaft.app.parking.entity.ParkingVisitorReservationEntity;
import com.mannschaft.app.parking.repository.ParkingSettingsRepository;
import com.mannschaft.app.parking.repository.ParkingSpaceRepository;
import com.mannschaft.app.parking.repository.ParkingVisitorReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 来場者予約サービス。予約の作成・承認・拒否・チェックイン・完了・空き確認を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ParkingVisitorReservationService {

    private final ParkingVisitorReservationRepository reservationRepository;
    private final ParkingSpaceRepository spaceRepository;
    private final ParkingSettingsRepository settingsRepository;
    private final ParkingMapper parkingMapper;

    private static final List<VisitorReservationStatus> EXCLUDE_STATUSES = List.of(
            VisitorReservationStatus.CANCELLED, VisitorReservationStatus.REJECTED, VisitorReservationStatus.NO_SHOW);

    /**
     * 予約一覧をページング取得する。
     */
    public Page<VisitorReservationResponse> list(List<Long> spaceIds, LocalDate date, Pageable pageable) {
        if (spaceIds.isEmpty()) {
            return Page.empty(pageable);
        }
        Page<ParkingVisitorReservationEntity> page;
        if (date != null) {
            page = reservationRepository.findBySpaceIdInAndReservedDate(spaceIds, date, pageable);
        } else {
            page = reservationRepository.findBySpaceIdIn(spaceIds, pageable);
        }
        return page.map(parkingMapper::toVisitorReservationResponse);
    }

    /**
     * 予約詳細を取得する。
     */
    public VisitorReservationResponse getDetail(Long id) {
        ParkingVisitorReservationEntity entity = reservationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.VISITOR_RESERVATION_NOT_FOUND));
        return parkingMapper.toVisitorReservationResponse(entity);
    }

    /**
     * 予約を作成する。
     */
    @Transactional
    public VisitorReservationResponse create(String scopeType, Long scopeId, Long userId,
                                              CreateVisitorReservationRequest request) {
        ParkingSettingsEntity settings = settingsRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseGet(() -> ParkingSettingsEntity.builder().scopeType(scopeType).scopeId(scopeId).build());

        // 日数チェック
        long daysAhead = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), request.getReservedDate());
        if (daysAhead > settings.getVisitorReservationMaxDaysAhead()) {
            throw new BusinessException(ParkingErrorCode.VISITOR_RESERVATION_DATE_OUT_OF_RANGE);
        }

        // 30分スロットバリデーション
        validateTimeSlot(request.getTimeFrom(), request.getTimeTo());

        // 1日あたり上限チェック
        long todayCount = reservationRepository.countByReservedByAndReservedDateAndStatusNotIn(
                userId, request.getReservedDate(), EXCLUDE_STATUSES);
        if (todayCount >= settings.getMaxVisitorReservationsPerDay()) {
            throw new BusinessException(ParkingErrorCode.VISITOR_RESERVATION_LIMIT);
        }

        // 時間帯重複チェック
        List<ParkingVisitorReservationEntity> existing = reservationRepository
                .findBySpaceIdAndReservedDateAndStatusNotIn(request.getSpaceId(), request.getReservedDate(), EXCLUDE_STATUSES);
        boolean overlap = existing.stream().anyMatch(r ->
                request.getTimeFrom().isBefore(r.getTimeTo()) && request.getTimeTo().isAfter(r.getTimeFrom()));
        if (overlap) {
            throw new BusinessException(ParkingErrorCode.TIME_SLOT_CONFLICT);
        }

        VisitorReservationStatus initialStatus = Boolean.TRUE.equals(settings.getVisitorReservationRequiresApproval())
                ? VisitorReservationStatus.PENDING_APPROVAL
                : VisitorReservationStatus.CONFIRMED;

        ParkingVisitorReservationEntity entity = ParkingVisitorReservationEntity.builder()
                .spaceId(request.getSpaceId())
                .reservedBy(userId)
                .visitorName(request.getVisitorName())
                .visitorPlateNumber(request.getVisitorPlateNumber())
                .reservedDate(request.getReservedDate())
                .timeFrom(request.getTimeFrom())
                .timeTo(request.getTimeTo())
                .purpose(request.getPurpose())
                .status(initialStatus)
                .build();
        ParkingVisitorReservationEntity saved = reservationRepository.save(entity);
        log.info("来場者予約作成: spaceId={}, date={}", request.getSpaceId(), request.getReservedDate());
        return parkingMapper.toVisitorReservationResponse(saved);
    }

    /**
     * 予約を承認する。
     */
    @Transactional
    public VisitorReservationResponse approve(Long id, Long approvedBy) {
        ParkingVisitorReservationEntity entity = reservationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.VISITOR_RESERVATION_NOT_FOUND));
        if (entity.getStatus() != VisitorReservationStatus.PENDING_APPROVAL) {
            throw new BusinessException(ParkingErrorCode.INVALID_VISITOR_STATUS);
        }
        entity.approve(approvedBy);
        ParkingVisitorReservationEntity saved = reservationRepository.save(entity);
        log.info("来場者予約承認: id={}", id);
        return parkingMapper.toVisitorReservationResponse(saved);
    }

    /**
     * 予約を拒否する。
     */
    @Transactional
    public VisitorReservationResponse reject(Long id, Long approvedBy, String adminComment) {
        ParkingVisitorReservationEntity entity = reservationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.VISITOR_RESERVATION_NOT_FOUND));
        if (entity.getStatus() != VisitorReservationStatus.PENDING_APPROVAL) {
            throw new BusinessException(ParkingErrorCode.INVALID_VISITOR_STATUS);
        }
        entity.reject(approvedBy, adminComment);
        ParkingVisitorReservationEntity saved = reservationRepository.save(entity);
        log.info("来場者予約拒否: id={}", id);
        return parkingMapper.toVisitorReservationResponse(saved);
    }

    /**
     * チェックインする。
     */
    @Transactional
    public VisitorReservationResponse checkIn(Long id) {
        ParkingVisitorReservationEntity entity = reservationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.VISITOR_RESERVATION_NOT_FOUND));
        if (entity.getStatus() != VisitorReservationStatus.CONFIRMED) {
            throw new BusinessException(ParkingErrorCode.INVALID_VISITOR_STATUS);
        }
        entity.checkIn();
        ParkingVisitorReservationEntity saved = reservationRepository.save(entity);
        log.info("来場者チェックイン: id={}", id);
        return parkingMapper.toVisitorReservationResponse(saved);
    }

    /**
     * 完了にする。
     */
    @Transactional
    public VisitorReservationResponse complete(Long id) {
        ParkingVisitorReservationEntity entity = reservationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.VISITOR_RESERVATION_NOT_FOUND));
        if (entity.getStatus() != VisitorReservationStatus.CHECKED_IN) {
            throw new BusinessException(ParkingErrorCode.INVALID_VISITOR_STATUS);
        }
        entity.complete();
        ParkingVisitorReservationEntity saved = reservationRepository.save(entity);
        log.info("来場者予約完了: id={}", id);
        return parkingMapper.toVisitorReservationResponse(saved);
    }

    /**
     * 予約をキャンセルする。
     */
    @Transactional
    public void cancel(Long id) {
        ParkingVisitorReservationEntity entity = reservationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.VISITOR_RESERVATION_NOT_FOUND));
        entity.cancel();
        reservationRepository.save(entity);
        log.info("来場者予約キャンセル: id={}", id);
    }

    /**
     * 時刻が30分単位であることを検証する。
     */
    private void validateTimeSlot(LocalTime timeFrom, LocalTime timeTo) {
        if (timeFrom.getMinute() % 30 != 0 || timeTo.getMinute() % 30 != 0) {
            throw new BusinessException(ParkingErrorCode.INVALID_TIME_SLOT);
        }
        if (!timeTo.isAfter(timeFrom)) {
            throw new BusinessException(ParkingErrorCode.INVALID_TIME_SLOT);
        }
    }

    /**
     * 指定日の来場者用区画の空き状況を取得する。
     */
    public AvailabilityResponse getAvailability(String scopeType, Long scopeId, LocalDate date) {
        List<ParkingSpaceEntity> visitorSpaces = spaceRepository.findByScopeTypeAndScopeIdAndSpaceType(
                scopeType, scopeId, SpaceType.VISITOR, Pageable.unpaged()).getContent();
        List<Long> visitorSpaceIds = visitorSpaces.stream().map(ParkingSpaceEntity::getId).toList();

        List<ParkingVisitorReservationEntity> reservations = visitorSpaceIds.isEmpty()
                ? List.of()
                : reservationRepository.findBySpaceIdInAndReservedDateAndStatusNotIn(visitorSpaceIds, date, EXCLUDE_STATUSES);

        Set<Long> reservedSpaceIds = new java.util.HashSet<>();
        reservations.forEach(r -> reservedSpaceIds.add(r.getSpaceId()));

        List<AvailabilityResponse.SpaceAvailability> spaces = new ArrayList<>();
        for (ParkingSpaceEntity space : visitorSpaces) {
            spaces.add(new AvailabilityResponse.SpaceAvailability(
                    space.getId(), space.getSpaceNumber(), !reservedSpaceIds.contains(space.getId())));
        }
        return new AvailabilityResponse(date, spaces);
    }
}
