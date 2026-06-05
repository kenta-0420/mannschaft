package com.mannschaft.app.organization.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/**
 * 組織所属チームサマリーレスポンス（GET /api/v1/organizations/{id}/teams 用）。
 */
@Getter
@RequiredArgsConstructor
public class OrgTeamSummaryResponse {

    private final UUID id;
    private final String name;
    private final String iconUrl;
    private final String visibility;
    private final int memberCount;
}
