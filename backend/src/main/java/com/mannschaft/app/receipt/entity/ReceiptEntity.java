package com.mannschaft.app.receipt.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.common.EncryptedStringConverter;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.ReceiptStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 発行済み領収書エンティティ。法的文書のため論理削除不可。取り消しは voided_at で管理。
 * 個人情報フィールドはAES-256-GCMで暗号化して保存する。PDF原本（S3）が法的正本。
 */
@Entity
@Table(name = "receipts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ReceiptEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReceiptScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReceiptStatus status = ReceiptStatus.ISSUED;

    @Column(length = 50)
    private String receiptNumber;

    private Long memberPaymentId;

    private Long recipientUserId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String recipientName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String recipientPostalCode;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String recipientAddress;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String issuerName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String issuerPostalCode;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String issuerAddress;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String issuerPhone;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isQualifiedInvoice = false;

    @Column(length = 14)
    private String invoiceRegistrationNumber;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false, precision = 10, scale = 0)
    private BigDecimal amount;

    @Column(nullable = false, precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal taxRate = new BigDecimal("10.00");

    @Column(nullable = false, precision = 10, scale = 0)
    private BigDecimal taxAmount;

    @Column(nullable = false, precision = 10, scale = 0)
    private BigDecimal amountExclTax;

    @Column(length = 50)
    private String paymentMethodLabel;

    @Column(nullable = false)
    private LocalDate paymentDate;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime issuedAt = LocalDateTime.now();

    @Column(nullable = false)
    private Long issuedBy;

    private Long sealStampLogId;

    @Column(length = 500)
    private String pdfStorageKey;

    private Long scheduleId;

    private LocalDateTime voidedAt;

    private Long voidedBy;

    @Column(length = 500)
    private String voidedReason;

    @Column(nullable = false)
    @Builder.Default
    private Integer encryptionKeyVersion = 1;

    /**
     * 領収書を無効化する。
     *
     * @param voidedBy    無効化した ADMIN のユーザー ID
     * @param voidedReason 無効化理由
     */
    public void voidReceipt(Long voidedBy, String voidedReason) {
        this.voidedAt = LocalDateTime.now();
        this.voidedBy = voidedBy;
        this.voidedReason = voidedReason;
    }

    /**
     * 無効化済みかどうかを判定する。
     */
    public boolean isVoided() {
        return this.voidedAt != null;
    }

    /**
     * 領収書番号を設定する（DRAFT → ISSUED 遷移時）。
     */
    public void assignReceiptNumber(String receiptNumber) {
        this.receiptNumber = receiptNumber;
    }

    /**
     * ステータスを ISSUED に遷移する。
     */
    public void approve() {
        this.status = ReceiptStatus.ISSUED;
        this.issuedAt = LocalDateTime.now();
    }

    /**
     * PDF ストレージキーを設定する。
     */
    public void updatePdfStorageKey(String pdfStorageKey) {
        this.pdfStorageKey = pdfStorageKey;
    }

    /**
     * 押印記録 ID を設定する。
     */
    public void updateSealStampLogId(Long sealStampLogId) {
        this.sealStampLogId = sealStampLogId;
    }
}
