package com.mannschaft.app.resident.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 居室エンティティ。
 */
@Entity
@Table(name = "dwelling_units")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class DwellingUnitEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    private Long teamId;

    private Long organizationId;

    @Column(nullable = false, length = 50)
    private String unitNumber;

    private Short floor;

    private BigDecimal areaSqm;

    @Column(length = 20)
    private String layout;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String unitType = "STANDARD";

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false)
    @Builder.Default
    private Short residentCount = 0;

    private LocalDateTime deletedAt;

    /**
     * 居室情報を更新する。
     */
    public void update(String unitNumber, Short floor, BigDecimal areaSqm,
                       String layout, String unitType, String notes) {
        this.unitNumber = unitNumber;
        this.floor = floor;
        this.areaSqm = areaSqm;
        this.layout = layout;
        this.unitType = unitType;
        this.notes = notes;
    }

    /**
     * 入居者数をインクリメントする。
     */
    public void incrementResidentCount() {
        this.residentCount++;
    }

    /**
     * 入居者数をデクリメントする。
     */
    public void decrementResidentCount() {
        if (this.residentCount > 0) {
            this.residentCount--;
        }
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
