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
import lombok.Builder;
import lombok.experimental.SuperBuilder;
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
@SuperBuilder(toBuilder = true)
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

    /**
     * 回収不能（リトライ上限到達・F03.11.1 §5.1）。
     *
     * <p>「試行が尽きた」ではなく「回収できない」という<b>結果</b>を表す終端状態である。
     * 未払いであることに変わりはないため新規申込のブロックは続き、この状態からの出口は免除のみとする。</p>
     */
    public void markUncollectible() {
        this.paymentStatus = CancellationPaymentStatus.UNCOLLECTIBLE;
    }

    /**
     * キャンセル料を免除する（F03.11.1 §10）。
     *
     * <p>免除できるのは受取先側（TEAM/ORG の精算管理者・個人受取の本人）と運営管理者であり、
     * 運営管理者とは限らないため引数名は {@code operatorUserId} とする。</p>
     *
     * <p><b>操作者の記録は監査ログ側の責務である</b>（Service 層で
     * {@code AuditEventType.RECRUITMENT_CANCELLATION_FEE_WAIVED} を残す）。本メソッドは
     * {@code operatorUserId} を状態として保持しない。引数として受けているのは、免除という操作が
     * 常に操作者を伴うことを呼び出し側に要求するためである。</p>
     *
     * @param operatorUserId 操作者ユーザー ID（監査ログ側で記録する）
     * @param notes          免除理由
     */
    public void waive(Long operatorUserId, String notes) {
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
