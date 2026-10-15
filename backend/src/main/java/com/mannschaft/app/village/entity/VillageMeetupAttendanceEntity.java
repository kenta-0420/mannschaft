package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.village.entity.enums.VillageMeetupAttendanceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 寄合出欠エンティティ（F17.2 Wave1 ②寄合後半戦・設計書 §4.2.1）。
 *
 * <p>CONFIRMED 状態の寄合に対して、村人が自分の出欠（GOING/MAYBE/ABSENT）を
 * upsert する。{@code (meetup_id, user_id)} の UNIQUE 制約で「1寄合×1村人=1行」を
 * DB レベルで保証する（設計書 §4.4.1 の upsert 実装方式）。</p>
 *
 * <p>{@code meetup_id} は同一ドメイン（village）内の参照だが、原則1に従い
 * FK は張らずインデックスのみで整合を保証する（設計書 §4.2.1 明示）。</p>
 */
@Entity
@Table(name = "village_meetup_attendances")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageMeetupAttendanceEntity extends UuidV7Entity {

    /** → village_meetups.id（同一ドメイン・FK非付与/index・原則1） */
    @Column(name = "meetup_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID meetupId;

    /** 出欠を答えた村人（FK非付与・原則1） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VillageMeetupAttendanceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
