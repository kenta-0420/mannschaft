package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 車両更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateVehicleRequest {

    @NotNull
    private final String vehicleType;

    @NotBlank
    @Size(max = 30)
    private final String plateNumber;

    @Size(max = 50)
    private final String nickname;
}
