package com.mannschaft.app.advertising.entity;

import com.mannschaft.app.advertising.ConversionType;
import com.mannschaft.app.common.BaseEntity;
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

import java.time.LocalDateTime;

/**
 * 広告コンバージョンエンティティ。
 */
@Entity
@Table(name = "ad_conversions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AdConversionEntity extends BaseEntity {

    @Column(nullable = false)
    private Long clickId;

    @Column(nullable = false)
    private Long campaignId;

    @Column(nullable = false)
    private Long adId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversionType conversionType;

    @Column(nullable = false)
    private Long convertedUserId;

    @Column(nullable = false)
    private LocalDateTime convertedAt;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    @Builder.Default
    private int attributionWindowDays = 7;
}
