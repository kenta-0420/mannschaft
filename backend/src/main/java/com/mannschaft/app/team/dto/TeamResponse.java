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

    private Long id;
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

    /** チームの所在地情報。 */
    public record TeamLocationDto(
            String prefecture,
            String city,
            String template
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

    // ──────────────────────────────────────────────────────────────────
    // 後方互換アクセサ（既存コードのフラットなフィールドアクセスをブリッジ）
    // 新規コードでは basicInfo().name() 等のネスト参照を使用すること。
    // ──────────────────────────────────────────────────────────────────

    /** @deprecated {@code getBasicInfo().name()} を使用すること */
    @Deprecated
    public String getName() {
        return basicInfo != null ? basicInfo.name() : null;
    }

    /** @deprecated {@code getBasicInfo().nameKana()} を使用すること */
    @Deprecated
    public String getNameKana() {
        return basicInfo != null ? basicInfo.nameKana() : null;
    }

    /** @deprecated {@code getBasicInfo().nickname1()} を使用すること */
    @Deprecated
    public String getNickname1() {
        return basicInfo != null ? basicInfo.nickname1() : null;
    }

    /** @deprecated {@code getBasicInfo().nickname2()} を使用すること */
    @Deprecated
    public String getNickname2() {
        return basicInfo != null ? basicInfo.nickname2() : null;
    }

    /** @deprecated {@code getLocation().prefecture()} を使用すること */
    @Deprecated
    public String getPrefecture() {
        return location != null ? location.prefecture() : null;
    }

    /** @deprecated {@code getLocation().city()} を使用すること */
    @Deprecated
    public String getCity() {
        return location != null ? location.city() : null;
    }

    /** @deprecated {@code getLocation().template()} を使用すること */
    @Deprecated
    public String getTemplate() {
        return location != null ? location.template() : null;
    }

    /** @deprecated {@code getVisibility().visibility()} を使用すること */
    @Deprecated
    public String getVisibilityValue() {
        return visibility != null ? visibility.visibility() : null;
    }

    /** @deprecated {@code getVisibility().supporterEnabled()} を使用すること */
    @Deprecated
    public Boolean getSupporterEnabled() {
        return visibility != null ? visibility.supporterEnabled() : null;
    }

    /** @deprecated {@code getMetadata().version()} を使用すること */
    @Deprecated
    public Long getVersion() {
        return metadata != null ? metadata.version() : null;
    }

    /** @deprecated {@code getMetadata().memberCount()} を使用すること */
    @Deprecated
    public int getMemberCount() {
        return metadata != null ? metadata.memberCount() : 0;
    }

    /** @deprecated {@code getMetadata().iconUrl()} を使用すること */
    @Deprecated
    public String getIconUrl() {
        return metadata != null ? metadata.iconUrl() : null;
    }

    /** @deprecated {@code getMetadata().bannerUrl()} を使用すること */
    @Deprecated
    public String getBannerUrl() {
        return metadata != null ? metadata.bannerUrl() : null;
    }

    /** @deprecated {@code getMetadata().mapEmbedUrl()} を使用すること */
    @Deprecated
    public String getMapEmbedUrl() {
        return metadata != null ? metadata.mapEmbedUrl() : null;
    }

    /** @deprecated {@code getSocial().teamFriendCount()} を使用すること */
    @Deprecated
    public long getTeamFriendCount() {
        return social != null ? social.teamFriendCount() : 0L;
    }

    /** @deprecated {@code getSocial().supporterCount()} を使用すること */
    @Deprecated
    public long getSupporterCount() {
        return social != null ? social.supporterCount() : 0L;
    }

    /** @deprecated {@code getTimestamps().archivedAt()} を使用すること */
    @Deprecated
    public LocalDateTime getArchivedAt() {
        return timestamps != null ? timestamps.archivedAt() : null;
    }

    /** @deprecated {@code getTimestamps().createdAt()} を使用すること */
    @Deprecated
    public LocalDateTime getCreatedAt() {
        return timestamps != null ? timestamps.createdAt() : null;
    }
}
