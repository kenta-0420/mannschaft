package com.mannschaft.app.reflection.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.reflection.ReflectionVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 日々の振り返りエントリ（F06.5・§2.2）。
 *
 * <p>(theme_id, target_date) 一意で「1テーマ×1日＝1エントリ」を保証する。
 * theme_id は同一 reflection ドメインのため FK＋CASCADE があるが、user_id /
 * exported_blog_post_id は他ドメイン参照のため FK を張らず ID のみ保持する（原則1）。</p>
 *
 * <p><b>楽観ロック（@Version）</b>: PUT upsert は expectedVersion 不一致で 409（AC-18）。</p>
 *
 * <p><b>更新は {@link #applyUpdate} の直接ミューテートで行う</b>（toBuilder 回避・id 欠落 INSERT 化バグ防止）。</p>
 */
@Entity
@Table(name = "reflection_entries")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ReflectionEntryEntity extends UuidV7Entity {

    @Column(name = "theme_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID themeId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Column(name = "structured_content", nullable = false, columnDefinition = "JSON")
    private String structuredContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    @Builder.Default
    private ReflectionVisibility visibility = ReflectionVisibility.PRIVATE;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(name = "exported_blog_post_id")
    private Long exportedBlogPostId;

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
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 振り返り本文を更新する（直接ミューテート・toBuilder 回避）。
     *
     * @param structuredContent 新しいアウトライン構造（サニタイズ済みを渡すこと）
     */
    public void applyUpdate(String structuredContent) {
        if (structuredContent != null) {
            this.structuredContent = structuredContent;
        }
    }

    /**
     * 論理削除済みエントリを復活させる（deleted_at=NULL）。同日同テーマでの再作成時に使う（§2.2）。
     *
     * @param structuredContent 復活後の本文
     */
    public void restoreWith(String structuredContent) {
        this.deletedAt = null;
        if (structuredContent != null) {
            this.structuredContent = structuredContent;
        }
    }

    /**
     * ブログ輸出済みであることを記録する（再輸出ブロック・輸出済み表示・AC-20）。
     */
    public void markExported(Long blogPostId) {
        this.exportedBlogPostId = blogPostId;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
