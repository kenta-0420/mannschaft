package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 村（Village）本体エンティティ（F17.1 Phase 1）。
 *
 * <p>「袖振り合うも他生の縁」をコンセプトに、組織・チーム・個人の垣根を越えた
 * 横断コミュニティを表す。{@code deleted_at}（村長判断の論理削除）と
 * {@code archived_at}（運営判断による永久凍結）を独立に持つ二段構え。</p>
 *
 * <p>原則7 適用外: 全テナント横断ドメインゆえ {@code organization_id} を持たず、
 * Repository も標準 {@link org.springframework.data.jpa.repository.JpaRepository} を継承する。</p>
 */
@Entity
@Table(name = "villages")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageEntity extends UuidV7Entity {

    @Column(name = "slug", nullable = false, length = 64)
    private String slug;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private VillageType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "join_policy", nullable = false, length = 20)
    private VillageJoinPolicy joinPolicy;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private VillageVisibility visibility;

    /**
     * 掲示板の公開範囲（F17.1 村掲示板グローバル方式）。
     * {@link #visibility}（検索可否）とは独立した概念。PUBLIC=非メンバーも閲覧可 / MEMBERS_ONLY=村メンバーのみ。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "bulletin_visibility", nullable = false, length = 20)
    private VillageBulletinVisibility bulletinVisibility;

    @Column(name = "category", length = 64)
    private String category;

    @Column(name = "icon_r2_key", length = 255)
    private String iconR2Key;

    @Column(name = "cover_r2_key", length = 255)
    private String coverR2Key;

    /** Phase 2: 村紋 R2 キー */
    @Column(name = "monsho_r2_key", length = 255)
    private String monshoR2Key;

    @Column(name = "guideline_md", columnDefinition = "MEDIUMTEXT")
    private String guidelineMd;

    @Column(name = "member_count_cache", nullable = false)
    private Long memberCountCache;

    /** 作成者 ユーザーID（FK 張らない／原則1） */
    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 村長判断による論理削除 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** 運営判断による永久凍結（ガイドライン違反等） */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.memberCountCache == null) {
            this.memberCountCache = 0L;
        }
        if (this.bulletinVisibility == null) {
            this.bulletinVisibility = VillageBulletinVisibility.MEMBERS_ONLY;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
