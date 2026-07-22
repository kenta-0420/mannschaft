package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.village.entity.enums.VillageEventArchiveSourceType;
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
 * 村史（行事アーカイブ）エンティティ（F17.2 Wave2 ⑦村史・設計書 §7.2）。
 *
 * <p>祭／歳時記／寄合の記録を、{@code source_type}＋{@code source_id}＋{@code title}＋
 * {@code summary}＋{@code archived_at} の共通形へ正規化した集約テーブル（案I採用・設計書 §7.2）。
 * 編纂時に summary を焼き付ける「確定した記録（スナップショット）」であり、元行事が後日
 * 削除・変更されても村史はぶれない（③の祭編纂が RSVP 集計を焼き付けるのと同思想）。</p>
 *
 * <p>{@code (source_type, source_id)} の UNIQUE 制約で「1行事=1村史エントリ」を保証し、
 * 二重編纂を冪等に防ぐ（設計書 §5.5・§7.2）。{@code village_id}／{@code source_id} は
 * いずれも FK非付与の ID 参照のみ（原則1）。論理削除（{@code deleted_at}）で原則3 に準拠。</p>
 */
@Entity
@Table(name = "village_event_archives")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageEventArchiveEntity extends UuidV7Entity {

    /** 村スコープ（FK非付与・原則1） */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    /** 元行事の種別（FESTIVAL / CALENDAR_EVENT / MEETUP） */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private VillageEventArchiveSourceType sourceType;

    /** 元行事の UUID（ID参照のみ・FK非付与・原則1） */
    @Column(name = "source_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID sourceId;

    /** 編纂時に焼き付けた表題 */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** 編纂サマリ（RSVP集計・実況件数等をテキスト化） */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    /** 代表画像（祭バナー等の複写・任意・R2キー） */
    @Column(name = "thumbnail_r2_key", length = 255)
    private String thumbnailR2Key;

    /** 編纂時刻 */
    @Column(name = "archived_at", nullable = false)
    private LocalDateTime archivedAt;

    /** 論理削除（原則3） */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

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
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
