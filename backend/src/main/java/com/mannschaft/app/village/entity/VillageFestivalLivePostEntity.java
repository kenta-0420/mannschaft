package com.mannschaft.app.village.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * お祭りの実況投稿の紐付けエンティティ（F17.2 Wave2 ③お祭りの参加レイヤー・設計書 §5.4）。
 *
 * <p>ACTIVE 期間中に村人が投稿した VILLAGE タイムライン投稿のうち、「この祭の実況として投稿」
 * タグを付けたものだけを、村ドメイン側の本中間テーブルへ記録する。timeline 本体は無改造とし、
 * 紐付けを村ドメインに閉じる（案B採用・設計書 §5.4）。</p>
 *
 * <p><b>原則6の例外（自然キー）</b>: 本エンティティは {@link com.mannschaft.app.common.entity.UuidV7Entity}
 * を継承せず、複合自然キー {@code (festival_id, timeline_post_id)} を主キーとする。理由は、
 * 独立発番の代理キーを必要とせず「参照2本の組」が一意で足りるため（設計書 §5.4・§13.1）。
 * 参照2本はいずれも FK を張らず ID 参照のみ（{@code festival_id} は同一 village ドメイン、
 * {@code timeline_post_id} は別ドメイン timeline の BIGINT PK・原則1）。</p>
 */
@Entity
@Table(name = "village_festival_live_posts")
@IdClass(VillageFestivalLivePostEntity.VillageFestivalLivePostId.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode
public class VillageFestivalLivePostEntity {

    /** → village_festivals.id（同一ドメイン・FK非付与・原則1） */
    @Id
    @Column(name = "festival_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID festivalId;

    /** → timeline_posts.id（別ドメイン timeline・BIGINT・ID参照のみ・FK非付与・原則1） */
    @Id
    @Column(name = "timeline_post_id", nullable = false)
    private Long timelinePostId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /**
     * 複合主キークラス（{@code festival_id} + {@code timeline_post_id}）。
     *
     * <p>{@link IdClass} 用。フィールド名・型は本エンティティの {@link Id} フィールドと一致させる。</p>
     */
    @Getter
    @Setter
    @NoArgsConstructor(access = AccessLevel.PUBLIC)
    @EqualsAndHashCode
    public static class VillageFestivalLivePostId implements Serializable {

        private UUID festivalId;
        private Long timelinePostId;

        public VillageFestivalLivePostId(UUID festivalId, Long timelinePostId) {
            this.festivalId = festivalId;
            this.timelinePostId = timelinePostId;
        }
    }
}
