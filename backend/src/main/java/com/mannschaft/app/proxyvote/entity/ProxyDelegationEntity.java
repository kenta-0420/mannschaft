package com.mannschaft.app.proxyvote.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.proxyvote.DelegationStatus;
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
 * 委任状エンティティ。出席できないメンバーが代理人を指定、または白紙委任を行う。
 */
@Entity
@Table(name = "proxy_delegations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ProxyDelegationEntity extends BaseEntity {

    @Column(nullable = false)
    private Long sessionId;

    @Column(nullable = false)
    private Long delegatorId;

    private Long delegateId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isBlank = false;

    private Long electronicSealId;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DelegationStatus status = DelegationStatus.SUBMITTED;

    private Long reviewedBy;

    private LocalDateTime reviewedAt;

    /**
     * 委任状を承認する。
     */
    public void accept(Long reviewerId) {
        this.status = DelegationStatus.ACCEPTED;
        this.reviewedBy = reviewerId;
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * 委任状を却下する。
     */
    public void reject(Long reviewerId) {
        this.status = DelegationStatus.REJECTED;
        this.reviewedBy = reviewerId;
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * 委任状をキャンセルする。
     */
    public void cancel() {
        this.status = DelegationStatus.CANCELLED;
    }
}
