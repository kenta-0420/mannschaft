package com.mannschaft.app.pointcard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * ポイントカードウォレットのユーザー設定（オプトイン状態・規約同意・WebAuthn 要求）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §5.5
 *
 * <p>1 ユーザー 1 行で {@code users.id} と 1:1 対応する設定テーブル。
 * CLAUDE.md 原則 6 のマスタ／シングルトン例外区分（PK 自然キー）に該当するため、
 * UUIDv7 主キーではなく {@code user_id} を PK 兼 FK として採用する。
 * シャーディング時は user_id と同じシャードに乗る前提。
 */
@Entity
@Table(name = "point_card_user_settings")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class PointCardUserSettingsEntity {

    /** PK 兼 FK（→ users.id ON DELETE CASCADE）。 */
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 機能の有効化（オプトイン）。 */
    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = Boolean.FALSE;

    /** 規約同意日時。 */
    @Column(name = "terms_accepted_at")
    private OffsetDateTime termsAcceptedAt;

    /** 同意した規約のバージョン（例: v1.0.0）。 */
    @Column(name = "terms_version", length = 20)
    private String termsVersion;

    /** 提示モード起動前に WebAuthn 再認証を要求するか。 */
    @Column(name = "require_biometric_on_show", nullable = false)
    @Builder.Default
    private Boolean requireBiometricOnShow = Boolean.FALSE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.enabled == null) {
            this.enabled = Boolean.FALSE;
        }
        if (this.requireBiometricOnShow == null) {
            this.requireBiometricOnShow = Boolean.FALSE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
