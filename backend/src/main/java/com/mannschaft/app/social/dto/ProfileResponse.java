package com.mannschaft.app.social.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ソーシャルプロフィールレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ProfileResponse {

    private final Long id;
    private final Long userId;
    private final String handle;
    private final String displayName;
    private final String avatarUrl;
    private final String bio;
    private final Boolean isActive;
    private final long followingCount;
    private final long followerCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
