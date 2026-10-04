package com.mannschaft.app.ranch.entity;

import com.mannschaft.app.ranch.reward.RanchRewardDecisionStatus;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** source factの本人一回だけの不変終端決定。 */
@Entity
@Table(name = "ranch_reward_decisions")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchRewardDecisionEntity extends RanchEntity {
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;
    @Column(name = "user_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long userId;
    @Column(name = "event_id", nullable = false)
    private UUID eventId;
    @Column(name = "source_type", nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    private RanchRewardSourceType sourceType;
    @Column(name = "canonical_key_hash", nullable = false, columnDefinition = "binary(32)")
    private byte[] canonicalKeyHash;
    @Column(name = "canonical_key", nullable = false, columnDefinition = "varbinary(240)")
    private byte[] canonicalKey;
    @Column(name = "reward_week", nullable = false)
    private LocalDate rewardWeek;
    @Column(name = "policy_id")
    private UUID policyId;
    @Column(name = "status", nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    private RanchRewardDecisionStatus status;
    @Column(name = "requested_points", nullable = false)
    private long requestedPoints;
    @Column(name = "awarded_points", nullable = false)
    private long awardedPoints;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    public byte[] getCanonicalKeyHash() { return canonicalKeyHash.clone(); }
    public byte[] getCanonicalKey() { return canonicalKey == null ? null : canonicalKey.clone(); }
}
