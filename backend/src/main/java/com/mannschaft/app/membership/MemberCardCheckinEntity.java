package com.mannschaft.app.membership;

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

import java.time.LocalDateTime;

/**
 * チェックイン履歴エンティティ。QRスキャンによる来店・入場の記録。
 */
@Entity
@Table(name = "member_card_checkins")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MemberCardCheckinEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberCardId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CheckinType checkinType;

    private Long checkedInBy;

    private Long checkinLocationId;

    @Column(nullable = false)
    private LocalDateTime checkedInAt;

    @Column(length = 200)
    private String location;

    @Column(length = 500)
    private String note;

    private Long reservationId;

    private Long serviceRecordId;

    private Long transactionId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        if (this.checkedInAt == null) {
            this.checkedInAt = now;
        }
        if (this.checkinType == null) {
            this.checkinType = CheckinType.STAFF_SCAN;
        }
    }
}
