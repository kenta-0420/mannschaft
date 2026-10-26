package com.mannschaft.app.venue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 施設登録リクエスト（Google Placesの候補選択時 or 手動登録時）。
 */
@Getter
@RequiredArgsConstructor
public class RegisterVenueRequest {

    /** Google Places place_id（手動登録時はnull） */
    @Size(max = 300)
    private final String googlePlaceId;

    @NotBlank
    @Size(max = 200)
    private final String name;

    @Size(max = 500)
    private final String address;

    private final BigDecimal latitude;
    private final BigDecimal longitude;

    @Size(max = 20)
    private final String prefecture;

    @Size(max = 50)
    private final String city;

    @Size(max = 50)
    private final String category;

    @Size(max = 30)
    private final String phoneNumber;

    @Size(max = 500)
    private final String websiteUrl;
}
