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

    /** URL 識別子（カスタムスラッグ）。 */
    private final String id;
    /** チームスラッグ（URL ルーティング用）。{@code /teams/{slug}} に使用する。 */
    private final String slug;
    private final String name;
    private final String iconUrl;
    private final String visibility;
    private final int memberCount;
    /**
     * 所属するチームグループ（F01.2.1 §10.1）。グループ機能が off・未分類・削除済みグループを指す行・
     * 閲覧者が組織の MEMBER 以上でない場合は null。
     */
    private final OrgTeamSummaryGroupRef teamGroup;

    /**
     * チームグループの表示用の最小情報（OpenAPI のスキーマ名が他の DTO と衝突しないよう固有の名前にしている）。
     *
     * @param id        グループ ID
     * @param name      グループ名
     * @param sortOrder 並び順
     */
    public record OrgTeamSummaryGroupRef(UUID id, String name, int sortOrder) {
    }
}
