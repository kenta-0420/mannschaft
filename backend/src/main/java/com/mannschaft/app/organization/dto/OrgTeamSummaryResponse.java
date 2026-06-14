package com.mannschaft.app.organization.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 組織所属チームサマリーレスポンス（GET /api/v1/organizations/{id}/teams 用）。
 */
@Getter
@RequiredArgsConstructor
public class OrgTeamSummaryResponse {

    /** URL 識別子（カスタムスラッグ）。 */
    private final String id;
    /** チームスラッグ（URL ルーティング用）。{@code /teams/{slug}} に使用する。 */
    private final String slug;
    private final String name;
    private final String iconUrl;
    private final String visibility;
    private final int memberCount;
}
