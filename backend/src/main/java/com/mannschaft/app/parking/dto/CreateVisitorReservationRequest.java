package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 来場者予約作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateVisitorReservationRequest {

    @NotNull
    private final Long spaceId;

    @Size(max = 100)
    private final String visitorName;

    @Size(max = 30)
    private final String visitorPlateNumber;

    @NotNull
    private final LocalDate reservedDate;

    @NotNull
    private final LocalTime timeFrom;

    @NotNull
    private final LocalTime timeTo;

    @Size(max = 200)
    private final String purpose;
}
