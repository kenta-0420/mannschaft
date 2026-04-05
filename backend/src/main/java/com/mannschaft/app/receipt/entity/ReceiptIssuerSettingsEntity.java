package com.mannschaft.app.receipt.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.common.EncryptedStringConverter;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.SealVariant;
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

/**
 * 領収書発行者設定エンティティ。チーム/組織ごとに1レコード。
 * 発行者名・住所・電話番号はAES-256-GCMで暗号化して保存する。
 */
@Entity
@Table(name = "receipt_issuer_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ReceiptIssuerSettingsEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReceiptScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String issuerName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String postalCode;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String address;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String phone;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isQualifiedInvoicer = false;

    @Column(length = 14)
    private String invoiceRegistrationNumber;

    private Long defaultSealUserId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SealVariant defaultSealVariant;

    @Column(columnDefinition = "TEXT")
    private String receiptNoteTemplate;

    @Column(length = 500)
    private String logoStorageKey;

    @Column(columnDefinition = "TEXT")
    private String customFooter;

    @Column(nullable = false)
    @Builder.Default
    private Integer nextReceiptNumber = 1;

    @Column(length = 20)
    private String receiptNumberPrefix;

    @Column(nullable = false)
    @Builder.Default
    private Integer fiscalYearStartMonth = 4;

    @Column(nullable = false)
    @Builder.Default
    private Boolean autoResetNumber = true;

    @Column(nullable = false)
    @Builder.Default
    private Integer encryptionKeyVersion = 1;

    /**
     * 発行者設定を更新する。
     */
    public void update(String issuerName, String postalCode, String address, String phone,
                       Boolean isQualifiedInvoicer, String invoiceRegistrationNumber,
                       Long defaultSealUserId, SealVariant defaultSealVariant,
                       String receiptNoteTemplate, String receiptNumberPrefix,
                       Integer fiscalYearStartMonth, Boolean autoResetNumber,
                       String customFooter) {
        this.issuerName = issuerName;
        this.postalCode = postalCode;
        this.address = address;
        this.phone = phone;
        this.isQualifiedInvoicer = isQualifiedInvoicer;
        this.invoiceRegistrationNumber = invoiceRegistrationNumber;
        this.defaultSealUserId = defaultSealUserId;
        this.defaultSealVariant = defaultSealVariant;
        this.receiptNoteTemplate = receiptNoteTemplate;
        this.receiptNumberPrefix = receiptNumberPrefix;
        this.fiscalYearStartMonth = fiscalYearStartMonth;
        this.autoResetNumber = autoResetNumber;
        this.customFooter = customFooter;
    }

    /**
     * ロゴのストレージキーを設定する。
     */
    public void updateLogoStorageKey(String logoStorageKey) {
        this.logoStorageKey = logoStorageKey;
    }

    /**
     * 領収書番号をインクリメントし、現在の番号を返す。
     *
     * @param count 採番する件数
     * @return 開始番号（インクリメント前の値）
     */
    public int incrementReceiptNumber(int count) {
        int start = this.nextReceiptNumber;
        this.nextReceiptNumber += count;
        return start;
    }

    /**
     * 年度リセットを実行する。
     *
     * @param newPrefix 新しいプレフィックス
     */
    public void resetForNewFiscalYear(String newPrefix) {
        this.nextReceiptNumber = 1;
        this.receiptNumberPrefix = newPrefix;
    }
}
