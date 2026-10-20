package com.mannschaft.app.loyalty.entity;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.loyalty.LoyaltyErrorCode;
import com.mannschaft.app.loyalty.RequestOperation;
import com.mannschaft.app.loyalty.RequestReason;
import com.mannschaft.app.loyalty.RequestStatus;
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
@Table(name = "team_loyalty_award_requests", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"team_id", "id"}),
        @UniqueConstraint(columnNames = {"team_id", "user_id", "submit_key"}),
        @UniqueConstraint(columnNames = {"team_id", "verification_code"}),
        @UniqueConstraint(columnNames = {"team_id", "user_id", "rule_id", "award_on", "pending_unique_key"}),
        @UniqueConstraint(columnNames = {"team_id", "terminal_actor_user_id", "terminal_action", "action_key"}),
        @UniqueConstraint(columnNames = {"team_id", "transaction_id"})})
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoyaltyAwardRequestEntity extends UuidV7Entity {

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "source_location_id", nullable = false)
    private Long sourceLocationId;

    @Column(name = "program_version", nullable = false)
    private long programVersion;

    @Column(name = "rule_version", nullable = false)
    private long ruleVersion;

    @Column(name = "points", nullable = false)
    private int points;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    @Column(name = "location_name", nullable = false, length = 100)
    private String locationName;

    @Column(name = "award_on", nullable = false)
    @org.hibernate.annotations.JdbcType(org.hibernate.type.descriptor.jdbc.LocalDateJdbcType.class)
    private LocalDate awardOn;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RequestStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = true, length = 30)
    private RequestReason reason;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "verification_code", nullable = false, length = 8)
    private String verificationCode;

    @Column(name = "submit_key", nullable = false)
    private UUID submitKey;

    @Column(name = "submit_payload_hash", nullable = false, length = 32)
    private byte[] submitPayloadHash;

    @Column(name = "terminal_actor_user_id", nullable = true)
    private Long terminalActorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "terminal_action", nullable = true, length = 10)
    private RequestOperation terminalAction;

    @Column(name = "action_key", nullable = true)
    private UUID actionKey;

    @Column(name = "action_payload_hash", nullable = true, length = 32)
    private byte[] actionPayloadHash;

    @Column(name = "transaction_id", nullable = true)
    private UUID transactionId;

    @Column(name = "closed_at", nullable = true)
    private Instant closedAt;

    @Column(name = "pending_unique_key", insertable = false, updatable = false,
            columnDefinition = "tinyint generated always as (if(status = 'PENDING', 1, null)) virtual")
    private Integer pendingUniqueKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** チームポイントの業務契約。 */
    public void close(RequestStatus status, RequestReason reason, Long actor, RequestOperation action,
                      UUID key, byte[] hash, UUID transaction, Instant now) {
        if (this.status != RequestStatus.PENDING) {
            throw new BusinessException(LoyaltyErrorCode.LOYALTY_013);
        }
        this.status = status;
        this.reason = reason;
        this.terminalActorUserId = actor;
        this.terminalAction = action;
        this.actionKey = key;
        this.actionPayloadHash = hash == null ? null : hash.clone();
        this.transactionId = transaction;
        this.closedAt = now;
        this.updatedAt = now;
        this.version++;
    }
}
