package com.mannschaft.app.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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

    /** URL 識別子（カスタムスラッグ）。実体は {@code slug} と同値。 */
    private String id;
    /** チームスラッグ（URL ルーティング用）。{@code /teams/{slug}} に使用する。 */
    private String slug;
    /**
     * チームの内部 BIGINT ID（F09.19.10）。
     *
     * <p>URL には使用しない（URL 識別子は上記 {@code id}/{@code slug} が正準）。
     * Spotlight 掲載面 API（{@code GET /api/v1/spotlight/content?scopeType=TEAM&scopeId=}）等、
     * BE が Long スコープ ID を要求する内部連携専用に公開する。露出先は当該チームを閲覧可能な者
     * （visibility ラダー準拠）に限られ、cross-domain FK には使わない。</p>
     */
    private Long numericId;
    private TeamBasicInfoDto basicInfo;
    private TeamLocationDto location;
    private TeamVisibilityDto visibility;
    /** 予約枠の現地日付・時刻を解釈するチーム固有の IANA タイムゾーン。 */
    @Schema(description = "チームのIANAタイムゾーン")
    private String timezone;
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
