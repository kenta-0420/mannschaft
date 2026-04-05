package com.mannschaft.app.venue.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * 施設レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class VenueResponse {

    private final Long id;
    private final String googlePlaceId;
    private final String name;
    private final String address;
    private final BigDecimal latitude;
    private final BigDecimal longitude;
    private final String prefecture;
    private final String city;
    private final String category;
}
