package com.mannschaft.app.parking.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.parking.RecurrenceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 来場者予約テンプレート（定期）エンティティ。
 */
@Entity
@Table(name = "parking_visitor_recurring")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ParkingVisitorRecurringEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long spaceId;

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecurrenceType recurrenceType;

    @Column(columnDefinition = "TINYINT UNSIGNED")
    private Integer dayOfWeek;

    @Column(columnDefinition = "TINYINT UNSIGNED")
    private Integer dayOfMonth;

    @Column(nullable = false)
    private LocalTime timeFrom;

    @Column(nullable = false)
    private LocalTime timeTo;

    @Column(length = 100)
    private String visitorName;

    @Column(length = 30)
    private String visitorPlateNumber;

    @Column(length = 200)
    private String purpose;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    private LocalDate nextGenerateDate;

    /**
     * テンプレートを更新する。
     */
    public void update(RecurrenceType recurrenceType, Integer dayOfWeek, Integer dayOfMonth,
                       LocalTime timeFrom, LocalTime timeTo, String visitorName,
                       String visitorPlateNumber, String purpose) {
        this.recurrenceType = recurrenceType;
        this.dayOfWeek = dayOfWeek;
        this.dayOfMonth = dayOfMonth;
        this.timeFrom = timeFrom;
        this.timeTo = timeTo;
        this.visitorName = visitorName;
        this.visitorPlateNumber = visitorPlateNumber;
        this.purpose = purpose;
    }

    /**
     * 次回生成日を更新する。
     */
    public void updateNextGenerateDate(LocalDate nextGenerateDate) {
        this.nextGenerateDate = nextGenerateDate;
    }

    /**
     * 無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }
}
