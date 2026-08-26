package com.mannschaft.app.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * メンバー情報レスポンス。
 */
@Getter
@Schema(name = "ScopeMemberResponse")
public class MemberResponse {

    private final Long userId;
    private final String displayName;
    private final String avatarUrl;
    private final String roleName;
    private final LocalDateTime joinedAt;
    private final String calendarColor;

    public MemberResponse(Long userId, String displayName, String avatarUrl,
                          String roleName, LocalDateTime joinedAt) {
        this(userId, displayName, avatarUrl, roleName, joinedAt, null);
    }

    public MemberResponse(Long userId, String displayName, String avatarUrl,
                          String roleName, LocalDateTime joinedAt, String calendarColor) {
        this.userId = userId;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.roleName = roleName;
        this.joinedAt = joinedAt;
        this.calendarColor = calendarColor;
    }
}
