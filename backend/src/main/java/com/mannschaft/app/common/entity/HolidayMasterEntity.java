package com.mannschaft.app.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 祝日マスタエンティティ。システム共通・組織・チーム単位で祝日を管理する。
 */
@Entity
@Table(name = "holiday_master")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class HolidayMasterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String scopeType = "SYSTEM";

    @Column(nullable = false)
    @Builder.Default
    private Long scopeId = 0L;

    @Column(nullable = false)
    private LocalDate holidayDate;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 5)
    @Builder.Default
    private String country = "JP";

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRecurring = false;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
