package com.mannschaft.app.timeline.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * タイムライン投票レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PollResponse {

    private final Long id;
    private final Long timelinePostId;
    private final String question;
    private final Integer totalVoteCount;
    private final LocalDateTime expiresAt;
    private final Boolean isClosed;
    private final List<PollOptionResponse> options;
    private final Long myVotedOptionId;
}
