package com.mannschaft.app.resident.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 居住者更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateResidentRequest {

    @NotBlank
    @Size(max = 20)
    private final String residentType;

    @NotBlank
    @Size(max = 50)
    private final String lastName;

    @NotBlank
    @Size(max = 50)
    private final String firstName;

    @Size(max = 100)
    private final String lastNameKana;

    @Size(max = 100)
    private final String firstNameKana;

    @Size(max = 20)
    private final String phone;

    @Size(max = 255)
    private final String email;

    @Size(max = 200)
    private final String emergencyContact;

    @NotNull
    private final LocalDate moveInDate;

    private final BigDecimal ownershipRatio;

    private final Boolean isPrimary;

    private final String notes;
}
