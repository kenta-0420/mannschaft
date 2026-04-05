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
 * 物件掲示板エンティティ。
 */
@Entity
@Table(name = "property_listings")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PropertyListingEntity extends BaseEntity {

    @Column(nullable = false)
    private Long dwellingUnitId;

    @Column(nullable = false)
    private Long listedBy;

    @Column(nullable = false, length = 10)
    private String listingType;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    private BigDecimal askingPrice;

    private BigDecimal monthlyRent;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    private LocalDateTime expiresAt;

    @Column(columnDefinition = "JSON")
    private String imageUrls;

    private LocalDateTime deletedAt;

    /**
     * 物件情報を更新する。
     */
    public void update(String title, String description, BigDecimal askingPrice,
                       BigDecimal monthlyRent, LocalDateTime expiresAt, String imageUrls) {
        this.title = title;
        this.description = description;
        this.askingPrice = askingPrice;
        this.monthlyRent = monthlyRent;
        this.expiresAt = expiresAt;
        this.imageUrls = imageUrls;
    }

    /**
     * ステータスを変更する。
     */
    public void changeStatus(String status) {
        this.status = status;
    }

    /**
     * 編集可能かどうかを判定する。
     */
    public boolean isEditable() {
        return "ACTIVE".equals(this.status);
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
