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
    /** チームスラッグ（URL ルーティング用）。{@code /teams/{slug}} に使用する。 */
    private final String slug;
    private final String name;
    private final String template;
    private final String visibility;
    private final int memberCount;
    private final long teamFriendCount;
    private final long supporterCount;
}
