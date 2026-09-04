package com.mannschaft.app.receipt.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.receipt.ReceiptArchiveKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 領収書 PDF 原本アーカイブ（F08.12 §3.3。電子帳簿保存法）。
 *
 * <p>一意制約が {@code (receipt_id, archive_kind)} なのは、無効化時に「無効」表示の PDF を
 * <b>同じ領収書の新しい行として追加する</b>ためである。{@code receipt_id} 単独の一意制約では
 * void が制約違反で失敗する。元の {@code ORIGINAL} 行は書き換えも削除もされない。</p>
 *
 * <p>PDF の生成・保存状態（失敗の記録）は本表ではなく {@code receipts.pdf_status} 側にある。
 * 本表は「成功した原本だけを載せる表」であり、失敗は原本ではないため置けない。</p>
 */
@Entity
@Table(name = "receipt_pdf_archives",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_rpa_receipt_kind",
                columnNames = {"receipt_id", "archive_kind"}),
        indexes = {
                @Index(name = "idx_rpa_receipt", columnList = "receipt_id"),
                @Index(name = "idx_rpa_retention", columnList = "retention_until")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ReceiptPdfArchiveEntity extends UuidV7Entity {

    /** 対象領収書。同一 receipt ドメイン内のため FK 可（設計原則 1）。 */
    @Column(name = "receipt_id", nullable = false)
    private Long receiptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "archive_kind", nullable = false, length = 20)
    private ReceiptArchiveKind archiveKind;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    /** PDF 原本の SHA-256（改ざん検知）。 */
    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(name = "byte_size", nullable = false)
    private Long byteSize;

    @Column(name = "archived_at", nullable = false)
    @Builder.Default
    private LocalDateTime archivedAt = LocalDateTime.now();

    /** 保存期限（{@code archived_at} + 7 年）。 */
    @Column(name = "retention_until", nullable = false)
    private LocalDate retentionUntil;

    /**
     * 実際に効いた不変性の担保手段（{@code S3_OBJECT_LOCK} / {@code R2_BUCKET_LOCK} /
     * {@code APP_ONLY}）。R2 と S3 で担保できる強度が違うため、監査時に「どの手段で
     * 守られた原本か」を説明できるよう行として残す。
     */
    @Column(name = "retention_backend", nullable = false, length = 30)
    private String retentionBackend;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
