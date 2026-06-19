package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 井戸端会議の日次スレッドエンティティ（F17.1 Phase 1）。
 *
 * <p>発言自体は既存 {@code chat_messages} に格納される。本エンティティは
 * 日付ごとに振り返るアーカイブビューを提供するためのインデックス。
 * {@code chatChannelId} は別ドメイン（chat）への参照ゆえ FK は張らない（原則1）。</p>
 */
@Entity
@Table(name = "village_lobby_daily_threads")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class VillageLobbyDailyThreadEntity extends UuidV7Entity {

    /** FK → villages.id（同一ドメイン・CASCADE） */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    @Column(name = "thread_date", nullable = false)
    private LocalDate threadDate;

    /** 対応するチャットチャンネル（FK 張らない／原則1） */
    @Column(name = "chat_channel_id", nullable = false)
    private Long chatChannelId;

    /** AI による日次サマリ（Phase 2 以降） */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "message_count_cache", nullable = false)
    private Long messageCountCache;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.messageCountCache == null) {
            this.messageCountCache = 0L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
