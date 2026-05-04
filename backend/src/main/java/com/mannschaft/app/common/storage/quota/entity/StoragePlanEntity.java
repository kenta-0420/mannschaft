package com.mannschaft.app.common.storage.quota.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
 * F13 ストレージプラン定義（{@code storage_plans}）。SYSTEM_ADMIN が管理する。
 *
 * <p>scope_level（ORGANIZATION / TEAM / PERSONAL）別に容量・料金プランを保持する。</p>
 */
@Entity
@Table(name = "storage_plans")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class StoragePlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** ORGANIZATION / TEAM / PERSONAL */
    @Column(name = "scope_level", nullable = false, length = 20)
    private String scopeLevel;

    @Column(name = "included_bytes", nullable = false)
    private Long includedBytes;

    @Column(name = "max_bytes")
    private Long maxBytes;

    @Column(name = "price_monthly", nullable = false, precision = 10, scale = 2)
    private BigDecimal priceMonthly;

    @Column(name = "price_yearly", precision = 10, scale = 2)
    private BigDecimal priceYearly;

    @Column(name = "price_per_extra_gb", precision = 10, scale = 2)
    private BigDecimal pricePerExtraGb;

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault;

    @Column(name = "sort_order", nullable = false)
    private Short sortOrder;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

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
}
