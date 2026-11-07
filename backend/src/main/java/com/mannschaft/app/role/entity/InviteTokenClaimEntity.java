package com.mannschaft.app.role.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * 招待の使用回数を消費した確保記録（CMP-260826-1457・招待承諾の分離）。
 *
 * <p>承諾を「確保（claim）→ロール付与 → 確定」の短いトランザクションの連鎖に分けたため、使用回数の消費を
 * この1行と結び付け、補償（release）をちょうど1回に限る。状態遷移は {@code CLAIMED→GRANTED}
 * （確定）と {@code CLAIMED→RELEASED}（補償）のみで、いずれも native の条件付き UPDATE で行う
 * （本 Entity は INSERT 専用。状態を Entity 経由で書き換えない）。</p>
 *
 * <p>{@code userId} は auth ドメインのため DB の FK を張らない。{@code tokenId} は同ドメインの
 * {@code invite_tokens} への FK（CASCADE なし）。</p>
 */
@Entity
@Table(name = "invite_token_claims")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class InviteTokenClaimEntity extends UuidV7Entity {

    public static final String CLAIMED = "CLAIMED";
    public static final String GRANTED = "GRANTED";
    public static final String RELEASED = "RELEASED";

    @Column(name = "token_id", nullable = false, updatable = false)
    private Long tokenId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "status", nullable = false, length = 16, updatable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }
}
