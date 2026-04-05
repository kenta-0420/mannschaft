package com.mannschaft.app.signage.entity;

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
 * デジタルサイネージ アクセストークンエンティティ。
 * ON DELETE CASCADE により、親画面削除時に物理削除される。
 */
@Entity
@Table(name = "signage_access_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SignageAccessTokenEntity extends BaseEntity {

    @Column(nullable = false)
    private Long screenId;

    /** UUID v4形式のトークン。 */
    @Column(nullable = false, length = 36, unique = true)
    private String token;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /** CIDR表記の許可IPリスト（JSON文字列）。NULLは全許可。 */
    @Column(columnDefinition = "JSON")
    private String allowedIps;

    private LocalDateTime lastAccessedAt;

    @Column(length = 45)
    private String lastAccessedIp;

    @Column(nullable = false)
    private Long createdBy;

    /**
     * トークンを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * 最終アクセス情報を記録する。
     */
    public void recordAccess(String ipAddress) {
        this.lastAccessedAt = LocalDateTime.now();
        this.lastAccessedIp = ipAddress;
    }
}
