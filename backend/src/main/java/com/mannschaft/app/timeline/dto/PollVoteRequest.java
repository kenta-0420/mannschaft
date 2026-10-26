package com.mannschaft.app.timeline.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * タイムライン投票投票リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class PollVoteRequest {

    @NotNull
    private final Long optionId;
}
