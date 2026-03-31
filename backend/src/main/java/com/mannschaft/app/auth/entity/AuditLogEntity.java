package com.mannschaft.app.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 監査ログエンティティ。ユーザー操作の監査証跡を記録する（イミュータブル）。
 */
@Entity
@Table(name = "audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作を行ったユーザー。システムバッチの場合は null。 */
    private Long userId;

    /** 操作の対象ユーザー。管理者操作（凍結・ロール変更等）の場合に設定。 */
    private Long targetUserId;

    /** 操作が行われたチームコンテキスト。チームスコープ外の操作は null。 */
    private Long teamId;

    /** 操作が行われた組織コンテキスト。組織スコープ外の操作は null。 */
    private Long organizationId;

    @Column(nullable = false, length = 100)
    private String eventType;

    @Column(length = 45)
    private String ipAddress;

    @Column(length = 500)
    private String userAgent;

    /** SHA-256(refresh_token_jti)。未認証イベントは null。 */
    @Column(length = 64)
    private String sessionHash;

    @Column(columnDefinition = "JSON")
    private String metadata;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
