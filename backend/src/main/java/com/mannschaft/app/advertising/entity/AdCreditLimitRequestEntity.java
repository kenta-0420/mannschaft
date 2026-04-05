package com.mannschaft.app.advertising.entity;

import com.mannschaft.app.advertising.CreditLimitRequestStatus;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 広告与信限度額変更リクエストエンティティ。
 */
@Entity
@Table(name = "ad_credit_limit_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AdCreditLimitRequestEntity extends BaseEntity {

    @Column(nullable = false)
    private Long advertiserAccountId;

    @Column(nullable = false)
    private BigDecimal currentLimit;

    @Column(nullable = false)
    private BigDecimal requestedLimit;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private CreditLimitRequestStatus status = CreditLimitRequestStatus.PENDING;

    private Long reviewedBy;

    private LocalDateTime reviewedAt;

    @Column(length = 500)
    private String reviewNote;

    /**
     * リクエストを承認する（PENDING → APPROVED）。
     */
    public void approve(Long reviewedByUserId) {
        if (this.status != CreditLimitRequestStatus.PENDING) {
            throw new IllegalStateException("承認はPENDING状態のリクエストのみ可能です");
        }
        this.status = CreditLimitRequestStatus.APPROVED;
        this.reviewedBy = reviewedByUserId;
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * リクエストを却下する（PENDING → REJECTED）。
     */
    public void reject(Long reviewedByUserId, String reviewNote) {
        if (this.status != CreditLimitRequestStatus.PENDING) {
            throw new IllegalStateException("却下はPENDING状態のリクエストのみ可能です");
        }
        this.status = CreditLimitRequestStatus.REJECTED;
        this.reviewedBy = reviewedByUserId;
        this.reviewedAt = LocalDateTime.now();
        this.reviewNote = reviewNote;
    }
}
