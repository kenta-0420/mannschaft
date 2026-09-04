package com.mannschaft.app.receipt.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.receipt.ReceiptArchiveKind;
import com.mannschaft.app.receipt.ReceiptErrorCode;
import com.mannschaft.app.receipt.ReceiptPdfGenerator;
import com.mannschaft.app.receipt.ReceiptPdfStatus;
import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.entity.ReceiptLineItemEntity;
import com.mannschaft.app.receipt.entity.ReceiptPdfArchiveEntity;
import com.mannschaft.app.receipt.repository.ReceiptPdfArchiveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 領収書 PDF 原本保存基盤（F08.12 §9 電子帳簿保存法対応）。
 *
 * <p><b>是正した実バグ（AC-32 / AC-38 / AC-39）</b>: 旧実装は
 * {@code ReceiptEntity.updatePdfStorageKey()} の呼び出し元が 0 件であり、
 * PDF は取得のたびにオンデマンド生成されるだけで原本という概念が無かった。
 * 本クラスは初回取得時に生成 PDF をストレージへ保存し {@code receipt_pdf_archives} に
 * 記録したうえで、2 回目以降は<b>保存済みの原本をそのまま返す</b>（再生成しない）。</p>
 *
 * <p><b>参照方法</b>: {@code (receipt_id, archive_kind)} の一意制約を使い、
 * アーカイブ表を正として引く（設計書 §3.4.1）。{@code receipts.pdf_storage_key} は
 * {@code ORIGINAL} のキーのキャッシュに過ぎず、本クラスは {@code ORIGINAL} 生成時にのみ
 * それを書き込む。{@code VOIDED} のキーは絶対にこの列へ書かない。</p>
 *
 * <p><b>ハッシュ検証（3 層防御の第 3 層。§9.3）</b>: 読み出しのたびに
 * {@code content_sha256} を照合し、不一致なら {@code RECEIPT_033} で拒否する。
 * R2 が S3 Object Lock ほど強い不変性を担保できない分をここで埋める。</p>
 *
 * <p><b>再試行上限（AC-51 / AC-52）</b>: {@code pdf_status = FAILED} かつ
 * {@code pdf_attempt_count >= 5} の場合は再試行せず {@code RECEIPT_031} を返す
 * （無限リトライで外部ストレージを叩き続けない）。</p>
 *
 * <p><b>新しいストレージ実装は起こさない</b>: 設計書 §9.3 が規定する
 * {@code ImmutableArchiveService}（S3 Object Lock / R2 Bucket Lock 別実装）は本クラスでは
 * 導入しない。R2 Bucket Lock の設定は手作業（§9.3.1・本戦役スコープ外）であるため、
 * アプリからは「どの手段で守られているか」を設定値として受け取るに留める。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptPdfArchiveService {

    /** 再試行上限。5 回失敗済みなら 6 回目は試みない（AC-51 / AC-52）。 */
    private static final int MAX_RETRY_ATTEMPT = 5;

    /** 保存期限（7 年。§9.5）。 */
    private static final int RETENTION_YEARS = 7;

    private final ReceiptPdfArchiveRepository archiveRepository;
    private final StorageService storageService;
    private final ReceiptPdfGenerator pdfGenerator;

    /**
     * 実際に効いた不変性の担保手段。ローカル/CI は保持ルールを掛けない
     * （§9.4 御裁可済み）ため既定は {@code local}。本番は運用手順（§9.3.1）で
     * Bucket Lock を設定したうえでこの値を切り替える。
     */
    @Value("${mannschaft.storage.archive.backend:local}")
    private String archiveBackend;

    /**
     * 領収書 PDF を取得する。保存済みの原本があればそれを返し、無ければ生成して保存する。
     *
     * <p><b>パッケージ非公開</b>（{@code com.mannschaft.app.receipt.service} 内の同居サービスのみ
     * 呼ぶ）。Entity をそのまま引数に取るため、{@code public} にすると
     * {@code ServiceApiEntityBoundaryArchTest}（D-1 API 境界）に抵触する
     * （他ドメインから呼ばれうる Service API は Entity を公開してはならない）。
     * {@link ReceiptService} / {@link ReceiptMyService} は同一パッケージのため到達できる。</p>
     *
     * @param receipt       対象領収書
     * @param lineItems     明細行
     * @param requestedKind 明示指定された種別。{@code null} の場合は現在の状態
     *                      （{@code voided_at}）から解決する（設計書 §3.4.1）
     * @return PDF バイト配列
     */
    @Transactional
    byte[] getOrArchive(ReceiptEntity receipt, List<ReceiptLineItemEntity> lineItems,
                         ReceiptArchiveKind requestedKind) {
        ReceiptArchiveKind kind = resolveKind(receipt, requestedKind);

        // VOIDED を明示指定（または既定解決）されたが実際には無効化されていない場合は
        // 原本が存在しえないので 404 とする（AC-55）。
        if (kind == ReceiptArchiveKind.VOIDED && !receipt.isVoided()) {
            throw new BusinessException(ReceiptErrorCode.PDF_ARCHIVE_NOT_FOUND);
        }

        Optional<ReceiptPdfArchiveEntity> existing =
                archiveRepository.findByReceiptIdAndArchiveKind(receipt.getId(), kind);
        if (existing.isPresent()) {
            return readAndVerify(existing.get());
        }

        return generateAndArchive(receipt, lineItems, kind);
    }

    private ReceiptArchiveKind resolveKind(ReceiptEntity receipt, ReceiptArchiveKind requestedKind) {
        if (requestedKind != null) {
            return requestedKind;
        }
        // 既定 = 現在の状態に対応する PDF。無効化済みなら VOIDED、そうでなければ ORIGINAL。
        return receipt.isVoided() ? ReceiptArchiveKind.VOIDED : ReceiptArchiveKind.ORIGINAL;
    }

    private byte[] readAndVerify(ReceiptPdfArchiveEntity archive) {
        byte[] content = storageService.download(archive.getStorageKey());
        String actualHash = sha256Hex(content);
        if (!actualHash.equalsIgnoreCase(archive.getContentSha256())) {
            log.error("領収書PDF原本のハッシュ不一致を検知（改ざんの疑い）: archiveId={}, receiptId={}, "
                            + "storageKey={}, expected={}, actual={}",
                    archive.getId(), archive.getReceiptId(), archive.getStorageKey(),
                    archive.getContentSha256(), actualHash);
            throw new BusinessException(ReceiptErrorCode.ARCHIVE_INTEGRITY_MISMATCH);
        }
        return content;
    }

    private byte[] generateAndArchive(ReceiptEntity receipt, List<ReceiptLineItemEntity> lineItems,
                                       ReceiptArchiveKind kind) {
        Integer attemptCount = receipt.getPdfAttemptCount();
        if (receipt.getPdfStatus() == ReceiptPdfStatus.FAILED
                && attemptCount != null && attemptCount >= MAX_RETRY_ATTEMPT) {
            throw new BusinessException(ReceiptErrorCode.PDF_RETRY_LIMIT_EXCEEDED);
        }

        byte[] content = kind == ReceiptArchiveKind.VOIDED
                ? pdfGenerator.generateVoided(receipt, lineItems, null, null, null)
                : pdfGenerator.generate(receipt, lineItems, null, null, null);

        String hash = sha256Hex(content);
        Instant now = Instant.now();
        LocalDate archivedDate = now.atZone(ZoneOffset.UTC).toLocalDate();
        String key = buildStorageKey(receipt, kind, archivedDate, hash);

        try {
            storageService.upload(key, content, "application/pdf");
        } catch (RuntimeException e) {
            // 対処療法禁止: 握りつぶさず FAILED として永続化し、呼び出し元にもエラーを返す。
            // receipt_pdf_archives には行を作らない（AC-31）。
            log.error("領収書PDF原本の保存に失敗: receiptId={}, kind={}", receipt.getId(), kind, e);
            receipt.markPdfFailed(ReceiptErrorCode.ARCHIVE_WRITE_FAILED.getCode() + ": " + e.getMessage());
            throw new BusinessException(ReceiptErrorCode.ARCHIVE_WRITE_FAILED);
        }

        ReceiptPdfArchiveEntity archive = ReceiptPdfArchiveEntity.builder()
                .receiptId(receipt.getId())
                .archiveKind(kind)
                .storageKey(key)
                .contentSha256(hash)
                .byteSize((long) content.length)
                .archivedAt(now)
                .retentionUntil(archivedDate.plusYears(RETENTION_YEARS))
                .retentionBackend(resolveRetentionBackend())
                .build();

        try {
            archiveRepository.save(archive);
        } catch (DataAccessException e) {
            // 一意制約 (receipt_id, archive_kind) 違反は並行取得の競合。保存自体は失敗ではないため
            // FAILED にはせず、既存行を読み直して返す（再試行で解決する競合であり握りつぶしではない）。
            log.warn("アーカイブ行の保存が競合と衝突: receiptId={}, kind={}", receipt.getId(), kind, e);
            return archiveRepository.findByReceiptIdAndArchiveKind(receipt.getId(), kind)
                    .map(this::readAndVerify)
                    .orElseThrow(() -> e);
        }

        // pdf_storage_key は ORIGINAL のキーのキャッシュ。VOIDED のキーは絶対に書かない（§3.4.1）。
        if (kind == ReceiptArchiveKind.ORIGINAL) {
            receipt.updatePdfStorageKey(key);
        }
        receipt.markPdfReady();

        return content;
    }

    /**
     * キー設計（§9.3）: {@code receipts/{scopeType}/{yyyy}/{receiptId}/{sha256先頭16桁}.pdf}。
     * 内容が変われば必ず別キーになるため、同一キーへの上書きが原理的に発生しない（AC-76）。
     */
    private String buildStorageKey(ReceiptEntity receipt, ReceiptArchiveKind kind,
                                    LocalDate archivedDate, String hash) {
        String hashPrefix = hash.substring(0, Math.min(16, hash.length()));
        return "receipts/" + receipt.getScopeType().name() + "/" + archivedDate.getYear()
                + "/" + receipt.getId() + "/" + kind.name().toLowerCase(Locale.ROOT)
                + "-" + hashPrefix + ".pdf";
    }

    private String resolveRetentionBackend() {
        return switch (archiveBackend) {
            case "s3-object-lock" -> "S3_OBJECT_LOCK";
            case "r2-bucket-lock" -> "R2_BUCKET_LOCK";
            default -> "APP_ONLY";
        };
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // JVM は SHA-256 を必ず持つ（標準アルゴリズム）。到達しない防御的例外変換。
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
