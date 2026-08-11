package com.mannschaft.app.schedule.dto;

import lombok.Builder;
import lombok.Getter;

/** F03.16 メンション候補ユーザー（設計書 §4.4）。 */
@Getter
@Builder
public class MentionCandidateResponse {
    private final Long userId;
    private final String displayName;
    private final String avatarUrl;
}
