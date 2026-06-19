package com.mannschaft.app.match.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.match.domain.TeamSide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F08.10 出場時間（match ドメイン内・01 §B.3）。
 *
 * <p>UUIDv7（{@link UuidV7Entity} 継承）。<b>organization_id / deleted_at は持たない</b>
 * （テナント分離は親 matches・二段アクセス・01 §A.4）。1 選手 1 行のサマリで、
 * {@code computedMinutes} は全 in/out 区間の合計（再出場対応）。クロスドメイン参照は ID のみ。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.3</p>
 */
@Entity
@Table(name = "player_appearances")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class PlayerAppearanceEntity extends UuidV7Entity {

    /** matches(id)（同一ドメイン・DB 上 FK CASCADE）。ID のみ保持。 */
    @Column(name = "match_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID matchId;

    /** 選手（user ドメイン ID 参照・未登録は NULL・FK なし） */
    @Column(name = "player_user_id")
    private Long playerUserId;

    @Column(name = "player_name", length = 128)
    private String playerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "team_side", nullable = false, length = 16)
    private TeamSide teamSide;

    @Column(name = "is_starter", nullable = false)
    private boolean starter;

    /** ポジション（器は競技非依存・語彙は競技別＝サッカーは GK/DF/MF/FW 等） */
    @Column(name = "position", length = 30)
    private String position;

    /** 背番号（未登録選手の同一性キーの一部） */
    @Column(name = "jersey_number")
    private Integer jerseyNumber;

    /** 最初の出場開始分（STARTER=0 / 初回 SUB_IN・代表値） */
    @Column(name = "first_in_minute")
    private Integer firstInMinute;

    /** 最後の退場分（代表値） */
    @Column(name = "last_out_minute")
    private Integer lastOutMinute;

    /** 自動算出出場分＝全 in/out 区間の合計（再出場対応） */
    @Column(name = "computed_minutes")
    private Integer computedMinutes;

    /** 自チーム編集権限の判定（team ドメイン ID 参照・FK なし） */
    @Column(name = "owning_team_id", nullable = false)
    private Long owningTeamId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
