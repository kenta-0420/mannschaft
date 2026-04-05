package com.mannschaft.app.parking.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 駐車場設定エンティティ。スコープごとの設定値を管理する。
 */
@Entity
@Table(name = "parking_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ParkingSettingsEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    @Builder.Default
    private Integer maxSpacesPerUser = 1;

    @Column(nullable = false)
    @Builder.Default
    private Integer maxVisitorReservationsPerDay = 2;

    @Column(nullable = false)
    @Builder.Default
    private Integer visitorReservationMaxDaysAhead = 30;

    @Column(nullable = false)
    @Builder.Default
    private Boolean visitorReservationRequiresApproval = true;

    /**
     * 設定を更新する。
     */
    public void update(Integer maxSpacesPerUser, Integer maxVisitorReservationsPerDay,
                       Integer visitorReservationMaxDaysAhead, Boolean visitorReservationRequiresApproval) {
        this.maxSpacesPerUser = maxSpacesPerUser;
        this.maxVisitorReservationsPerDay = maxVisitorReservationsPerDay;
        this.visitorReservationMaxDaysAhead = visitorReservationMaxDaysAhead;
        this.visitorReservationRequiresApproval = visitorReservationRequiresApproval;
    }
}
