package com.mannschaft.app.equipment.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.equipment.EquipmentStatus;
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
 * 備品マスターエンティティ。備品の基本情報・在庫状況・ステータスを管理する。
 */
@Entity
@Table(name = "equipment_items")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class EquipmentItemEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(length = 100)
    private String category;

    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    @Column(nullable = false)
    @Builder.Default
    private Integer assignedQuantity = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private EquipmentStatus status = EquipmentStatus.AVAILABLE;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isConsumable = false;

    @Column(length = 200)
    private String storageLocation;

    private LocalDate purchaseDate;

    @Column(precision = 10, scale = 2)
    private BigDecimal purchasePrice;

    @Column(length = 500)
    private String s3Key;

    @Column(nullable = false, length = 100)
    private String qrCode;

    private LocalDateTime deletedAt;

    /**
     * 備品情報を更新する。
     */
    public void update(String name, String description, String category,
                       Integer quantity, String storageLocation,
                       LocalDate purchaseDate, BigDecimal purchasePrice,
                       Boolean isConsumable) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.quantity = quantity;
        this.storageLocation = storageLocation;
        this.purchaseDate = purchaseDate;
        this.purchasePrice = purchasePrice;
        this.isConsumable = isConsumable;
    }

    /**
     * ステータスを手動で変更する。
     */
    public void changeStatus(EquipmentStatus status) {
        this.status = status;
    }

    /**
     * 貸出数量を加算し、ステータスを自動更新する。
     *
     * @param qty 貸出数量
     */
    public void addAssignedQuantity(int qty) {
        this.assignedQuantity += qty;
        refreshStatus();
    }

    /**
     * 貸出数量を減算し、ステータスを自動更新する。
     *
     * @param qty 返却数量
     */
    public void subtractAssignedQuantity(int qty) {
        this.assignedQuantity = Math.max(0, this.assignedQuantity - qty);
        refreshStatus();
    }

    /**
     * 消耗品の消費で保有数量を減算する。
     *
     * @param qty 消費数量
     */
    public void consumeQuantity(int qty) {
        this.quantity -= qty;
        if (this.quantity <= 0) {
            this.quantity = 0;
            this.status = EquipmentStatus.RETIRED;
        }
    }

    /**
     * 利用可能数量を算出する。
     *
     * @return quantity - assignedQuantity
     */
    public int getAvailableQuantity() {
        return this.quantity - this.assignedQuantity;
    }

    /**
     * S3キーを更新する。
     */
    public void updateS3Key(String s3Key) {
        this.s3Key = s3Key;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * ステータスを在庫状況に基づいて自動更新する。
     * MAINTENANCE / RETIRED は手動設定のみのため対象外。
     */
    private void refreshStatus() {
        if (this.status == EquipmentStatus.MAINTENANCE || this.status == EquipmentStatus.RETIRED) {
            return;
        }
        if (getAvailableQuantity() <= 0) {
            this.status = EquipmentStatus.ALL_ASSIGNED;
        } else {
            this.status = EquipmentStatus.AVAILABLE;
        }
    }
}
