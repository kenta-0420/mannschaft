package com.mannschaft.app.team.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * チーム詳細レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class TeamResponse {

    private final Long id;
    private final String name;
    private final String nameKana;
    private final String nickname1;
    private final String nickname2;
    private final String template;
    private final String prefecture;
    private final String city;
    private final String visibility;
    private final Boolean supporterEnabled;
    private final Long version;
    private final int memberCount;
    private final String iconUrl;
    private final String bannerUrl;
    private final LocalDateTime archivedAt;
    private final LocalDateTime createdAt;
    private final long teamFriendCount;
    private final long supporterCount;
}
