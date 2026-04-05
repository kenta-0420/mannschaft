package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Google Calendar OAuth連携情報エンティティ。
 */
@Entity
@Table(name = "user_google_calendar_connections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class UserGoogleCalendarConnectionEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String googleAccountEmail;

    @Column(nullable = false)
    @Builder.Default
    private String googleCalendarId = "primary";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String accessToken;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String refreshToken;

    @Column(nullable = false)
    private LocalDateTime tokenExpiresAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean personalSyncEnabled = false;

    @Column(length = 30)
    private String lastSyncErrorType;

    @Column(columnDefinition = "TEXT")
    private String lastSyncErrorMessage;

    private LocalDateTime lastSyncErrorAt;

    @Column(nullable = false)
    @Builder.Default
    private Integer encryptionKeyVersion = 1;

    /**
     * 連携を無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * 連携を有効化する。
     */
    public void activate() {
        this.isActive = true;
    }

    /**
     * OAuthトークンを更新する。
     *
     * @param accessToken  新しいアクセストークン
     * @param refreshToken 新しいリフレッシュトークン
     * @param expiresAt    新しい有効期限
     */
    public void updateTokens(String accessToken, String refreshToken, LocalDateTime expiresAt) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.tokenExpiresAt = expiresAt;
    }

    /**
     * 同期エラーを記録する。
     *
     * @param type    エラー種別
     * @param message エラーメッセージ
     */
    public void recordSyncError(String type, String message) {
        this.lastSyncErrorType = type;
        this.lastSyncErrorMessage = message;
        this.lastSyncErrorAt = LocalDateTime.now();
    }

    /**
     * 同期エラーをクリアする。
     */
    public void clearSyncError() {
        this.lastSyncErrorType = null;
        this.lastSyncErrorMessage = null;
        this.lastSyncErrorAt = null;
    }

    /**
     * 個人スケジュール同期を有効化する。
     */
    public void enablePersonalSync() {
        this.personalSyncEnabled = true;
    }

    /**
     * 個人スケジュール同期を無効化する。
     */
    public void disablePersonalSync() {
        this.personalSyncEnabled = false;
    }
}
