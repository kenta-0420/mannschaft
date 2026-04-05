package com.mannschaft.app.ticket.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.ticket.TicketBookStatus;
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
 * 発行済み回数券エンティティ。1レコード = 1冊の回数券。
 */
@Entity
@Table(name = "ticket_books")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TicketBookEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Integer totalTickets;

    @Column(nullable = false)
    @Builder.Default
    private Integer usedTickets = 0;

    /**
     * remaining_tickets は MySQL の GENERATED ALWAYS AS カラム。
     * JPA からは読み取り専用として扱い、INSERT/UPDATE では書き込まない。
     */
    @Column(insertable = false, updatable = false)
    private Integer remainingTickets;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TicketBookStatus status = TicketBookStatus.PENDING;

    private LocalDateTime purchasedAt;

    private LocalDateTime expiresAt;

    private Long paymentId;

    private Long issuedBy;

    @Column(length = 500)
    private String note;

    /**
     * チケットを1枚消化する。残数0になった場合はステータスを EXHAUSTED に遷移する。
     */
    public void consume() {
        this.usedTickets++;
        if (this.usedTickets >= this.totalTickets) {
            this.status = TicketBookStatus.EXHAUSTED;
        }
    }

    /**
     * 消化を取り消す（1枚分）。EXHAUSTED の場合は ACTIVE に復帰する。
     */
    public void voidConsumption() {
        if (this.usedTickets > 0) {
            this.usedTickets--;
        }
        if (this.status == TicketBookStatus.EXHAUSTED) {
            this.status = TicketBookStatus.ACTIVE;
        }
    }

    /**
     * 決済完了で ACTIVE に遷移する。有効期限を設定する。
     *
     * @param validityDays 有効期間（日数。null の場合は無期限）
     */
    public void activate(Integer validityDays) {
        this.status = TicketBookStatus.ACTIVE;
        this.purchasedAt = LocalDateTime.now();
        if (validityDays != null) {
            this.expiresAt = this.purchasedAt.plusDays(validityDays);
        }
    }

    /**
     * キャンセルに遷移する。
     */
    public void cancel() {
        this.status = TicketBookStatus.CANCELLED;
    }

    /**
     * 期限切れに遷移する。
     */
    public void expire() {
        this.status = TicketBookStatus.EXPIRED;
    }

    /**
     * 有効期限を延長する。EXPIRED の場合は ACTIVE に復帰する。
     *
     * @param newExpiresAt 新しい有効期限
     */
    public void extendExpiry(LocalDateTime newExpiresAt) {
        this.expiresAt = newExpiresAt;
        if (this.status == TicketBookStatus.EXPIRED) {
            this.status = TicketBookStatus.ACTIVE;
        }
    }

    /**
     * 全額返金によるキャンセル。
     */
    public void refundFull() {
        this.status = TicketBookStatus.CANCELLED;
    }

    /**
     * 部分返金時の残回数調整。
     *
     * @param adjustedRemaining 返金後の残回数
     */
    public void adjustRemaining(int adjustedRemaining) {
        this.usedTickets = this.totalTickets - adjustedRemaining;
        if (adjustedRemaining == 0) {
            this.status = TicketBookStatus.CANCELLED;
        } else {
            this.status = TicketBookStatus.ACTIVE;
        }
    }

    /**
     * 決済情報を紐付ける。
     *
     * @param paymentId 決済ID
     */
    public void setPaymentId(Long paymentId) {
        this.paymentId = paymentId;
    }
}
