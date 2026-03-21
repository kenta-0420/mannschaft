package com.mannschaft.app.timeline.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ミュートレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MuteResponse {

    private final Long id;
    private final Long userId;
    private final String mutedType;
    private final Long mutedId;
    private final LocalDateTime createdAt;
}
