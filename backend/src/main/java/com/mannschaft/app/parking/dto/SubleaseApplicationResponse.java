package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * サブリース申請レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SubleaseApplicationResponse {

    private final Long id;
    private final Long subleaseId;
    private final Long userId;
    private final Long vehicleId;
    private final String message;
    private final String status;
    private final LocalDateTime decidedAt;
    private final LocalDateTime createdAt;
}
