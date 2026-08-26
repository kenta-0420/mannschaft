package com.mannschaft.app.team.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チーム所属組織サマリーレスポンス（GET /api/v1/teams/{id}/organizations 用）。
 */
@Getter
@RequiredArgsConstructor
public class TeamOrgSummaryResponse {

    /** URL 識別子（カスタムスラッグ）。 */
    private final String id;
    /** 組織スラッグ（URL ルーティング用）。{@code /organizations/{slug}} に使用する。 */
    private final String slug;
    private final String name;
    private final String iconUrl;
    private final String visibility;
    private final int memberCount;
}
