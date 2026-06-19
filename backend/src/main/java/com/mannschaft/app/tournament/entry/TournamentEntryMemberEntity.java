package com.mannschaft.app.tournament.entry;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 大会エントリー表メンバーエンティティ。
 *
 * <p>F08.7 Phase 9: tournament_entry_members テーブルに対応する。
 * 参加チーム（tournament_participants）ごとのエントリーメンバーを管理する。</p>
 */
@Entity
@Table(name = "tournament_entry_members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
public class TournamentEntryMemberEntity extends UuidV7Entity {

    /** 参加チームID（tournament_participants.id） */
    @Column(nullable = false)
    private Long participantId;

    /** ユーザーID（クロスドメインFK禁止のためインデックスのみ） */
    @Column(nullable = false)
    private Long userId;

    /** チームメンバー番号（nullable） */
    @Column(length = 50)
    private String memberNumber;

    /** ポジション（nullable） */
    @Column(length = 50)
    private String position;

    /** 背番号（nullable） */
    private Integer jerseyNumber;

    /** 備考（nullable） */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /** 並び順 */
    @Column(nullable = false)
    @Builder.Default
    private Short sortOrder = 0;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * エントリーメンバー情報を更新する。
     */
    public void update(String position, Integer jerseyNumber, String notes, Short sortOrder) {
        this.position = position;
        this.jerseyNumber = jerseyNumber;
        this.notes = notes;
        this.sortOrder = sortOrder;
    }
}
