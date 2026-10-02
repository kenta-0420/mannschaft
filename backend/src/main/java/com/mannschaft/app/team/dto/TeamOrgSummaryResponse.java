package com.mannschaft.app.team.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

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
    /**
     * 自チームが所属するチームグループ（F01.2.1 §10.1。自チームのグループ名だけ。他のグループは出さない）。
     * グループ機能が off・未分類・削除済みグループを指す行・閲覧者がチームの MEMBER 以上でない場合は null。
     */
    private final TeamOrgSummaryGroupRef teamGroup;

    /**
     * チームグループの表示用の最小情報（OpenAPI のスキーマ名が他の DTO と衝突しないよう固有の名前にしている）。
     *
     * @param id   グループ ID
     * @param name グループ名
     */
    public record TeamOrgSummaryGroupRef(UUID id, String name) {
    }
}
