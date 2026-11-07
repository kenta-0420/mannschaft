package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import java.time.Instant;
import java.util.UUID;

/** 無料 care と後続報酬・交換の不変台帳。本人操作と同じ ranch TX で保存する。 */
@Entity
@Table(name = "ranch_point_ledger")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchPointLedgerEntity extends RanchEntity {
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "decision_id")
    private UUID decisionId;
    @Column(name = "command_id")
    private UUID commandId;
    @Column(name = "entry_kind", nullable = false, length = 20)
    private String entryKind;
    @Column(name = "delta_points", nullable = false)
    private long deltaPoints;
    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;
    @Column(name = "delta_xp", nullable = false)
    private long deltaXp;
    @Column(name = "dinosaur_id")
    private UUID dinosaurId;
    @Column(name = "rule_snapshot", nullable = false, columnDefinition = "json")
    private String ruleSnapshot;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
