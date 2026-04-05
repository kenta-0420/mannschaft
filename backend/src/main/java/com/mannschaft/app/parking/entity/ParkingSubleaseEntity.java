package com.mannschaft.app.parking.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.parking.PaymentMethod;
import com.mannschaft.app.parking.SubleaseStatus;
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
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * サブリース（又貸し）エンティティ。
 */
@Entity
@Table(name = "parking_subleases")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ParkingSubleaseEntity extends BaseEntity {

    @Column(nullable = false)
    private Long spaceId;

    @Column(nullable = false)
    private Long assignmentId;

    @Column(nullable = false)
    private Long offeredBy;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 10, scale = 0)
    private BigDecimal pricePerMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PaymentMethod paymentMethod = PaymentMethod.DIRECT;

    @Column(nullable = false)
    private LocalDate availableFrom;

    private LocalDate availableTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SubleaseStatus status = SubleaseStatus.OPEN;

    private Long matchedApplicationId;

    private LocalDateTime deletedAt;

    /**
     * サブリース情報を更新する。
     */
    public void update(String title, String description, BigDecimal pricePerMonth,
                       PaymentMethod paymentMethod, LocalDate availableFrom, LocalDate availableTo) {
        this.title = title;
        this.description = description;
        this.pricePerMonth = pricePerMonth;
        this.paymentMethod = paymentMethod;
        this.availableFrom = availableFrom;
        this.availableTo = availableTo;
    }

    /**
     * マッチングを確定する。
     */
    public void match(Long matchedApplicationId) {
        this.status = SubleaseStatus.MATCHED;
        this.matchedApplicationId = matchedApplicationId;
    }

    /**
     * キャンセルする。
     */
    public void cancel() {
        this.status = SubleaseStatus.CANCELLED;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
