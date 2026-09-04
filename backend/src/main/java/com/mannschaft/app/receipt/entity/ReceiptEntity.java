package com.mannschaft.app.receipt.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.common.EncryptedStringConverter;
import com.mannschaft.app.receipt.ReceiptPdfStatus;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.ReceiptSourceType;
import com.mannschaft.app.receipt.ReceiptStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Check;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
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
@Table(
        name = "receipts",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_r_active_platform_source",
                columnNames = "active_platform_source_key"),
        indexes = @Index(name = "idx_r_source", columnList = "source_type, source_ref"))
@Check(name = "ck_r_platform_requires_source", constraints =
        "scope_type <> 'PLATFORM' OR (source_type IS NOT NULL AND source_ref IS NOT NULL)")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
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

    // ===========================================
    // F08.12: 元データの汎用参照（member_payment_id は既存互換のため残す）
    // ===========================================

    /**
     * 元データ種別。文字列表現は {@link com.mannschaft.app.receipt.ReceiptSourceRef} が規約を持つ。
     *
     * <p>クロスドメイン FK は張らない（設計原則 1）。整合性はアプリ層で保証する。</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 30)
    private ReceiptSourceType sourceType;

    /** 元データ ID の文字列表現（BIGINT 系は 10 進、UUID 系は小文字 36 文字）。 */
    @Column(name = "source_ref", length = 64)
    private String sourceRef;

    // ===========================================
    // F08.12: PDF の生成・保存状態（証憑の状態機械とは独立した軸）
    // ===========================================

    /**
     * PDF 生成状態。
     *
     * <p><b>{@code columnDefinition} で DEFAULT を明示している理由</b>: 統合テストのスキーマは
     * Flyway ではなく Entity から生成される（{@code ddl-auto: create} / {@code flyway.enabled: false}）。
     * DEFAULT を書かないと、この列を明示しない生 SQL の INSERT が
     * 「Field 'pdf_status' doesn't have a default value」で全滅する。</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "pdf_status", nullable = false, length = 20,
            columnDefinition = "VARCHAR(20) NOT NULL DEFAULT 'GENERATING'")
    @Builder.Default
    private ReceiptPdfStatus pdfStatus = ReceiptPdfStatus.GENERATING;

    /** PDF 生成・保存の試行回数。<b>失敗時に加算</b>する（試行の直前ではない）。 */
    @Column(name = "pdf_attempt_count", nullable = false,
            columnDefinition = "INT UNSIGNED NOT NULL DEFAULT 0")
    @Builder.Default
    private Integer pdfAttemptCount = 0;

    /** 直近の失敗時刻。 */
    @Column(name = "pdf_failed_at")
    private LocalDateTime pdfFailedAt;

    /** 直近の失敗理由（エラーコード + 要約。スタックトレースは入れない）。 */
    @Column(name = "pdf_failure_reason", length = 500)
    private String pdfFailureReason;

    /**
     * 有効な PLATFORM 領収書の重複防止キー。<b>MySQL の STORED 生成列</b>であり Java からは
     * 読み取り専用。{@code uq_r_active_platform_source} と対で「有効な PLATFORM 領収書は
     * 同一 source につき 1 通まで／無効化されたものは何通でも並ぶ」を<b>原子的に</b>表現する。
     *
     * <p><b>{@code columnDefinition} に生成式を書いている理由（この罠で過去に事故がある）</b>:
     * 統合テストのスキーマは Flyway ではなく Entity から生成されるため、Flyway に生成列を
     * 書くだけでは IT のスキーマに現れない。それでもテストは「重複しない」ことを観測できて
     * しまうため、<b>CI 緑・本番で重複が通る</b>という最悪の偽陰性になる。
     * {@code RecruitmentParticipantEntity#activeSubjectKey} と
     * {@code TicketBookEntity#remainingTickets} はいずれも生成式を宣言しておらず、
     * テストスキーマでは素の列として作られている。本列はその前例に<b>倣わず</b>、
     * 生成式まで宣言して Flyway と一致させる。</p>
     *
     * <p><b>{@code nullable} を書かない理由</b>: {@code insertable=false, updatable=false} の
     * 生成列に {@code nullable} を付けても Hibernate は INSERT/UPDATE 文に列を含めないため
     * 実質的に意味がなく、逆に NOT NULL 化して保存が全滅した前例がある
     * （{@code RecruitmentParticipantEntity} の Javadoc に実測記録あり）。</p>
     *
     * <p>区切りに {@code 0x1F}（Unit Separator）を使うのは、素朴な連結だと
     * {@code "AD" + "1_2"} と {@code "AD_1" + "2"} が同じ文字列になりうるためである。</p>
     */
    @Column(name = "active_platform_source_key", insertable = false, updatable = false,
            columnDefinition = "VARCHAR(110) GENERATED ALWAYS AS ("
                    + "CASE WHEN scope_type = 'PLATFORM' AND voided_at IS NULL "
                    + "AND source_type IS NOT NULL AND source_ref IS NOT NULL "
                    + "THEN CONCAT(source_type, 0x1F, source_ref) ELSE NULL END) STORED")
    private String activePlatformSourceKey;

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

    /**
     * PLATFORM（運営）スコープの領収書かどうか。
     */
    public boolean isPlatformScope() {
        return this.scopeType != null && this.scopeType.isPlatform();
    }

    /**
     * 元データ参照を設定する（発行時のみ。証憑の内容に相当するため以後変更しない）。
     */
    public void assignSource(ReceiptSourceType sourceType, String sourceRef) {
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
    }

    /**
     * PDF の生成・保存が完了したことを記録する。
     */
    public void markPdfReady() {
        this.pdfStatus = ReceiptPdfStatus.READY;
        this.pdfFailedAt = null;
        this.pdfFailureReason = null;
    }

    /**
     * PDF の生成・保存に失敗したことを記録する。試行回数はここで加算する
     * （値 5 は「5 回失敗済み」を意味する）。
     *
     * @param failureReason エラーコード + 要約。スタックトレースは入れない
     */
    public void markPdfFailed(String failureReason) {
        this.pdfStatus = ReceiptPdfStatus.FAILED;
        this.pdfAttemptCount = (this.pdfAttemptCount == null ? 0 : this.pdfAttemptCount) + 1;
        this.pdfFailedAt = LocalDateTime.now();
        this.pdfFailureReason = failureReason == null ? null
                : failureReason.substring(0, Math.min(failureReason.length(), 500));
    }
}
