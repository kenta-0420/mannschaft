package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** ranch_participation_periodsの本人スコープ永続骨格。 */
@Entity
@Table(name = "ranch_participation_periods")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchParticipationPeriodEntity extends RanchEntity {
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;
    @Column(name = "ends_at", nullable = true)
    private Instant endsAt;

    public void closeAt(Instant now) {
        if (now == null || endsAt != null || startsAt == null || !now.isAfter(startsAt)) {
            throw new IllegalArgumentException("参加期間を終了できません");
        }
        endsAt = now;
    }
}
