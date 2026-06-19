package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
 * 村史（月次ダイジェスト）エンティティ（F17.1 Phase 3-β）。
 *
 * <p>月次バッチで生成される村単位の活動サマリ。LLM は使用せず、
 * 投稿数・新メンバー数・TOP3 トピックの統計のみを保持する。</p>
 *
 * <p>同一村 × 同一年月は 1 行のみ（UNIQUE 制約）。再生成は UPSERT で同一行を更新する。</p>
 */
@Entity
@Table(name = "village_chronicles")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageChronicleEntity extends UuidV7Entity {

    /** FK → villages.id（同一ドメイン CASCADE） */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    /** 対象年月の 1 日（例: 2026-05-01）。UTC 基準。 */
    @Column(name = "`year_month`", nullable = false)
    private LocalDate yearMonth;

    /** 本レコード生成時刻。 */
    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    /** 当月の投稿数（bulletin_threads + timeline_posts の VILLAGE スコープ）。 */
    @Column(name = "post_count", nullable = false)
    private Integer postCount;

    /** 当月新規参加メンバー数。 */
    @Column(name = "new_member_count", nullable = false)
    private Integer newMemberCount;

    @Column(name = "topic_1_name", length = 100)
    private String topic1Name;

    @Column(name = "topic_1_count", nullable = false)
    private Integer topic1Count;

    @Column(name = "topic_2_name", length = 100)
    private String topic2Name;

    @Column(name = "topic_2_count", nullable = false)
    private Integer topic2Count;

    @Column(name = "topic_3_name", length = 100)
    private String topic3Name;

    @Column(name = "topic_3_count", nullable = false)
    private Integer topic3Count;

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
        if (this.generatedAt == null) {
            this.generatedAt = now;
        }
        if (this.postCount == null) {
            this.postCount = 0;
        }
        if (this.newMemberCount == null) {
            this.newMemberCount = 0;
        }
        if (this.topic1Count == null) {
            this.topic1Count = 0;
        }
        if (this.topic2Count == null) {
            this.topic2Count = 0;
        }
        if (this.topic3Count == null) {
            this.topic3Count = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
