package com.mannschaft.app.payment.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.payment.PaymentRequestStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F08.9 協会→加盟チーム請求エンティティ（payment_requests）。
 *
 * <p>組織(協会=ORG)が加盟チーム(TEAM)へ発行する請求書。チーム ADMIN が通知内の支払いボタンから
 * 「チーム ADMIN 個人の Stripe Customer で立替課金」（案3・README §6.3）して支払う。</p>
 *
 * <p>状態遷移:
 * {@code DRAFT}（発行）→ {@code SENT}（配信）→ {@code VIEWED}（閲覧）→ {@code PAID}（支払い）。
 * 期限超過は @Scheduled バッチが {@code SENT}/{@code VIEWED} → {@code OVERDUE}。{@code DRAFT}/{@code SENT}
 * は {@code CANCELLED} 可。再請求は CANCELLED 後に新行を起票し旧行の {@code supersededById} に新行を指す。</p>
 *
 * <p>設計原則:</p>
 * <ul>
 *   <li>原則1: クロスドメイン FK なし（issuer/payer/payee/created_by/notification はすべて論理参照）。</li>
 *   <li>原則6: 主キーは UUIDv7（{@link UuidV7Entity} 継承）。</li>
 *   <li>原則7: organization_id を持つため {@code AbstractTenantAwareRepository} 継承対象。</li>
 * </ul>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.2 / 02_api_design.md §7</p>
 */
@Entity
@Table(name = "payment_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class PaymentRequestEntity extends UuidV7Entity {

    /** テナント（請求元の協会）。論理参照・FK なし。 */
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** 請求元 scope 種別（P7 では ORG のみ）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "issuer_scope_kind", nullable = false, length = 8)
    private ScopeKind issuerScopeKind;

    /** 請求元 ID（協会）。論理参照・FK なし。 */
    @Column(name = "issuer_scope_id", nullable = false)
    private Long issuerScopeId;

    /** 請求先 scope 種別（TEAM）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "payer_scope_kind", nullable = false, length = 8)
    private ScopeKind payerScopeKind;

    /** 請求先チーム ID。論理参照・FK なし。 */
    @Column(name = "payer_scope_id", nullable = false)
    private Long payerScopeId;

    /** 着金先（協会の Connect 口座）。論理参照・FK なし。発行時に解決して焼き付ける。 */
    @Column(name = "payee_connect_account_id", nullable = false)
    private UUID payeeConnectAccountId;

    /** 請求タイトル。 */
    @Column(name = "title", nullable = false, length = 120)
    private String title;

    /** 請求の説明。 */
    @Column(name = "description", length = 1000)
    private String description;

    /** 額面（円整数・最小通貨単位）。 */
    @Column(name = "face_amount", nullable = false)
    private Integer faceAmount;

    /** 通貨。 */
    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "JPY";

    /** 税からくり（NULL=税なし扱い・NoOpTaxPolicy）。 */
    @Column(name = "tax_category", length = 16)
    private String taxCategory;

    /** 支払期限。 */
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /** 状態。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    @Builder.Default
    private PaymentRequestStatus status = PaymentRequestStatus.DRAFT;

    /** 支払い時に money rail へ連結（F22.1 escrow）。論理参照・FK なし。 */
    @Column(name = "escrow_transaction_id")
    private UUID escrowTransactionId;

    /** 配信した確認必須通知（F04.9）。論理参照・FK なし。第二波で配信時に埋める。 */
    @Column(name = "confirmable_notification_id")
    private Long confirmableNotificationId;

    /** CANCELLED 後の再請求で新請求を指す（再発行の追跡・自己参照）。論理参照・FK なし。 */
    @Column(name = "superseded_by_id")
    private UUID supersededById;

    /** SENT 遷移日時（配信日時）。 */
    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** VIEWED 遷移日時（チーム閲覧日時）。 */
    @Column(name = "viewed_at")
    private LocalDateTime viewedAt;

    /** PAID 遷移日時（支払い日時）。 */
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /** 発行者ユーザーID。論理参照・FK なし。 */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 論理削除（GDPR/退会）。業務状態（status）とは独立。NULL=有効。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 請求を CANCELLED 状態に遷移する（発行者が取消・DRAFT/SENT からのみ）。
     */
    public void cancel() {
        this.status = PaymentRequestStatus.CANCELLED;
    }

    /**
     * CANCELLED 後の再請求で、新しい請求行を指す（再発行の追跡）。
     *
     * @param newRequestId 新規に発行した請求の ID
     */
    public void supersedeBy(UUID newRequestId) {
        this.supersededById = newRequestId;
    }

    /**
     * 配信で SENT 状態に遷移し、配信した確認必須通知（F04.9）を連結する（DRAFT からのみ）。
     *
     * @param confirmableNotificationId 配信した確認必須通知の ID（F04.9）
     */
    public void markAsSent(Long confirmableNotificationId) {
        this.status = PaymentRequestStatus.SENT;
        this.confirmableNotificationId = confirmableNotificationId;
        this.sentAt = LocalDateTime.now();
    }

    /**
     * チームが初閲覧したとき SENT → VIEWED に遷移する（冪等・SENT のときのみ遷移）。
     *
     * <p>VIEWED/OVERDUE/PAID/CANCELLED では何もしない（一度きりの初閲覧マーキング）。
     * 呼び出し側（詳細取得）は遷移有無に関わらず請求を返す。</p>
     *
     * @return SENT → VIEWED に遷移したら true（初閲覧）、それ以外は false
     */
    public boolean markAsViewedIfSent() {
        if (this.status == PaymentRequestStatus.SENT) {
            this.status = PaymentRequestStatus.VIEWED;
            this.viewedAt = LocalDateTime.now();
            return true;
        }
        return false;
    }

    /**
     * 期限超過で OVERDUE 状態に遷移する（SENT/VIEWED からのみ・@Scheduled バッチが呼ぶ）。
     *
     * <p>PAID/CANCELLED/DRAFT/OVERDUE では遷移しない（バッチ側の抽出クエリで SENT/VIEWED に
     * 絞るが、競合に対する二重防御として状態を再確認する）。</p>
     *
     * @return SENT/VIEWED → OVERDUE に遷移したら true、それ以外は false
     */
    public boolean markAsOverdueIfDue() {
        if (this.status == PaymentRequestStatus.SENT || this.status == PaymentRequestStatus.VIEWED) {
            this.status = PaymentRequestStatus.OVERDUE;
            return true;
        }
        return false;
    }

    /**
     * 支払い成立で PAID 状態に遷移し、money rail（escrow）へ連結する。
     *
     * @param escrowTransactionId 連結する escrow 取引 ID
     */
    public void markAsPaid(UUID escrowTransactionId) {
        this.status = PaymentRequestStatus.PAID;
        this.escrowTransactionId = escrowTransactionId;
        this.paidAt = LocalDateTime.now();
    }
}
