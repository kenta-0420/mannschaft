package com.mannschaft.app.safetycheck.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.safetycheck.MessageSource;
import com.mannschaft.app.safetycheck.SafetyResponseStatus;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 安否確認回答エンティティ。ユーザーの安否回答を管理する。
 */
@Entity
@Table(name = "safety_responses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SafetyResponseEntity extends BaseEntity {

    @Column(nullable = false)
    private Long safetyCheckId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SafetyResponseStatus status;

    @Column(length = 200)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private MessageSource messageSource;

    @Column(nullable = false)
    @Builder.Default
    private Boolean gpsShared = false;

    @Column(precision = 10, scale = 7)
    private BigDecimal gpsLatitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal gpsLongitude;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime respondedAt = LocalDateTime.now();
}
