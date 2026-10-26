package com.mannschaft.app.social.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * フォローレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FollowResponse {

    private final Long id;
    private final String followerType;
    private final Long followerId;
    private final String followedType;
    private final Long followedId;
    private final LocalDateTime createdAt;
}
