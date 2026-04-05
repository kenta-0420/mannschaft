package com.mannschaft.app.ticket.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * チケット消化履歴エンティティ。来店ごとに1レコード。
 *
 * <p>BaseEntity を継承しない（created_at / updated_at カラムなし。consumed_at で代替）。</p>
 */
@Entity
@Table(name = "ticket_consumptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TicketConsumptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long bookId;

    @Column(nullable = false)
    private Long consumedBy;

    private Long reservationId;

    private Long serviceRecordId;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime consumedAt = LocalDateTime.now();

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isVoided = false;

    private LocalDateTime voidedAt;

    private Long voidedBy;

    /**
     * 消化を取り消す。
     *
     * @param voidedByUserId 取消したユーザーID
     */
    public void voidConsumption(Long voidedByUserId) {
        this.isVoided = true;
        this.voidedAt = LocalDateTime.now();
        this.voidedBy = voidedByUserId;
    }
}
