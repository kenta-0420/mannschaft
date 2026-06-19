package com.mannschaft.app.advertising.campaign.entity;

import com.mannschaft.app.advertising.campaign.enums.AdNgWordSeverity;
import com.mannschaft.app.common.entity.UuidV7Entity;
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

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * F09.17 Phase 11-b 自動 NG 辞書エントリ。
 *
 * <p>{@code AdContentModerator} が submit 時に {@code body_markdown} を本テーブルの
 * {@code word} 群と照合し、検出時に {@code moderation_status} を遷移させる。</p>
 *
 * <ul>
 *   <li>{@code severity=BLOCK}: 検出時に自動 BLOCKED</li>
 *   <li>{@code severity=WARN}: 検出時に AUTO_FLAGGED (SYSTEM_ADMIN 手動審査待ち)</li>
 * </ul>
 *
 * <p>Serializable を実装するのは Redis キャッシュ (RedisConfig) で
 * JSON シリアライズされるため。</p>
 */
@Entity
@Table(name = "ad_ng_words")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class AdNgWord extends UuidV7Entity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "word", nullable = false, length = 100, unique = true)
    private String word;

    /** PHARMA / SUPERLATIVE / FINANCIAL_RISK / DISCRIMINATION / OTHER */
    @Column(name = "category", nullable = false, length = 50)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private AdNgWordSeverity severity;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    /** SYSTEM_ADMIN user_id (クロスドメイン参照・FK なし)。シードでは NULL。 */
    @Column(name = "created_by_user_id")
    private Long createdByUserId;

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
        if (this.isActive == null) {
            this.isActive = Boolean.TRUE;
        }
        if (this.severity == null) {
            this.severity = AdNgWordSeverity.WARN;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
