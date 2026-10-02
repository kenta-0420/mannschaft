package com.mannschaft.app.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * リフレッシュトークンエンティティ。JWT更新用トークンを管理する。
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class RefreshTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    /**
     * ローテーションで発行した後継リフレッシュトークンの SHA-256 ハッシュ。
     *
     * <p>非 NULL = このトークンはローテーションによって正規に失効させられた（後継が存在する）印。
     * {@link #revokedAt} からの経過が grace window 以内なら並行更新として正規化し（リプレイ扱いにしない）、
     * grace window を超過していれば真のリプレイとして扱う。
     * NULL のまま {@link #revokedAt} が設定されている場合は明示ログアウト等（後継なし revoke）で、
     * grace window の対象外とする。</p>
     */
    @Column(length = 64)
    private String replacedByTokenHash;

    @Column(nullable = false)
    private Boolean rememberMe;

    /** リフレッシュトークンの JWT ID。session_hash 計算の基点として使用する。 */
    @Column(name = "jti", nullable = false, length = 36)
    private String jti;

    @Column(length = 64)
    private String deviceFingerprint;

    @Column(length = 45)
    private String ipAddress;

    @Column(length = 500)
    private String userAgent;

    @Column(length = 100)
    private String deviceName;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime lastUsedAt;

    private LocalDateTime revokedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * トークンを無効化する。
     */
    public void revoke() {
        this.revokedAt = LocalDateTime.now();
    }

    /**
     * ローテーションによりトークンを失効させ、後継トークンのハッシュを記録する。
     *
     * <p>{@link #revoke()} 相当の失効に加えて {@link #replacedByTokenHash} を設定することで、
     * 「このトークンはリプレイではなくローテーションで正規に置き換えられた」ことを表す。
     * これにより並行更新（同一トークンでの near-simultaneous な refresh）を
     * grace window 内なら正規化でき、リプレイ誤判定による全セッション無効化を防ぐ。</p>
     *
     * @param successorTokenHash 後継リフレッシュトークンの SHA-256 ハッシュ
     */
    public void markRotated(String successorTokenHash) {
        this.revokedAt = LocalDateTime.now();
        this.replacedByTokenHash = successorTokenHash;
    }

    /**
     * 最終使用日時を更新する。
     */
    public void updateLastUsedAt() {
        this.lastUsedAt = LocalDateTime.now();
    }

    /**
     * デバイス名を更新する。
     */
    public void updateDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }
}
