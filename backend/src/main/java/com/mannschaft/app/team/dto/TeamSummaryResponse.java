package com.mannschaft.app.team.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/**
 * チームサマリーレスポンス（一覧用）。
 */
@Getter
@RequiredArgsConstructor
public class TeamSummaryResponse {

    private final UUID id;
    private final String name;
    private final String template;
    private final String visibility;
    private final int memberCount;
    private final long teamFriendCount;
    private final long supporterCount;
}
