package com.mannschaft.app.role.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import com.mannschaft.app.common.token.PlaintextTokenDebt;

/**
 * 招待トークンエンティティ。チーム・組織への招待リンクを管理する。
 */
@Entity
@Table(name = "invite_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class InviteTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @PlaintextTokenDebt(cmp = "CMP-260826-1457")
    @Column(nullable = false, unique = true, length = 36)
    private String token;

    private Long teamId;

    private Long organizationId;

    @Column(nullable = false)
    private Long roleId;

    private LocalDateTime expiresAt;

    private Integer maxUses;

    /**
     * 使用回数。Entity 経由の UPDATE では書かない（{@code updatable=false}）。
     *
     * <p>外側トランザクションの古いスナップショットを持つ Entity が、失効などの全列 UPDATE で
     * 別トランザクションが進めた値を書き戻して失うことを防ぐ。値を変えてよいのは
     * 承諾の確保（claim）での加算と補償（release）での減算の native 文だけである
     * （CMP-260826-1457・陣立て書 §8.2-B/§8.3）。</p>
     */
    @Column(nullable = false, updatable = false)
    private Integer usedCount;

    private LocalDateTime revokedAt;

    /** 招待先自身の発行時閉鎖世代。組織所属先の閉鎖世代には連動しない（#3892）。 */
    @Column(name = "scope_closure_generation", nullable = false, updatable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 0")
    private long scopeClosureGeneration;

    private Long createdBy;

    /**
     * 承諾型招待の宛先ユーザー（F04.12）。
     * NULL = 従来の共有リンク型（誰でも参加可） / 非 NULL = 宛先付き承諾型（この user のみ承諾可）。
     * users は user ドメインのため FK は張らない（原則1）。
     */
    private Long targetUserId;

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
        if (this.revokedAt == null) {
            this.revokedAt = LocalDateTime.now();
        }
    }

    /**
     * トークンが有効かどうかを判定する。
     *
     * @return 有効であれば true
     */
    public boolean isValid() {
        return isValidState(this.revokedAt, this.expiresAt, this.maxUses, this.usedCount, LocalDateTime.now());
    }

    /**
     * 有効性規則を純粋関数として切り出したもの。Entity を読まない承諾の確保（ロック付き射影）と
     * {@link #isValid()} が同じ規則を共有する。{@code expiresAt}・{@code now} は同じ時刻基準で渡すこと。
     */
    public static boolean isValidState(LocalDateTime revokedAt, LocalDateTime expiresAt, Integer maxUses,
                                       int usedCount, LocalDateTime now) {
        return isValidState(revokedAt == null ? null : revokedAt.toInstant(ZoneOffset.UTC),
                expiresAt == null ? null : expiresAt.toInstant(ZoneOffset.UTC), maxUses, usedCount,
                now.toInstant(ZoneOffset.UTC));
    }

    /** {@link #isValidState(LocalDateTime, LocalDateTime, Integer, int, LocalDateTime)} の瞬間（Instant）版。 */
    public static boolean isValidState(Instant revokedAt, Instant expiresAt, Integer maxUses,
                                       int usedCount, Instant now) {
        if (revokedAt != null) {
            return false;
        }
        if (expiresAt != null && expiresAt.isBefore(now)) {
            return false;
        }
        return maxUses == null || usedCount < maxUses;
    }
}
