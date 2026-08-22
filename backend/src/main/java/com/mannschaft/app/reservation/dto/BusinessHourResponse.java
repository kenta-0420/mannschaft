package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalTime;

/**
 * 営業時間レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class BusinessHourResponse {

    Long id;
    Long teamId;
    BusinessStatusDto businessStatus;

    public record BusinessStatusDto(String dayOfWeek, Boolean isOpen, LocalTime openTime, LocalTime closeTime,
                                    Boolean endsNextDay) {
        public BusinessStatusDto(String dayOfWeek, Boolean isOpen, LocalTime openTime, LocalTime closeTime) {
            this(dayOfWeek, isOpen, openTime, closeTime, false);
        }
    }
}
