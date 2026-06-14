package com.mannschaft.app.team.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * チーム詳細レスポンス。
 * ネストDTOで関心ごとを分類して返す。
 */
@Builder(toBuilder = true)
@Getter
public class TeamResponse {

    /** URL 識別子（カスタムスラッグ）。 */
    private String id;
    /** チームスラッグ（URL ルーティング用）。{@code /teams/{slug}} に使用する。 */
    private String slug;
    private TeamBasicInfoDto basicInfo;
    private TeamLocationDto location;
    private TeamVisibilityDto visibility;
    private TeamMetadataDto metadata;
    private TeamSocialDto social;
    private TeamTimestampsDto timestamps;

    /** チームの基本情報（名称・ニックネーム）。 */
    public record TeamBasicInfoDto(
            String name,
            String nameKana,
            String nickname1,
            String nickname2
    ) {}

    /**
     * チームの所在地情報。
     *
     * <p>F22.1 市 Phase 2 足場C: 名称（{@code prefecture}/{@code city}）に加え、構造化キーの
     * {@code prefectureCode}/{@code cityCode} を併存して返す（旧名称は表示用に残置）。
     * フィールド名は Jackson 既定の camelCase。</p>
     */
    public record TeamLocationDto(
            String prefecture,
            String city,
            String template,
            String prefectureCode,
            String cityCode
    ) {}

    /** チームの公開設定。 */
    public record TeamVisibilityDto(
            String visibility,
            Boolean supporterEnabled
    ) {}

    /** チームのメタデータ（バージョン・メンバー数・画像URL等）。 */
    public record TeamMetadataDto(
            Long version,
            int memberCount,
            String iconUrl,
            String bannerUrl,
            String mapEmbedUrl
    ) {}

    /** チームのソーシャル情報（フレンド数・サポーター数）。 */
    public record TeamSocialDto(
            long teamFriendCount,
            long supporterCount
    ) {}

    /** チームのタイムスタンプ情報。 */
    public record TeamTimestampsDto(
            LocalDateTime archivedAt,
            LocalDateTime createdAt
    ) {}

}
