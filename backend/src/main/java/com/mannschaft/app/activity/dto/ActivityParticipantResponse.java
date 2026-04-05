package com.mannschaft.app.activity.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 活動参加者レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ActivityParticipantResponse {

    private final Long id;
    private final Long userId;
    private final String displayName;
    private final String memberNumber;
    private final String roleLabel;
    private final LocalDateTime createdAt;
}
