package com.mannschaft.app.member.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * メンバープロフィールレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MemberProfileResponse {

    private final Long id;
    private final Long teamPageId;
    private final Long userId;
    private final String displayName;
    private final String memberNumber;
    private final String photoS3Key;
    private final String bio;
    private final String position;
    private final String customFieldValues;
    private final Integer sortOrder;
    private final Boolean isVisible;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
