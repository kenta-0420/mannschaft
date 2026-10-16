package com.mannschaft.app.loyalty.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** チームポイントの業務契約。 */
@Entity
@Table(name = "team_loyalty_rewards", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"team_id", "id"})})
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoyaltyRewardEntity extends UuidV7Entity {

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", nullable = true, length = 500)
    private String description;

    @Column(name = "required_points", nullable = false)
    private int requiredPoints;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void update(String name, String description, int points, boolean active, LocalDateTime now) {
        this.name = name;
        this.description = description;
        this.requiredPoints = points;
        this.isActive = active;
        this.version++;
        this.updatedAt = now;
    }
}
