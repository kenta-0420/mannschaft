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
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 組織マスターエンティティ。組織の基本情報・公開設定・階層構造を管理する。
 */
@Entity
@Table(name = "organizations")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class OrganizationEntity extends BaseEntity {

    /**
     * URL 公開用カスタムスラッグ（人間可読な識別子）。
     * <p>3〜30文字の英数字ハイフン。組織名から自動生成し、一意性は uq_organizations_slug で担保する。
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

    /**
     * F01.2 §5.9.5 slug リネーム: slug を新しい値へ変更する。
     *
     * <p>クラスレベル {@code @Setter} を持たないため、ビジネスメソッド経由で更新する。
     * 形式・予約語・一意性・履歴予約の検証は Service 層（{@code OrganizationService#renameSlug}）が
     * 行う前提で、本メソッドは値の差し替えのみを担う。旧 slug の履歴記録も Service 層の責務。</p>
     *
     * @param newSlug 新しい slug（検証済み前提）
     */
    public void renameSlug(String newSlug) {
        this.slug = newSlug;
    }

    /**
     * 組織の基本情報を部分更新する（{@code OrganizationService#updateOrganization} 用）。
     *
     * <p>本メソッドは managed entity をその場でミューテートする更新メソッドである。
     * {@code @Transactional} 内で managed な本エンティティに対して呼ぶことで JPA の
     * dirty checking により UPDATE が発行される。</p>
     *
     * <p><strong>なぜ builder ({@code toBuilder().build()}) で作り直さないか:</strong>
     * {@link OrganizationEntity} は {@code @SuperBuilder(toBuilder = true)}（{@code @SuperBuilder} ではない）であり、
     * 主キー {@code id} は基底クラス {@link com.mannschaft.app.common.BaseEntity} のフィールドである。
     * {@code @SuperBuilder} は superclass のフィールドを取り込まないため、{@code toBuilder()} で
     * 作り直すと継承フィールド {@code id} が引き継がれず {@code id = null} の新インスタンスになる。
     * これを {@code save} すると UPDATE ではなく INSERT が走り、slug 一意制約違反で 500 になる。
     * よって更新は必ず managed entity の直接ミューテートで行う（PR #1643 と同型）。</p>
     *
     * <p>各引数は「リクエスト値が非 null なら採用、null なら現値を維持」の部分更新セマンティクス。
     * visibility / hierarchyVisibility の enum 解決は呼び出し側（{@code OrganizationService}）の責務とし、
     * 本メソッドは解決済みの値を受け取る。</p>
     *
     * @param name                 新組織名
     * @param nameKana             新カナ
     * @param nickname1            新ニックネーム1
     * @param nickname2            新ニックネーム2
     * @param prefecture           新都道府県
     * @param city                 新市区町村
     * @param visibility           新公開範囲（解決済み enum・null なら現値維持）
     * @param hierarchyVisibility  新階層公開範囲（解決済み enum・null なら現値維持）
     * @param supporterEnabled     新サポーター有効フラグ
     */
    public void applyUpdate(String name, String nameKana, String nickname1, String nickname2,
                            String prefecture, String city, Visibility visibility,
                            HierarchyVisibility hierarchyVisibility, Boolean supporterEnabled) {
        if (name != null) {
            this.name = name;
        }
        if (nameKana != null) {
            this.nameKana = nameKana;
        }
        if (nickname1 != null) {
            this.nickname1 = nickname1;
        }
        if (nickname2 != null) {
            this.nickname2 = nickname2;
        }
        if (prefecture != null) {
            this.prefecture = prefecture;
        }
        if (city != null) {
            this.city = city;
        }
        if (visibility != null) {
            this.visibility = visibility;
        }
        if (hierarchyVisibility != null) {
            this.hierarchyVisibility = hierarchyVisibility;
        }
        if (supporterEnabled != null) {
            this.supporterEnabled = supporterEnabled;
        }
    }

    /**
     * 組織の拡張プロフィールを更新する（{@code OrganizationExtendedProfileService#updateProfile} 用）。
     *
     * <p>{@link #applyUpdate} と同じく managed entity の直接ミューテートで UPDATE を発行する。
     * builder 作り直しによる id 欠落 INSERT を避けるために設けた更新メソッドである。</p>
     *
     * <p>本メソッドは「指定された値で上書きする」セマンティクス（null も含めて上書き可）。
     * 呼び出し側（{@code OrganizationExtendedProfileService}）が現値維持／null 化のロジックを
     * 解決済みの値として渡す前提とする。</p>
     *
     * @param homepageUrl              新ホームページ URL（正規化済み・null 化可）
     * @param establishedDate         新設立日（null 化可）
     * @param establishedDatePrecision 新設立日精度（null 化可）
     * @param philosophy              新理念（trim・null 化済み）
     * @param profileVisibility       新プロフィール公開設定
     */
    public void applyProfileUpdate(String homepageUrl, LocalDate establishedDate,
                                   com.mannschaft.app.organization.EstablishedDatePrecision establishedDatePrecision,
                                   String philosophy,
                                   com.mannschaft.app.organization.ProfileVisibility profileVisibility) {
        this.homepageUrl = homepageUrl;
        this.establishedDate = establishedDate;
        this.establishedDatePrecision = establishedDatePrecision;
        this.philosophy = philosophy;
        this.profileVisibility = profileVisibility;
    }

    /**
     * F19.1 Phase 2: サポーター向け氏名表示モードを更新する
     * （{@code SupporterNameDisclosureService#patchOrganizationDisclosure} 用）。
     *
     * <p>managed entity の直接ミューテートで UPDATE を発行する。builder 作り直しによる
     * id 欠落 INSERT を避けるために設けた更新メソッドである。</p>
     *
     * @param mode 新しい氏名表示モード
     */
    public void updateSupporterNameDisclosure(com.mannschaft.app.publicview.enums.NameDisclosureMode mode) {
        this.supporterNameDisclosure = mode;
    }
}
