package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.recruitment.RecruitmentFriendTargetKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F22.1 市: フレンド宛非公開札（{@code visibility='FRIEND_TEAMS_ONLY'}）の宛先。
 *
 * <p>1 つの札（{@code listing_id}）に対し、3 粒度（全体 / フォルダ / 個別チーム）の宛先を
 * 複数行で記録する。利用者は混在指定でき、配信・アクセス解決の都度 F01.5 サービスで
 * 「現在の成立フレンド集合」へ解決する（01_data_model §4 / 02_api_design §7）。</p>
 *
 * <h2>整合性</h2>
 * <ul>
 *   <li>{@code ALL_FRIENDS} → folder_id / team_id ともに NULL</li>
 *   <li>{@code FOLDER}      → folder_id 必須・team_id NULL</li>
 *   <li>{@code TEAM}        → team_id 必須・folder_id NULL</li>
 * </ul>
 * DB の CHECK 制約（{@code ck_rft_kind}）と本エンティティの生成メソッドで二重に保証する。
 *
 * <h2>FK 方針</h2>
 * <p>{@code listing_id} は同一ドメイン（recruitment）につき FK + ON DELETE CASCADE。
 * {@code folder_id}（F01.5）/ {@code team_id}（team ドメイン）はクロスドメインのため
 * FK なし・index のみ（CLAUDE.md 原則 1・2）。整合性は Service 層で検証する。</p>
 *
 * <h2>主キー</h2>
 * <p>新規テーブルにつき {@link UuidV7Entity} を継承（CLAUDE.md 原則 6）。</p>
 */
@Entity
@Table(name = "recruitment_friend_targets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class RecruitmentFriendTargetEntity extends UuidV7Entity {

    /** 札ID（recruitment_listings.id・同一ドメイン FK CASCADE）。 */
    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    /** 宛先粒度。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", nullable = false, length = 20)
    private RecruitmentFriendTargetKind targetKind;

    /** F01.5 フレンドフォルダID（{@code FOLDER} のとき必須・FK なし）。 */
    @Column(name = "folder_id")
    private Long folderId;

    /** 宛先チームID（{@code TEAM} のとき必須・FK なし）。 */
    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    // -------------------------------------------------------------------------
    // ファクトリメソッド（kind と参照列の整合を強制する）
    // -------------------------------------------------------------------------

    /**
     * 札主チームの全成立フレンド宛（ALL_FRIENDS）の宛先を生成する。
     *
     * @param listingId 札ID
     * @return ALL_FRIENDS 宛先
     */
    public static RecruitmentFriendTargetEntity ofAllFriends(Long listingId) {
        requireListingId(listingId);
        return RecruitmentFriendTargetEntity.builder()
                .listingId(listingId)
                .targetKind(RecruitmentFriendTargetKind.ALL_FRIENDS)
                .folderId(null)
                .teamId(null)
                .build();
    }

    /**
     * フレンドフォルダ単位（FOLDER）の宛先を生成する。
     *
     * @param listingId 札ID
     * @param folderId  F01.5 フレンドフォルダID（必須）
     * @return FOLDER 宛先
     */
    public static RecruitmentFriendTargetEntity ofFolder(Long listingId, Long folderId) {
        requireListingId(listingId);
        if (folderId == null) {
            throw new IllegalArgumentException("FOLDER 宛先には folder_id が必須です");
        }
        return RecruitmentFriendTargetEntity.builder()
                .listingId(listingId)
                .targetKind(RecruitmentFriendTargetKind.FOLDER)
                .folderId(folderId)
                .teamId(null)
                .build();
    }

    /**
     * 個別チーム単位（TEAM）の宛先を生成する。
     *
     * @param listingId 札ID
     * @param teamId    宛先チームID（必須）
     * @return TEAM 宛先
     */
    public static RecruitmentFriendTargetEntity ofTeam(Long listingId, Long teamId) {
        requireListingId(listingId);
        if (teamId == null) {
            throw new IllegalArgumentException("TEAM 宛先には team_id が必須です");
        }
        return RecruitmentFriendTargetEntity.builder()
                .listingId(listingId)
                .targetKind(RecruitmentFriendTargetKind.TEAM)
                .teamId(teamId)
                .folderId(null)
                .build();
    }

    private static void requireListingId(Long listingId) {
        if (listingId == null) {
            throw new IllegalArgumentException("listing_id は必須です");
        }
    }
}
