package com.mannschaft.app.team.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * チームマスターエンティティ。チームの基本情報・公開設定を管理する。
 */
@Entity
@Table(name = "teams")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TeamEntity extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String nameKana;

    @Column(length = 50)
    private String nickname1;

    @Column(length = 50)
    private String nickname2;

    @Column(length = 30)
    private String template;

    @Column(length = 20)
    private String prefecture;

    @Column(length = 50)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Visibility visibility;

    @Column(nullable = false)
    private Boolean supporterEnabled;

    @Version
    private Long version;

    private LocalDateTime archivedAt;

    private LocalDateTime deletedAt;

    // --- F01.2 拡張プロフィールフィールド ---

    @Column(name = "icon_url", length = 500)
    private String iconUrl;

    @Column(name = "banner_url", length = 500)
    private String bannerUrl;

    @Column(length = 512)
    private String homepageUrl;

    private LocalDate establishedDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private com.mannschaft.app.organization.EstablishedDatePrecision establishedDatePrecision;

    @Column(columnDefinition = "TEXT")
    private String philosophy;

    @Convert(converter = com.mannschaft.app.organization.ProfileVisibilityConverter.class)
    @Column(columnDefinition = "JSON")
    private com.mannschaft.app.organization.ProfileVisibility profileVisibility;

    // --- F15.4 Phase 5-β: 店舗詳細ページの地図表示用 ---

    /**
     * Google Maps 埋め込み URL。未ログイン公開店舗詳細ページで iframe 表示する。
     * フォーマット例: {@code https://www.google.com/maps/embed?pb=...}
     * バリデーション（^https://www\.google\.com/maps/embed\?で始まる）は Application 層で実施。
     * 設計書: docs/features/F15.4_phase5_team_public_detail.md §5
     */
    @Column(name = "map_embed_url", length = 2048)
    private String mapEmbedUrl;

    // --- F15.4 Phase 4: メンバー数事前集計 ---

    /**
     * アクティブメンバー数の事前集計値。
     * リスナー（足軽16）で同期更新し、夜次バッチ（足軽17）で誤差補正する。
     * 設計書: docs/features/F15.4_team_store_search_within_org.md §3.3 / §11.4
     */
    @Column(name = "member_count", nullable = false)
    @Builder.Default
    private Long memberCount = 0L;

    /**
     * チーム公開範囲
     */
    public enum Visibility {
        PUBLIC,
        ORGANIZATION_ONLY,
        PRIVATE
    }

    /**
     * チームをアーカイブする。
     */
    public void archive() {
        this.archivedAt = LocalDateTime.now();
    }

    /**
     * チームのアーカイブを解除する。
     */
    public void unarchive() {
        this.archivedAt = null;
    }

    /**
     * チームを論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * チームの論理削除を取り消す。
     */
    public void restore() {
        this.deletedAt = null;
    }

    /**
     * アイコン画像URLを更新する。
     */
    public void updateIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    /**
     * バナー画像URLを更新する。
     */
    public void updateBannerUrl(String bannerUrl) {
        this.bannerUrl = bannerUrl;
    }
}
