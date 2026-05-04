package com.mannschaft.app.membership.entity;

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
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 役職割当エンティティ（memberships と positions の中間表）。
 *
 * <p>F00.5 メンバーシップ基盤再設計で導入。期間付きの役職兼任を表す N:N 中間表。</p>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §5.2</p>
 */
@Entity
@Table(name = "member_positions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MemberPositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "membership_id", nullable = false)
    private Long membershipId;

    @Column(name = "position_id", nullable = false)
    private Long positionId;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** 役職離任日時。NULL = 現役。 */
    @Column(name = "ended_at")
    @Setter
    private LocalDateTime endedAt;

    @Column(name = "assigned_by")
    private Long assignedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 部分 UNIQUE 用の生成列（DB 側で計算）。
     * {@code ended_at IS NULL} のとき {@code membership_id:position_id} の文字列、
     * 離任済のとき NULL。JPA からの書き込みは禁止。
     *
     * <p>設計書 §5.5 / EC-20 参照。</p>
     */
    @Column(name = "active_position_key", insertable = false, updatable = false)
    private String activePositionKey;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.startedAt == null) {
            this.startedAt = now;
        }
        this.createdAt = now;
    }

    /**
     * 現役（ended_at IS NULL）の役職割当かを判定する。
     */
    public boolean isActive() {
        return this.endedAt == null;
    }
}
