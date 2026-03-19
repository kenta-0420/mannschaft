package com.mannschaft.app.team.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チームサマリーレスポンス（一覧用）。
 */
@Getter
@RequiredArgsConstructor
public class TeamSummaryResponse {

    private final Long id;
    private final String name;
    private final String template;
    private final String visibility;
    private final int memberCount;
}
