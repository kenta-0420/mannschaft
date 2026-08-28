package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
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
import java.util.UUID;

/**
 * 村メンバーシップエンティティ（F17.1 Phase 1）。
 *
 * <p>個人・チーム・組織が村に参加する関係を表す。{@code subject_id} は
 * USER の場合 users.id、TEAM なら teams.id、ORGANIZATION なら organizations.id を
 * 保持するが、FK は張らない（原則1）。退村は {@code leftAt} で論理削除。</p>
 */
@Entity
@Table(name = "village_memberships")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageMembershipEntity extends UuidV7Entity {

    /** FK → villages.id（同一ドメイン・CASCADE） */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20)
    private VillageSubjectType subjectType;

    /** 参加主体ID（FK 張らない／原則1） */
    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private VillageRole role;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    /** 退村日（論理削除） */
    @Column(name = "left_at")
    private LocalDateTime leftAt;

    @Column(name = "banned_at")
    private LocalDateTime bannedAt;

    @Column(name = "banned_reason", length = 500)
    private String bannedReason;

    /**
     * この村への所属を所属村一覧（GET /users/&#123;id&#125;/villages）に公開してよいか（F17.2 機能⑥）。
     * 既定は FALSE（非公開）。本人がトグルで能動的に ON にしたときだけ公開する（設計書 §9.2）。
     */
    @Column(name = "profile_public", nullable = false)
    private boolean profilePublic;

    /**
     * <b>入村のきっかけとなった村人</b>のメンバーシップID（上位概念で統一解釈する）。
     *
     * <p>この列は二つの経路から書かれ、意味が二重化している。混同しないよう明記しておく。</p>
     * <ul>
     *   <li>参加申請（{@code VillageJoinRequestService}）経由での入村 → <b>承認した</b>村長・長老</li>
     *   <li>招待（{@code VillageInvitationService}）経由での入村 → 招待を<b>発行した</b>村長・長老</li>
     * </ul>
     *
     * <p>「承認者」という狭い名前で解釈すると招待経由の行が説明できず、逆に招待専用の列を
     * 新設すると「誰の縁で入ったか」を辿る処理が二箇所に割れる。よって<b>「入村のきっかけと
     * なった村人」という上位概念で一本化</b>し、どちらの経路でもこの列だけを見ればよい形に保つ。
     * 経路を区別したい場合は招待テーブル側（{@code village_invitations}）を突き合わせること。</p>
     */
    @Column(name = "invited_by_membership_id", columnDefinition = "BINARY(16)")
    private UUID invitedByMembershipId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
        if (this.joinedAt == null) {
            this.joinedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
