package com.mannschaft.app.receipt.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 保存期限が到来した PDF 原本アーカイブの一覧要素（F08.12 §9.5 AC-77）。
 *
 * <p>一覧のみを提供し、削除は行わない（削除バッチは意図的に作らない。§9.5）。</p>
 */
@Getter
@RequiredArgsConstructor
public class RetentionExpiredArchiveResponse {

    private final Long receiptId;
    private final String archiveKind;
    private final String storageKey;
    private final Instant archivedAt;
    private final LocalDate retentionUntil;
    private final String retentionBackend;
}
