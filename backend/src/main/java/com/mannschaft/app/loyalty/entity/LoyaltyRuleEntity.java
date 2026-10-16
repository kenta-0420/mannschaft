package com.mannschaft.app.loyalty.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.loyalty.Frequency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** チームポイントの業務契約。 */
@Entity
@Table(name = "team_loyalty_rules", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"team_id"}),
        @UniqueConstraint(columnNames = {"team_id", "id"})})
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoyaltyRuleEntity extends UuidV7Entity {

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "points", nullable = false)
    private int points;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 30)
    private Frequency frequency;

    @Column(name = "is_self_award_enabled", nullable = false)
    private boolean isSelfAwardEnabled;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void update(String name, int points, Frequency frequency, boolean selfEnabled, boolean active, LocalDateTime now) {
        this.name = name;
        this.points = points;
        this.frequency = frequency;
        this.isSelfAwardEnabled = selfEnabled;
        this.isActive = active;
        this.version++;
        this.updatedAt = now;
    }
}
