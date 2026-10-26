package com.mannschaft.app.shift.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * シフトポジションレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ShiftPositionResponse {

    private final Long id;
    private final Long teamId;
    private final String name;
    private final Integer displayOrder;
    private final Boolean isActive;
    private final LocalDateTime createdAt;
}
