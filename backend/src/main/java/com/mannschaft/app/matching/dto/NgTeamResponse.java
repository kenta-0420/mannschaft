package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * NGチームレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class NgTeamResponse {

    private final Long blockedTeamId;
    private final LocalDateTime createdAt;
}
