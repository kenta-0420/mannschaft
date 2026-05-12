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
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * エントリーテンプレートメンバーエンティティ。
 *
 * <p>F08.7 Phase 9-B: tournament_entry_template_members テーブルに対応する。
 * テンプレートに含まれるメンバーの詳細情報を管理する。</p>
 */
@Entity
@Table(name = "tournament_entry_template_members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TournamentEntryTemplateMemberEntity extends UuidV7Entity {

    /** テンプレートID（tournament_entry_templates.id、UUIDv7） */
    @Column(nullable = false, columnDefinition = "BINARY(16)")
    private UUID templateId;

    /** ユーザーID（クロスドメインFK禁止のためインデックスのみ） */
    @Column(nullable = false)
    private Long userId;

    /** 背番号（nullable） */
    private Integer jerseyNumber;

    /** ポジション（nullable） */
    @Column(length = 50)
    private String position;

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
     * テンプレートメンバー情報を更新する。
     */
    public void update(Integer jerseyNumber, String position, Short sortOrder) {
        this.jerseyNumber = jerseyNumber;
        this.position = position;
        this.sortOrder = sortOrder;
        this.updatedAt = LocalDateTime.now();
    }
}
