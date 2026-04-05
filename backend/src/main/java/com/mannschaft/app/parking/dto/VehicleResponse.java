package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 車両レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class VehicleResponse {

    private final Long id;
    private final Long userId;
    private final String vehicleType;
    private final String plateNumber;
    private final String nickname;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

}
