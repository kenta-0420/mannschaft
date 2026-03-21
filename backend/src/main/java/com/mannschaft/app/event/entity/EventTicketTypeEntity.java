package com.mannschaft.app.event.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * イベントチケット種別エンティティ。チケットの種類・価格・在庫を管理する。
 */
@Entity
@Table(name = "event_ticket_types")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class EventTicketTypeEntity extends BaseEntity {

    @Column(nullable = false)
    private Long eventId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, precision = 12, scale = 0)
    @Builder.Default
    private BigDecimal price = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "JPY";

    private Integer maxQuantity;

    @Column(nullable = false)
    @Builder.Default
    private Integer issuedCount = 0;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String minRegistrationRole = "MEMBER_PLUS";

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * 発行数をインクリメントする。
     *
     * @param quantity 追加発行数
     */
    public void incrementIssuedCount(int quantity) {
        this.issuedCount += quantity;
    }

    /**
     * 在庫があるかどうかを判定する。
     *
     * @param requestedQuantity 要求数量
     * @return 在庫がある場合 true
     */
    public boolean hasStock(int requestedQuantity) {
        if (this.maxQuantity == null) {
            return true;
        }
        return this.issuedCount + requestedQuantity <= this.maxQuantity;
    }
}
