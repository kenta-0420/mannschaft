package com.mannschaft.app.circulation.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 押印委任エンティティ。
 *
 * <p>F05.2 Phase 11 第三陣 3-B で追加。受信者（委任者）が別ユーザー（代理人）に
 * 押印を委任する。代理人は委任者の名義で押印できる。</p>
 *
 * <p>CLAUDE.md 原則 6: 新規テーブルのため UUIDv7 主キー。
 * delegatee_user_id はクロスドメイン参照のため FK なし（原則 1）。</p>
 */
@Entity
@Table(name = "circulation_stamp_delegations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class CirculationStampDelegationEntity extends UuidV7Entity {

    /** 委任ステータス。 */
    public enum Status {
        /** 有効。代理人が押印可能。 */
        ACTIVE,
        /** 取り消し済み。 */
        REVOKED,
        /** 代理人が押印を完了。 */
        FULFILLED
    }

    @Column(nullable = false)
    private Long documentId;

    @Column(nullable = false)
    private Long delegatorUserId;

    @Column(nullable = false)
    private Long delegateeUserId;

    @Column(length = 255)
    private String reason;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.ACTIVE;

    private LocalDateTime revokedAt;

    private LocalDateTime fulfilledAt;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public void revoke() {
        this.status = Status.REVOKED;
        this.revokedAt = LocalDateTime.now();
    }

    public void fulfill() {
        this.status = Status.FULFILLED;
        this.fulfilledAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return this.status == Status.ACTIVE;
    }
}
