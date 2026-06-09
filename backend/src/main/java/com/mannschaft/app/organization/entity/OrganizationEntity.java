package com.mannschaft.app.organization.entity;

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
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 組織マスターエンティティ。組織の基本情報・公開設定・階層構造を管理する。
 */
@Entity
@Table(name = "organizations")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class OrganizationEntity extends BaseEntity {

    /**
     * URL 公開用 UUID（列挙攻撃対策）。
     * <p>内部 BIGINT PK は FK 関係のために保持し、URL には本フィールドを使用する。
     * {@code @UuidGenerator(style = TIME)} により UUIDv7（時刻順ソート可能）が自動生成される。</p>
     */
    @Column(name = "public_id", columnDefinition = "BINARY(16)", nullable = false, updatable = false, unique = true)
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID publicId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String nameKana;

    @Column(length = 50)
    private String nickname1;

    @Column(length = 50)
    private String nickname2;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrgType orgType;

    private Long parentOrganizationId;

    @Column(length = 20)
    private String prefecture;

    @Column(length = 50)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Visibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HierarchyVisibility hierarchyVisibility;

    @Column(nullable = false)
    private Boolean supporterEnabled;

    @Version
    private Long version;

    private LocalDateTime archivedAt;

    private LocalDateTime deletedAt;

    // --- F01.2 拡張プロフィールフィールド ---

    @Column(name = "icon_url", length = 512)
    private String iconUrl;

    @Column(name = "banner_url", length = 512)
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

    // --- F19.1 Phase 1 Foundation: 公開ページ氏名開示制御 ---

    /**
     * サポーター向け氏名表示モード。
     * <p>{@code DISPLAY_NAME}（既定）または {@code REAL_NAME}。Phase 1 ではカラム追加のみで
     * 機能活性化は Phase 2 の IdentityVisibilityResolver 実装時に行う。</p>
     * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §5.1 / §7.2</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "supporter_name_disclosure", nullable = false,
            columnDefinition = "ENUM('DISPLAY_NAME','REAL_NAME') NOT NULL DEFAULT 'DISPLAY_NAME'")
    @Builder.Default
    private com.mannschaft.app.publicview.enums.NameDisclosureMode supporterNameDisclosure =
            com.mannschaft.app.publicview.enums.NameDisclosureMode.DISPLAY_NAME;

    /**
     * Google Maps 等の埋め込み URL。
     * <p>F19.1 Phase 1 で organizations の公開ページ iframe 表示に使用する。
     * バリデーション（{@code ^https://www\.google\.com/maps/embed\?} で始まる）は
     * Application 層で実施する。teams 側の同等カラムは F15.4 Phase 5-β V9.160 で先行導入済。</p>
     * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §5.1</p>
     */
    @Column(name = "map_embed_url", length = 2048)
    private String mapEmbedUrl;

    /** F19.1 Phase 7: イベントを公開ページに表示するか。 */
    @Column(name = "public_events_enabled", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    @Builder.Default
    private boolean publicEventsEnabled = false;

    /** F19.1 Phase 7: タイムライン投稿を公開ページに表示するか。 */
    @Column(name = "timeline_posts_public", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    @Builder.Default
    private boolean timelinePostsPublic = false;

    /**
     * 組織種別
     */
    public enum OrgType {
        GOVERNMENT,    // 行政・官公庁
        MUNICIPALITY,  // 自治体（市区町村）
        COMPANY,       // 会社・企業
        HOSPITAL,      // 病院・医療機関
        ASSOCIATION,   // 協会・連盟
        SCHOOL,        // 学校・教育機関
        NPO,           // NPO・非営利団体
        COMMUNITY,     // コミュニティ
        OTHER          // その他
    }

    /**
     * 公開範囲
     */
    public enum Visibility {
        PUBLIC,
        PRIVATE
    }

    /**
     * 階層公開範囲
     */
    public enum HierarchyVisibility {
        NONE,
        BASIC,
        FULL
    }

    /**
     * 組織をアーカイブする。
     */
    public void archive() {
        this.archivedAt = LocalDateTime.now();
    }

    /**
     * 組織のアーカイブを解除する。
     */
    public void unarchive() {
        this.archivedAt = null;
    }

    /**
     * 組織を論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 組織の論理削除を取り消す。
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

    /** F19.1 Phase 7: イベント公開設定を更新する。 */
    public void updatePublicEventsEnabled(boolean enabled) {
        this.publicEventsEnabled = enabled;
    }

    /** F19.1 Phase 7: タイムライン公開設定を更新する。 */
    public void updateTimelinePostsPublic(boolean enabled) {
        this.timelinePostsPublic = enabled;
    }
}
