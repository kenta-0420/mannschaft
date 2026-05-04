package com.mannschaft.app.school.entity;

import com.mannschaft.app.common.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** 出席要件開示判断記録エンティティ（F03.13 Phase 15）。 */
@Entity
@Table(name = "attendance_disclosure_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AttendanceDisclosureRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK→attendance_requirement_evaluations.id */
    @Column(nullable = false)
    private Long evaluationId;

    /** 対象生徒 FK→users.id */
    @Column(nullable = false)
    private Long studentUserId;

    /** 開示判断（開示/非開示） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DisclosureDecision decision;

    /** 開示モード（DISCLOSED時のみ） */
    @Enumerated(EnumType.STRING)
    @Column(length = 25)
    private DisclosureMode mode;

    /** 通知先（DISCLOSED時のみ） */
    @Enumerated(EnumType.STRING)
    @Column(length = 15)
    private DisclosureRecipients recipients;

    /** 担任メッセージ（AES-256-GCM暗号化） */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private String message;

    /** 非開示理由（AES-256-GCM暗号化・WITHHELD時のみ） */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private String withholdReason;

    /** 判断者のユーザーID（担任等） FK→users.id */
    @Column(nullable = false)
    private Long decidedBy;

    /** 判断日時（DB DEFAULT CURRENT_TIMESTAMP） */
    @Column(nullable = false, insertable = true, updatable = false)
    @Builder.Default
    private LocalDateTime decidedAt = LocalDateTime.now();

    /** 通知配信レコードID（DISCLOSED時のみ・将来拡張） */
    private Long notificationId;

    /** 開示判断種別。 */
    public enum DisclosureDecision {
        /** 開示 */
        DISCLOSED,
        /** 非開示 */
        WITHHELD
    }

    /** 開示モード。 */
    public enum DisclosureMode {
        /** 数値あり（出席率・残余日数を含めて開示） */
        WITH_NUMBERS,
        /** 数値なし（ステータスのみ開示） */
        WITHOUT_NUMBERS,
        /** 面談要請のみ */
        MEETING_REQUEST_ONLY
    }

    /** 通知先。 */
    public enum DisclosureRecipients {
        /** 生徒のみ */
        STUDENT_ONLY,
        /** 保護者のみ */
        GUARDIAN_ONLY,
        /** 生徒と保護者の両方 */
        BOTH
    }
}
