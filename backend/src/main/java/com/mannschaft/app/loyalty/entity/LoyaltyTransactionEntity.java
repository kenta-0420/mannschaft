package com.mannschaft.app.loyalty.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.loyalty.RequestOperation;
import com.mannschaft.app.loyalty.TransactionChannel;
import com.mannschaft.app.loyalty.TransactionKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** チームポイントの業務契約。 */
@Entity
@Table(name = "team_loyalty_transactions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"team_id", "id"}),
        @UniqueConstraint(columnNames = {"team_id", "actor_user_id", "request_operation", "request_key"}),
        @UniqueConstraint(columnNames = {"team_id", "user_id", "rule_id", "award_on"})})
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoyaltyTransactionEntity extends UuidV7Entity {

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private TransactionKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private TransactionChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_operation", nullable = false, length = 20)
    private RequestOperation requestOperation;

    @Column(name = "delta", nullable = false)
    private int delta;

    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    @Column(name = "rule_id", nullable = true)
    private UUID ruleId;

    @Column(name = "reward_id", nullable = true)
    private UUID rewardId;

    @Column(name = "source_location_id", nullable = true)
    private Long sourceLocationId;

    @Column(name = "award_on", nullable = true)
    @org.hibernate.annotations.JdbcType(org.hibernate.type.descriptor.jdbc.LocalDateJdbcType.class)
    private LocalDate awardOn;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "rule_or_reward_version", nullable = false)
    private long ruleOrRewardVersion;

    @Column(name = "program_version", nullable = false)
    private long programVersion;

    @Column(name = "request_key", nullable = false)
    private UUID requestKey;

    @Column(name = "payload_hash", nullable = false, length = 32)
    private byte[] payloadHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
