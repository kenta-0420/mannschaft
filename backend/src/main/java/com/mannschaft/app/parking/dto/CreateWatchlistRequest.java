package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * ウォッチリスト登録リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateWatchlistRequest {

    private final String spaceType;

    @Size(max = 10)
    private final String floor;

    private final BigDecimal maxPrice;
}
