package com.mannschaft.app.shift.entity;

import com.mannschaft.app.shift.ShiftPreference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * シフト希望エンティティ。メンバーのシフト希望を管理する。
 */
@Entity
@Table(name = "shift_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ShiftRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long scheduleId;

    @Column(nullable = false)
    private Long userId;

    private Long slotId;

    @Column(nullable = false)
    private LocalDate slotDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShiftPreference preference;

    @Column(length = 200)
    private String note;

    private LocalDateTime submittedAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.submittedAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 希望を更新する。
     *
     * @param preference 新しい希望種別
     * @param note       備考
     */
    public void updatePreference(ShiftPreference preference, String note) {
        this.preference = preference;
        this.note = note;
    }
}
