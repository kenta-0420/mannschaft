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

    /**
     * URL 公開用カスタムスラッグ（人間可読な識別子）。
     * <p>3〜30文字の英数字ハイフン。チーム名から自動生成し、一意性は uq_teams_slug で担保する。
     * 内部 BIGINT PK は FK 関係のために保持し、URL には本フィールドを使用する。</p>
     */
    @Column(name = "slug", length = 30, nullable = false, unique = true)
    private String slug;

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

    // --- F22.1 市 Phase 2 足場C: 地域コード（構造化フィルタ・市ビュー結合用） ---

    /**
     * 都道府県コード（JIS X 0401・{@code prefectures.code} 参照）。
     * <p>自由入力の {@link #prefecture} とは別に保持する構造化フィルタ用キー。
     * 第一陣ではカラム追加のみで、名称→コードのバックフィルは別工程（ドライラン基盤参照）。
     * 設計書: docs/features/F22.1_market / CLAUDE.md 原則 1（FKなし）</p>
     */
    @Column(name = "prefecture_code", length = 2)
    private String prefectureCode;

    /**
     * 市区町村コード（JIS X 0402・{@code cities.code} 参照）。
     * <p>自由入力の {@link #city} とは別に保持する構造化フィルタ用キー。FKなし。</p>
     */
    @Column(name = "city_code", length = 5)
    private String cityCode;

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
     * 公開ページでチームイベント一覧を表示するか。
     * <p>Phase 1 ではカラム追加のみで機能活性化は Phase 4 の
     * PublicTeamEventQueryService 実装時に行う。組織イベントは常時公開のため
     * organizations 側に対応カラムは存在しない。</p>
     * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §5.1</p>
     */
    @Column(name = "public_events_enabled", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    @Builder.Default
    private Boolean publicEventsEnabled = Boolean.FALSE;

    /** F19.1 Phase 7: タイムライン投稿を公開ページに表示するか。 */
    @Column(name = "timeline_posts_public", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    @Builder.Default
    private boolean timelinePostsPublic = false;

    /**
     * チーム公開範囲（ロールベース設計）。
     *
     * <p>旧設計（PRIVATE / ORGANIZATION_ONLY）は組織概念に依存していた。
     * 新設計ではチーム内のロール（メンバー/サポーター/ゲスト/パブリック）で分ける。</p>
     *
     * <p>F00 StandardVisibility マッピング:
     * <ul>
     *   <li>{@link #PUBLIC} → {@link com.mannschaft.app.common.visibility.StandardVisibility#PUBLIC}</li>
     *   <li>{@link #GUESTS_AND_ABOVE} → {@link com.mannschaft.app.common.visibility.StandardVisibility#SCOPE_AFFILIATED}</li>
     *   <li>{@link #SUPPORTERS_AND_ABOVE} → {@link com.mannschaft.app.common.visibility.StandardVisibility#SUPPORTERS_AND_ABOVE}</li>
     *   <li>{@link #MEMBERS_AND_ABOVE} → {@link com.mannschaft.app.common.visibility.StandardVisibility#MEMBERS_AND_ABOVE}</li>
     * </ul>
     * </p>
     */
    public enum Visibility {
        PUBLIC,
        GUESTS_AND_ABOVE,
        SUPPORTERS_AND_ABOVE,
        MEMBERS_AND_ABOVE
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

    /** F19.1 Phase 7: タイムライン公開設定を更新する。 */
    public void updateTimelinePostsPublic(boolean enabled) {
        this.timelinePostsPublic = enabled;
    }

    /** F19.1 Phase 7: イベント公開設定を更新する。 */
    public void updatePublicEventsEnabled(boolean enabled) {
        this.publicEventsEnabled = enabled;
    }

    /**
     * F22.1 市 Phase 2 足場C: 地域コード（都道府県・市区町村）を設定する。
     * <p>クラスレベル {@code @Setter} を持たないため、ビジネスメソッド経由で更新する。
     * バックフィル・正規化結果の反映に使用する。どちらも null 許容（未解決＝NULL）。</p>
     *
     * @param prefectureCode 都道府県コード（JIS X 0401、null 可）
     * @param cityCode       市区町村コード（JIS X 0402、null 可）
     */
    public void updateRegionCodes(String prefectureCode, String cityCode) {
        this.prefectureCode = prefectureCode;
        this.cityCode = cityCode;
    }
}
