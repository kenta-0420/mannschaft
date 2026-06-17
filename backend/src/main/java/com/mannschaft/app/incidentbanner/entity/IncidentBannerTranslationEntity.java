package com.mannschaft.app.incidentbanner.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 障害告知バナー翻訳エンティティ。
 *
 * <p>各バナーの多言語メッセージを管理する。
 * banner_id → incident_banners への FK は同一ドメイン内の親子関係であるため
 * ON DELETE CASCADE を許可している（アーキテクチャ原則2）。</p>
 *
 * <p>（banner_id, language）の組み合わせは UNIQUE 制約により重複を防ぐ。</p>
 */
@Entity
@Table(name = "incident_banner_translations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class IncidentBannerTranslationEntity extends UuidV7Entity {

    /**
     * 親バナーのID（incident_banners.id・同一ドメイン内 FK）。
     */
    @Column(name = "banner_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID bannerId;

    /**
     * 言語コード（例: "ja", "en", "zh", "ko", "es", "de"）。
     */
    @Column(name = "language", nullable = false, length = 10)
    private String language;

    /**
     * 翻訳後のバナーメッセージ本文。
     */
    @Column(name = "message", nullable = false, length = 500)
    private String message;

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
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * メッセージ本文を更新する。
     *
     * @param message 更新後のメッセージ
     */
    public void updateMessage(String message) {
        this.message = message;
    }
}
