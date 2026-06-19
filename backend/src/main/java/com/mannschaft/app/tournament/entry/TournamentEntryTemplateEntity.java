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
 * エントリーテンプレートエンティティ。
 *
 * <p>F08.7 Phase 9-B: tournament_entry_templates テーブルに対応する。
 * チームごとに最大5件のエントリーテンプレートを管理する。
 * 論理削除（deleted_at）対応。</p>
 */
@Entity
@Table(name = "tournament_entry_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
public class TournamentEntryTemplateEntity extends UuidV7Entity {

    /** チームID（クロスドメインFK禁止のためインデックスのみ） */
    @Column(nullable = false)
    private Long teamId;

    /** テンプレート名（最大50文字） */
    @Column(nullable = false, length = 50)
    private String name;

    /** テンプレート説明（最大200文字、nullable） */
    @Column(length = 200)
    private String description;

    /** 並び順 */
    @Column(nullable = false)
    @Builder.Default
    private Short sortOrder = 0;

    /** 論理削除日時（nullの場合は有効） */
    private LocalDateTime deletedAt;

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
     * テンプレート情報を更新する。
     */
    public void update(String name, String description, Short sortOrder) {
        this.name = name;
        this.description = description;
        this.sortOrder = sortOrder;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
