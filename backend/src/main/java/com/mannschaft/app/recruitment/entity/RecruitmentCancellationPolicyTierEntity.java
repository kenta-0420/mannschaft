package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.recruitment.CancellationFeeType;
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

import java.time.LocalDateTime;

/**
 * F03.11 募集型予約: キャンセル料の段階定義 (Phase 5a)。
 * 1ポリシーあたり最大4段階。tier_order が大きいほど開催直前で高料金。
 */
@Entity
@Table(name = "recruitment_cancellation_policy_tiers")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class RecruitmentCancellationPolicyTierEntity extends BaseEntity {

    @Column(nullable = false)
    private Long policyId;

    @Column(nullable = false)
    private Integer tierOrder;

    @Column(nullable = false)
    private Integer appliesAtOrBeforeHours;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CancellationFeeType feeType;

    @Column(nullable = false)
    private Integer feeValue;

    private LocalDateTime deletedAt;

    /** 論理削除を行う。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
