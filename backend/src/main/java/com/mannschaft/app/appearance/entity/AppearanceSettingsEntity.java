package com.mannschaft.app.appearance.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * F11.4 外観テーマ設定 — ユーザーごとの外観テーマ設定エンティティ。
 *
 * <p>1ユーザー1行（{@code UNIQUE KEY uq_appearance_settings_user_id}）のupsert方式で、
 * 複数端末でテーマ設定を同期する。</p>
 *
 * <p><b>主キー方針</b>: CLAUDE.md 原則 6（2026-05-11〜）に従い
 * {@link UuidV7Entity} を継承し、{@code id BINARY(16)} で UUIDv7 を採用。</p>
 *
 * <p><b>FK 方針</b>: クロスドメイン FK 禁止原則（CLAUDE.md 原則1）に従い、
 * {@code user_id} への FK は張らない。参照整合性はアプリケーション層で保証。</p>
 */
@Entity
@Table(name = "appearance_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class AppearanceSettingsEntity extends UuidV7Entity {

    /**
     * auth ドメインの users.id（FK は張らない・クロスドメイン FK 禁止原則準拠）。
     * UNIQUE KEY uq_appearance_settings_user_id により 1 ユーザー 1 行を保証。
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** テーマモード（LIGHT / DARK）。デフォルト LIGHT。 */
    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "theme", nullable = false, length = 8)
    private ThemeMode theme;

    /** 背景色（HEX カラーコード、例: {@code #f3efe0}）。ライトモード用。 */
    @Setter
    @Column(name = "bg_color", nullable = false, length = 32)
    private String bgColor;

    /** ダークモード用背景色（HEX カラーコード、例: {@code #18181b}）。 */
    @Setter
    @Column(name = "dark_bg_color", nullable = false, length = 32)
    private String darkBgColor;

    /** 季節テーマ ID（任意・null 許容）。seasonal_themes テーブルへの参照（FK なし）。 */
    @Setter
    @Column(name = "seasonal_theme_id")
    private Long seasonalThemeId;

    /** チャットプレビューを非表示にするか。デフォルト false。 */
    @Setter
    @Column(name = "hide_chat_preview", nullable = false)
    private boolean hideChatPreview;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
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
}
