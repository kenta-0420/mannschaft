package com.mannschaft.app.loyalty.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.loyalty.Audience;
import com.mannschaft.app.loyalty.SelfAwardMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** チームポイントの業務契約。 */
@Entity
@Table(name = "team_loyalty_programs", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"team_id"})})
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoyaltyProgramEntity extends UuidV7Entity {

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "is_enabled", nullable = false)
    private boolean isEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_audience", nullable = false, length = 30)
    private Audience targetAudience;

    @Enumerated(EnumType.STRING)
    @Column(name = "self_award_mode", nullable = false, length = 20)
    private SelfAwardMode selfAwardMode;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void update(boolean enabled, Audience audience, SelfAwardMode mode, Instant now) {
        this.isEnabled = enabled;
        this.targetAudience = audience;
        this.selfAwardMode = mode;
        this.version++;
        this.updatedAt = now;
    }
}
