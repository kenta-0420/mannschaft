package com.mannschaft.app.team.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チームサマリーレスポンス（一覧用）。
 */
@Getter
@RequiredArgsConstructor
public class TeamSummaryResponse {

    /** URL 識別子（カスタムスラッグ）。 */
    private final String id;
    private final String name;
    private final String template;
    private final String visibility;
    private final int memberCount;
    private final long teamFriendCount;
    private final long supporterCount;
}
