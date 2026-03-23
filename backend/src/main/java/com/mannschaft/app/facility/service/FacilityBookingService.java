package com.mannschaft.app.facility.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.facility.BookingStatus;
import com.mannschaft.app.facility.FacilityErrorCode;
import com.mannschaft.app.facility.FacilityMapper;
import com.mannschaft.app.facility.dto.ApproveBookingRequest;
import com.mannschaft.app.facility.dto.BookingDetailResponse;
import com.mannschaft.app.facility.dto.BookingResponse;
import com.mannschaft.app.facility.dto.CalendarBookingResponse;
import com.mannschaft.app.facility.dto.CancelBookingRequest;
import com.mannschaft.app.facility.dto.CreateBookingRequest;
import com.mannschaft.app.facility.dto.RejectBookingRequest;
import com.mannschaft.app.facility.dto.UpdateBookingRequest;
import com.mannschaft.app.facility.entity.FacilityBookingEntity;
import com.mannschaft.app.facility.entity.FacilityBookingEquipmentEntity;
import com.mannschaft.app.facility.entity.FacilityEquipmentEntity;
import com.mannschaft.app.facility.entity.FacilitySettingsEntity;
import com.mannschaft.app.facility.entity.FacilityUsageRuleEntity;
import com.mannschaft.app.facility.entity.SharedFacilityEntity;
import com.mannschaft.app.facility.repository.FacilityBookingEquipmentRepository;
import com.mannschaft.app.facility.repository.FacilityBookingRepository;
import com.mannschaft.app.facility.repository.FacilitySettingsRepository;
import com.mannschaft.app.facility.repository.FacilityUsageRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 施設予約サービス。予約CRUD・ステータス遷移・カレンダーを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FacilityBookingService {

    private static final List<BookingStatus> INACTIVE_STATUSES =
            List.of(BookingStatus.CANCELLED, BookingStatus.REJECTED);

    private final FacilityBookingRepository bookingRepository;
    private final FacilityBookingEquipmentRepository bookingEquipmentRepository;
    private final FacilityUsageRuleRepository usageRuleRepository;
    private final FacilitySettingsRepository settingsRepository;
    private final FacilityService facilityService;
    private final FacilityEquipmentService equipmentService;
    private final FacilityFeeCalculator feeCalculator;
    private final FacilityMapper facilityMapper;

    /**
     * 予約一覧をページング取得する。
     */
    public Page<BookingResponse> listBookings(String scopeType, Long scopeId,
                                               String status, Pageable pageable) {
        Page<FacilityBookingEntity> page;
        if (status != null) {
            BookingStatus bookingStatus = BookingStatus.valueOf(status);
            page = bookingRepository.findByScopeAndStatusOrderByBookingDateDesc(
                    scopeType, scopeId, bookingStatus, pageable);
        } else {
            page = bookingRepository.findByScopeOrderByBookingDateDesc(scopeType, scopeId, pageable);
        }
        return page.map(entity -> {
            BookingResponse response = facilityMapper.toBookingResponse(entity);
            return response;
        });
    }

    /**
     * 予約詳細を取得する。
     */
    public BookingDetailResponse getBooking(Long bookingId) {
        FacilityBookingEntity entity = findBookingOrThrow(bookingId);
        BookingDetailResponse response = facilityMapper.toBookingDetailResponse(entity);
        return response;
    }

    /**
     * 予約を作成する。
     */
    @Transactional
    public BookingResponse createBooking(String scopeType, Long scopeId, Long userId,
                                          CreateBookingRequest request) {
        SharedFacilityEntity facility = facilityService.findFacilityByIdOrThrow(request.getFacilityId());

        if (!facility.getIsActive()) {
            throw new BusinessException(FacilityErrorCode.FACILITY_INACTIVE);
        }

        // 日付バリデーション
        if (request.getBookingDate().isBefore(LocalDate.now())) {
            throw new BusinessException(FacilityErrorCode.PAST_DATE_BOOKING);
        }

        // ルールバリデーション
        FacilityUsageRuleEntity rule = usageRuleRepository.findByFacilityId(facility.getId())
                .orElse(null);
        if (rule != null) {
            validateBookingAgainstRules(rule, request.getBookingDate(), request.getTimeFrom(),
                    request.getTimeTo(), userId, facility.getId());
        }

        // 日次予約上限チェック
        FacilitySettingsEntity settings = settingsRepository
                .findByScopeTypeAndScopeId(scopeType, scopeId).orElse(null);
        if (settings != null) {
            long dailyCount = bookingRepository.countByFacilityIdAndBookingDateAndBookedByAndStatusNotIn(
                    facility.getId(), request.getBookingDate(), userId, INACTIVE_STATUSES);
            if (dailyCount >= settings.getMaxBookingsPerDayPerUser()) {
                throw new BusinessException(FacilityErrorCode.DAILY_BOOKING_LIMIT_EXCEEDED);
            }
        }

        // 時間帯重複チェック
        List<FacilityBookingEntity> overlapping = bookingRepository.findOverlapping(
                facility.getId(), request.getBookingDate(),
                request.getTimeFrom(), request.getTimeTo(), INACTIVE_STATUSES);
        if (!overlapping.isEmpty()) {
            throw new BusinessException(FacilityErrorCode.TIME_SLOT_CONFLICT);
        }

        // スロット数計算
        int slotCount = feeCalculator.calculateSlotCount(request.getTimeFrom(), request.getTimeTo());

        // 料金計算
        int stayNights = request.getStayNights() != null ? request.getStayNights() : 0;
        BigDecimal usageFee = feeCalculator.calculateUsageFee(
                facility, request.getBookingDate(), request.getTimeFrom(), request.getTimeTo(), stayNights);

        // 予約エンティティ作成
        BookingStatus initialStatus = (settings != null && !settings.getRequiresApproval())
                || facility.getAutoApprove()
                ? BookingStatus.CONFIRMED
                : BookingStatus.PENDING_APPROVAL;

        FacilityBookingEntity booking = FacilityBookingEntity.builder()
                .facilityId(facility.getId())
                .bookedBy(userId)
                .bookingDate(request.getBookingDate())
                .checkOutDate(request.getCheckOutDate())
                .stayNights(stayNights)
                .timeFrom(request.getTimeFrom())
                .timeTo(request.getTimeTo())
                .slotCount(slotCount)
                .purpose(request.getPurpose())
                .attendeeCount(request.getAttendeeCount())
                .usageFee(usageFee)
                .status(initialStatus)
                .build();

        FacilityBookingEntity savedBooking = bookingRepository.save(booking);

        // 備品処理
        BigDecimal equipmentFee = BigDecimal.ZERO;
        if (request.getEquipment() != null && !request.getEquipment().isEmpty()) {
            equipmentFee = processBookingEquipment(savedBooking.getId(), request.getEquipment());
        }

        // 料金設定
        BigDecimal totalFee = usageFee.add(equipmentFee);
        savedBooking.setFees(usageFee, equipmentFee, totalFee);

        return facilityMapper.toBookingResponse(savedBooking);
    }

    /**
     * 予約を更新する。
     */
    @Transactional
    public BookingDetailResponse updateBooking(Long bookingId, UpdateBookingRequest request) {
        FacilityBookingEntity booking = findBookingOrThrow(bookingId);

        if (!booking.isApprovable()) {
            throw new BusinessException(FacilityErrorCode.INVALID_BOOKING_STATUS);
        }

        int stayNights = request.getStayNights() != null ? request.getStayNights() : 0;
        int slotCount = feeCalculator.calculateSlotCount(request.getTimeFrom(), request.getTimeTo());

        booking.updateBooking(
                request.getBookingDate(), request.getCheckOutDate(), stayNights,
                request.getTimeFrom(), request.getTimeTo(), slotCount,
                request.getPurpose(), request.getAttendeeCount()
        );

        // 料金再計算
        SharedFacilityEntity facility = facilityService.findFacilityByIdOrThrow(booking.getFacilityId());
        BigDecimal usageFee = feeCalculator.calculateUsageFee(
                facility, request.getBookingDate(), request.getTimeFrom(), request.getTimeTo(), stayNights);

        // 備品再処理
        BigDecimal equipmentFee = BigDecimal.ZERO;
        if (request.getEquipment() != null) {
            bookingEquipmentRepository.deleteByBookingId(bookingId);
            if (!request.getEquipment().isEmpty()) {
                equipmentFee = processBookingEquipment(bookingId, request.getEquipment());
            }
        }

        booking.setFees(usageFee, equipmentFee, usageFee.add(equipmentFee));

        return facilityMapper.toBookingDetailResponse(booking);
    }

    /**
     * 予約をキャンセルする。
     */
    @Transactional
    public BookingDetailResponse cancelBooking(Long bookingId, Long userId, CancelBookingRequest request) {
        FacilityBookingEntity booking = findBookingOrThrow(bookingId);

        if (!booking.isCancellable()) {
            throw new BusinessException(FacilityErrorCode.INVALID_BOOKING_STATUS);
        }

        booking.cancel(userId, request.getCancellationReason());
        return facilityMapper.toBookingDetailResponse(booking);
    }

    /**
     * 予約を承認する。
     */
    @Transactional
    public BookingDetailResponse approveBooking(Long bookingId, Long adminUserId,
                                                 ApproveBookingRequest request) {
        FacilityBookingEntity booking = findBookingOrThrow(bookingId);

        if (!booking.isApprovable()) {
            throw new BusinessException(FacilityErrorCode.INVALID_BOOKING_STATUS);
        }

        booking.approve(adminUserId, request.getAdminComment());
        return facilityMapper.toBookingDetailResponse(booking);
    }

    /**
     * 予約を却下する。
     */
    @Transactional
    public BookingDetailResponse rejectBooking(Long bookingId, Long adminUserId,
                                                RejectBookingRequest request) {
        FacilityBookingEntity booking = findBookingOrThrow(bookingId);

        if (!booking.isApprovable()) {
            throw new BusinessException(FacilityErrorCode.INVALID_BOOKING_STATUS);
        }

        booking.reject(adminUserId, request.getAdminComment());
        return facilityMapper.toBookingDetailResponse(booking);
    }

    /**
     * チェックインする。
     */
    @Transactional
    public BookingDetailResponse checkIn(Long bookingId) {
        FacilityBookingEntity booking = findBookingOrThrow(bookingId);

        if (!booking.isCheckInnable()) {
            throw new BusinessException(FacilityErrorCode.INVALID_BOOKING_STATUS);
        }

        booking.checkIn();
        return facilityMapper.toBookingDetailResponse(booking);
    }

    /**
     * 利用完了する。
     */
    @Transactional
    public BookingDetailResponse completeBooking(Long bookingId) {
        FacilityBookingEntity booking = findBookingOrThrow(bookingId);

        if (!booking.isCompletable()) {
            throw new BusinessException(FacilityErrorCode.INVALID_BOOKING_STATUS);
        }

        booking.complete();
        return facilityMapper.toBookingDetailResponse(booking);
    }

    /**
     * カレンダー予約を取得する。
     */
    public List<CalendarBookingResponse> getCalendarBookings(String scopeType, Long scopeId,
                                                              LocalDate dateFrom, LocalDate dateTo) {
        List<FacilityBookingEntity> bookings = bookingRepository.findCalendarBookings(
                scopeType, scopeId, dateFrom, dateTo, INACTIVE_STATUSES);
        return facilityMapper.toCalendarBookingResponseList(bookings);
    }

    /**
     * 確認用PDF用のデータを取得する（placeholder）。
     */
    public BookingDetailResponse getBookingForPdf(Long bookingId) {
        return getBooking(bookingId);
    }

    private FacilityBookingEntity findBookingOrThrow(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BusinessException(FacilityErrorCode.BOOKING_NOT_FOUND));
    }

    private void validateBookingAgainstRules(FacilityUsageRuleEntity rule, LocalDate bookingDate,
                                              LocalTime timeFrom, LocalTime timeTo,
                                              Long userId, Long facilityId) {
        // 事前予約時間チェック
        LocalDateTime bookingDateTime = LocalDateTime.of(bookingDate, timeFrom);
        long hoursUntilBooking = ChronoUnit.HOURS.between(LocalDateTime.now(), bookingDateTime);
        if (hoursUntilBooking < rule.getMinAdvanceHours()) {
            throw new BusinessException(FacilityErrorCode.INSUFFICIENT_ADVANCE_TIME);
        }

        // 最大予約可能日数チェック
        long daysUntilBooking = ChronoUnit.DAYS.between(LocalDate.now(), bookingDate);
        if (daysUntilBooking > rule.getMaxAdvanceDays()) {
            throw new BusinessException(FacilityErrorCode.EXCEED_MAX_ADVANCE_DAYS);
        }

        // 利用可能時間チェック
        if (timeFrom.isBefore(rule.getAvailableTimeFrom()) || timeTo.isAfter(rule.getAvailableTimeTo())) {
            throw new BusinessException(FacilityErrorCode.OUTSIDE_AVAILABLE_HOURS);
        }

        // 予約時間チェック
        long minutes = ChronoUnit.MINUTES.between(timeFrom, timeTo);
        BigDecimal hours = BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 1, java.math.RoundingMode.HALF_UP);
        if (hours.compareTo(rule.getMinHoursPerBooking()) < 0) {
            throw new BusinessException(FacilityErrorCode.BELOW_MINIMUM_HOURS);
        }
        if (hours.compareTo(rule.getMaxHoursPerBooking()) > 0) {
            throw new BusinessException(FacilityErrorCode.EXCEED_MAXIMUM_HOURS);
        }

        // 月間予約上限チェック
        long monthlyCount = bookingRepository.countMonthlyBookings(
                userId, facilityId, bookingDate.getYear(), bookingDate.getMonthValue(), INACTIVE_STATUSES);
        if (monthlyCount >= rule.getMaxBookingsPerMonthPerUser()) {
            throw new BusinessException(FacilityErrorCode.MONTHLY_BOOKING_LIMIT_EXCEEDED);
        }

        // 利用可能曜日チェック
        int dayOfWeek = bookingDate.getDayOfWeek().getValue() % 7; // 0=日, 1=月, ..., 6=土
        String availableDays = rule.getAvailableDaysOfWeek();
        if (availableDays != null && !availableDays.contains(String.valueOf(dayOfWeek))) {
            throw new BusinessException(FacilityErrorCode.UNAVAILABLE_DAY_OF_WEEK);
        }
    }

    private BigDecimal processBookingEquipment(Long bookingId,
                                                List<CreateBookingRequest.BookingEquipmentEntry> entries) {
        BigDecimal totalEquipmentFee = BigDecimal.ZERO;

        for (CreateBookingRequest.BookingEquipmentEntry entry : entries) {
            FacilityEquipmentEntity equipment = equipmentService.findEquipmentOrThrow(entry.getEquipmentId());
            BigDecimal unitPrice = equipment.getPricePerUse() != null ? equipment.getPricePerUse() : BigDecimal.ZERO;
            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(entry.getQuantity()));

            FacilityBookingEquipmentEntity bookingEquipment = FacilityBookingEquipmentEntity.builder()
                    .bookingId(bookingId)
                    .equipmentId(entry.getEquipmentId())
                    .quantity(entry.getQuantity())
                    .unitPrice(unitPrice)
                    .subtotal(subtotal)
                    .build();

            bookingEquipmentRepository.save(bookingEquipment);
            totalEquipmentFee = totalEquipmentFee.add(subtotal);
        }

        return totalEquipmentFee;
    }
}
