package com.mannschaft.app.team.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チーム所属組織サマリーレスポンス（GET /api/v1/teams/{id}/organizations 用）。
 */
@Getter
@RequiredArgsConstructor
public class TeamOrgSummaryResponse {

    private final Long id;
    private final String name;
    private final String iconUrl;
    private final String visibility;
    private final int memberCount;
}
