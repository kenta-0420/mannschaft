package com.mannschaft.app.proxyvote.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 投票結果レスポンスDTO。
 */
@Getter
@Builder
public class CastVoteResponse {

    private final Long sessionId;
    private final Integer votedMotions;
    private final LocalDateTime votedAt;
}
