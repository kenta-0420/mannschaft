package com.mannschaft.app.role.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * メンバー情報レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class MemberResponse {

    private final Long userId;
    private final String displayName;
    private final String avatarUrl;
    private final String roleName;
    private final LocalDateTime joinedAt;
}
