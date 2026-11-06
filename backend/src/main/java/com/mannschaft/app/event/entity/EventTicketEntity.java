package com.mannschaft.app.event.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.event.TicketStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import com.mannschaft.app.common.token.PlaintextSecretRequired;

/**
 * イベントチケットエンティティ。発行されたチケットの状態を管理する。
 */
@Entity
@Table(name = "event_tickets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class EventTicketEntity extends BaseEntity {

    @Column(nullable = false)
    private Long registrationId;

    @Column(nullable = false)
    private Long eventId;

    @Column(nullable = false)
    private Long ticketTypeId;

    @PlaintextSecretRequired(reason = "保有者本人にチケットの QR を何度でも見せる機能の本質のため平文で保持する。署名付き短命 QR への移行は別 CMP")
    @Column(nullable = false, length = 36)
    private String qrToken;

    @Column(nullable = false, length = 30)
    private String ticketNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TicketStatus status = TicketStatus.VALID;

    private LocalDateTime usedAt;

    private LocalDateTime cancelledAt;

    /**
     * チケットを使用済みにする。
     */
    public void use() {
        this.status = TicketStatus.USED;
        this.usedAt = LocalDateTime.now();
    }

    /**
     * チケットをキャンセルする。
     */
    public void cancel() {
        this.status = TicketStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
    }
}
