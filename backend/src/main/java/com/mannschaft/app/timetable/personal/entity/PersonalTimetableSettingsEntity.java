package com.mannschaft.app.timetable.personal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * F03.15 個人時間割ユーザー設定（1ユーザー1行・UPSERT 動作）。
 *
 * <p>PK が user_id のため BaseEntity の AUTO_INCREMENT id を使わず独自 PK。</p>
 */
@Entity
@Table(name = "personal_timetable_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PersonalTimetableSettingsEntity {

    @Id
    private Long userId;

    @Setter
    @Column(nullable = false)
    @Builder.Default
    private Boolean autoReflectClassChangesToCalendar = true;

    @Setter
    @Column(nullable = false)
    @Builder.Default
    private Boolean notifyTeamSlotNoteUpdates = true;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DefaultPeriodTemplate defaultPeriodTemplate = DefaultPeriodTemplate.CUSTOM;

    @Setter
    @Column(nullable = false, columnDefinition = "JSON")
    @Builder.Default
    private String visibleDefaultFields = "[\"preparation\",\"review\",\"items_to_bring\",\"free_memo\"]";

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * デフォルト時限テンプレート種別。
     */
    public enum DefaultPeriodTemplate {
        ELEMENTARY,
        JUNIOR_HIGH,
        HIGH_SCHOOL,
        UNIV_90MIN,
        UNIV_100MIN,
        CUSTOM
    }
}
