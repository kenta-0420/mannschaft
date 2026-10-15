package com.mannschaft.app.resident.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 居住者レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ResidentResponse {

    private final Long id;
    private final Long dwellingUnitId;
    private final Long userId;
    private final String residentType;
    private final String lastName;
    private final String firstName;
    private final String lastNameKana;
    private final String firstNameKana;
    private final String phone;
    private final String email;
    private final String emergencyContact;
    private final LocalDate moveInDate;
    private final LocalDate moveOutDate;
    private final BigDecimal ownershipRatio;
    private final Boolean isPrimary;
    private final Boolean isVerified;
    private final Long verifiedBy;
    private final LocalDateTime verifiedAt;
    private final String notes;
    private final LocalDateTime createdAt;
}
