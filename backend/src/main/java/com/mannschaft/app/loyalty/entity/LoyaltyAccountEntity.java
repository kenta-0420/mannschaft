package com.mannschaft.app.loyalty.entity;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.loyalty.LoyaltyErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** チームポイントの業務契約。 */
@Entity
@Table(name = "team_loyalty_accounts", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"team_id", "user_id"}),
        @UniqueConstraint(columnNames = {"team_id", "id"})})
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoyaltyAccountEntity extends UuidV7Entity {

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "balance", nullable = false)
    private int balance;

    @Column(name = "lifetime_earned", nullable = false)
    private long lifetimeEarned;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** チームポイントの業務契約。 */
    public void apply(int delta, Instant now) {
        long nextBalance = (long) balance + delta;
        if (nextBalance < 0) {
            throw new BusinessException(LoyaltyErrorCode.LOYALTY_008);
        }
        if (nextBalance > Integer.MAX_VALUE || delta > 0 && lifetimeEarned > Long.MAX_VALUE - delta) {
            throw new BusinessException(LoyaltyErrorCode.LOYALTY_009);
        }
        balance = (int) nextBalance;
        if (delta > 0) {
            lifetimeEarned += delta;
        }
        updatedAt = now;
    }
}
