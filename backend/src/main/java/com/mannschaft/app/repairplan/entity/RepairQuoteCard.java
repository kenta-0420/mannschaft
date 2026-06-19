package com.mannschaft.app.repairplan.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 業者見積カード（F08.8 Phase 1・案4）。
 *
 * <p>1 ボード内で複数業者の見積行を保持する。
 * {@code kanban_id} は同ドメイン内 FK + CASCADE、
 * {@code vendor_id} は F09.13 への ID 参照（FK なし）。</p>
 */
@Entity
@Table(name = "repair_quote_cards")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class RepairQuoteCard extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "kanban_id", nullable = false)
    private UUID kanbanId;

    /** F09.13 vendors.id（クロスドメイン参照・FK なし） */
    @Column(name = "vendor_id", nullable = false)
    private Long vendorId;

    @Column(name = "vendor_name_snapshot", nullable = false, length = 150)
    private String vendorNameSnapshot;

    @Column(name = "stage", nullable = false, length = 20)
    private String stage;

    @Column(name = "amount")
    private Long amount;

    @Column(name = "breakdown_json", columnDefinition = "JSON")
    private String breakdownJson;

    @Column(name = "bid_token_hash", length = 64)
    private String bidTokenHash;

    @Column(name = "is_visible_after")
    private LocalDateTime isVisibleAfter;

    @Column(name = "compliance_check_status", nullable = false, length = 20)
    private String complianceCheckStatus;

    @Column(name = "compliance_checked_at")
    private LocalDateTime complianceCheckedAt;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
