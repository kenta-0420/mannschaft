package com.mannschaft.app.facility.service;

import com.mannschaft.app.common.HolidayService;
import com.mannschaft.app.facility.DayType;
import com.mannschaft.app.facility.entity.FacilityBookingEquipmentEntity;
import com.mannschaft.app.facility.entity.FacilityTimeRateEntity;
import com.mannschaft.app.facility.entity.SharedFacilityEntity;
import com.mannschaft.app.facility.repository.FacilityTimeRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 施設利用料金計算コンポーネント。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FacilityFeeCalculator {

    private static final int SLOT_MINUTES = 30;

    private final FacilityTimeRateRepository timeRateRepository;
    private final HolidayService holidayService;

    /**
     * 利用料金を計算する。
     *
     * @param facility    施設エンティティ
     * @param bookingDate 予約日
     * @param timeFrom    開始時刻
     * @param timeTo      終了時刻
     * @param stayNights  宿泊数
     * @return 利用料金
     */
    public BigDecimal calculateUsageFee(SharedFacilityEntity facility, LocalDate bookingDate,
                                         LocalTime timeFrom, LocalTime timeTo, int stayNights) {
        // 宿泊の場合は宿泊料金
        if (stayNights > 0 && facility.getRatePerNight() != null) {
            return facility.getRatePerNight().multiply(BigDecimal.valueOf(stayNights));
        }

        // 時間帯別料金が設定されている場合はそちらを使用
        DayType dayType = toDayType(bookingDate);
        List<FacilityTimeRateEntity> rates = timeRateRepository.findByFacilityIdAndDayType(
                facility.getId(), dayType);

        if (!rates.isEmpty()) {
            return calculateByTimeRates(rates, timeFrom, timeTo);
        }

        // デフォルト: 施設の基本料金 × スロット数
        if (facility.getRatePerSlot() != null) {
            int slotCount = calculateSlotCount(timeFrom, timeTo);
            return facility.getRatePerSlot().multiply(BigDecimal.valueOf(slotCount));
        }

        return BigDecimal.ZERO;
    }

    /**
     * 備品料金を計算する。
     *
     * @param equipmentItems 予約備品リスト
     * @return 備品料金合計
     */
    public BigDecimal calculateEquipmentFee(List<FacilityBookingEquipmentEntity> equipmentItems) {
        return equipmentItems.stream()
                .map(FacilityBookingEquipmentEntity::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * スロット数を計算する。
     *
     * @param timeFrom 開始時刻
     * @param timeTo   終了時刻
     * @return スロット数
     */
    public int calculateSlotCount(LocalTime timeFrom, LocalTime timeTo) {
        long minutes = ChronoUnit.MINUTES.between(timeFrom, timeTo);
        return (int) Math.max(1, minutes / SLOT_MINUTES);
    }

    private BigDecimal calculateByTimeRates(List<FacilityTimeRateEntity> rates,
                                             LocalTime timeFrom, LocalTime timeTo) {
        BigDecimal total = BigDecimal.ZERO;
        LocalTime current = timeFrom;

        while (current.isBefore(timeTo)) {
            LocalTime slotEnd = current.plusMinutes(SLOT_MINUTES);
            if (slotEnd.isAfter(timeTo)) {
                slotEnd = timeTo;
            }

            BigDecimal slotRate = findRateForTime(rates, current);
            total = total.add(slotRate);
            current = slotEnd;
        }
        return total;
    }

    private BigDecimal findRateForTime(List<FacilityTimeRateEntity> rates, LocalTime time) {
        for (FacilityTimeRateEntity rate : rates) {
            if (!time.isBefore(rate.getTimeFrom()) && time.isBefore(rate.getTimeTo())) {
                return rate.getRatePerSlot();
            }
        }
        return BigDecimal.ZERO;
    }

    private DayType toDayType(LocalDate date) {
        if (holidayService.isSystemHoliday(date)) {
            return DayType.HOLIDAY;
        }
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return DayType.WEEKEND;
        }
        return DayType.WEEKDAY;
    }
}
