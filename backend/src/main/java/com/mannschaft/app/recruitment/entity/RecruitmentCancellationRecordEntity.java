package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.CancellationSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * F03.11 募集型予約: 個別キャンセル記録 (Phase 5a)。
 * 永続保持。料金請求や紛争対応の証跡として使用。
 *
 * §14.11: fee_amount は INSERT 後 UPDATE 禁止。@Column(updatable=false) でJPA レベルで強制。
 * BaseEntity は継承しない (テーブルは cancelled_at のみで created_at/updated_at が無いため)。
 */
@Entity
@Table(name = "recruitment_cancellation_records")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class RecruitmentCancellationRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long participantId;

    private Long listingId;

    private Long userId;

    private Long teamId;

    @Column(nullable = false)
    private LocalDateTime cancelledAt;

    private Long cancelledBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CancellationSource cancelSource;

    @Column(nullable = false)
    private Integer hoursBeforeStart;

    private Long appliedTierId;

    /** §14.11: INSERT 後 UPDATE 禁止 */
    @Column(nullable = false, updatable = false)
    private Integer feeAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CancellationPaymentStatus paymentStatus = CancellationPaymentStatus.NOT_REQUIRED;

    @Column(length = 100)
    private String paymentId;

    /** §Phase5a 決済リトライ回数（最大3回）。 */
    @Builder.Default
    private Integer paymentRetryCount = 0;

    @Column(length = 500)
    private String notes;

    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        if (this.cancelledAt == null) {
            this.cancelledAt = LocalDateTime.now();
        }
    }

    // ===========================================
    // payment_status 系のみ更新可能 (§14.11)
    // ===========================================

    /** 決済成功。 */
    public void markPaid(String paymentId) {
        this.paymentStatus = CancellationPaymentStatus.PAID;
        this.paymentId = paymentId;
    }

    /** 決済失敗。 */
    public void markFailed() {
        this.paymentStatus = CancellationPaymentStatus.FAILED;
    }

    /** 管理者免除。 */
    public void waive(Long adminUserId, String notes) {
        this.paymentStatus = CancellationPaymentStatus.WAIVED;
        this.notes = notes;
    }

    /** 論理削除を行う (GDPR 削除要求時)。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /** §Phase5a 決済リトライ回数をインクリメントする。 */
    public void incrementRetryCount() {
        this.paymentRetryCount = (this.paymentRetryCount == null ? 0 : this.paymentRetryCount) + 1;
    }
}
