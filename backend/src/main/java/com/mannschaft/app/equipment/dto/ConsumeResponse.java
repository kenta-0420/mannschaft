package com.mannschaft.app.equipment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消耗品消費レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ConsumeResponse {

    private final Long equipmentItemId;
    private final String equipmentName;
    private final Integer consumedQuantity;
    private final Integer remainingQuantity;
    private final LocalDateTime consumedAt;
}
