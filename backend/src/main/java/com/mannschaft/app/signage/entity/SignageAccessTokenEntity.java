package com.mannschaft.app.signage.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * デジタルサイネージ アクセストークンエンティティ。
 * ON DELETE CASCADE により、親画面削除時に物理削除される。
 */
@Entity
@Table(name = "signage_access_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
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

    /** トークン有効期限。NULL は無期限。 */
    private LocalDateTime expiredAt;

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
     * 指定時刻において有効期限が満了しているかを判定する。
     *
     * <p>{@code expiredAt} が NULL のトークンは無期限であり、常に {@code false} を返す。
     * 期限に到達した瞬間（{@code now == expiredAt}）は満了として扱う。</p>
     *
     * @param now 判定基準時刻
     * @return 有効期限が満了していれば true
     */
    public boolean isExpired(LocalDateTime now) {
        return expiredAt != null && !now.isBefore(expiredAt);
    }

    /**
     * 最終アクセス情報を記録する。
     */
    public void recordAccess(String ipAddress) {
        this.lastAccessedAt = LocalDateTime.now();
        this.lastAccessedIp = ipAddress;
    }
}
