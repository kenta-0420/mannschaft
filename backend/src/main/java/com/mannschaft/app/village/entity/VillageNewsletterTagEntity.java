package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村ニュースレタータグマスタ（F17.1 ②-1・案Y の pull 層）。
 *
 * <p>村ごとにタグを定義する。ブログの {@code BlogTagEntity} を金型に、村ドメイン内で UUIDv7 ネイティブに
 * 新設する（ブログのタグは Long キーで流用不可＝設計書 §3A.3 / §4.7.1）。Long の壁を越えない。</p>
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>原則1: {@code village_id} は村ドメイン内のため FK 可（CASCADE）。他ドメイン参照は持たない。</li>
 *   <li>原則6: 新規テーブルのため UUIDv7 を採用。</li>
 * </ul>
 */
@Entity
@Table(name = "village_newsletter_tags")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageNewsletterTagEntity extends UuidV7Entity {

    /** FK → villages.id（同一ドメイン CASCADE）。 */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 表示色 #RRGGBB（ブログ既定色に倣う）。 */
    @Column(name = "color", nullable = false, length = 7)
    private String color;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** 論理削除（原則3）。 */
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
        if (this.color == null) {
            this.color = "#6B7280";
        }
        if (this.sortOrder == null) {
            this.sortOrder = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
