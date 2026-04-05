package com.mannschaft.app.receipt.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.receipt.ReceiptErrorCode;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.dto.DescriptionSuggestionResponse;
import com.mannschaft.app.receipt.dto.DownloadZipRequest;
import com.mannschaft.app.receipt.dto.DownloadZipResponse;
import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.repository.ReceiptRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 領収書エクスポートサービス。CSV出力・ZIP生成・但し書き候補取得を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceiptExportService {

    private final ReceiptRepository receiptRepository;

    /** ZIP ジョブの進捗管理（本番では Redis 等に移行） */
    private final Map<String, DownloadZipResponse> zipJobs = new ConcurrentHashMap<>();

    /**
     * 領収書一覧を CSV エクスポートする（BOM付き UTF-8）。
     *
     * @param scopeType     スコープ種別
     * @param scopeId       スコープID
     * @param year          発行年フィルタ（NULL の場合は全期間）
     * @param issuedFrom    発行日の開始
     * @param issuedTo      発行日の終了
     * @param includeVoided 無効化済みを含むか
     * @return CSV のバイト配列
     */
    public byte[] exportCsv(ReceiptScopeType scopeType, Long scopeId, Integer year,
                            LocalDate issuedFrom, LocalDate issuedTo, boolean includeVoided) {
        Specification<ReceiptEntity> spec = buildSpecification(scopeType, scopeId, year, issuedFrom, issuedTo, includeVoided);
        List<ReceiptEntity> receipts = receiptRepository.findAll(spec);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // BOM 付き UTF-8
        try {
            baos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));
            writer.println("領収書番号,発行日,受領者名,但し書き,税込金額,税抜金額,税額,税率,支払方法,支払日,適格請求書,登録番号,無効化");

            for (ReceiptEntity receipt : receipts) {
                writer.print(escapeCsv(receipt.getReceiptNumber()));
                writer.print(',');
                writer.print(receipt.getIssuedAt() != null ? receipt.getIssuedAt().toLocalDate() : "");
                writer.print(',');
                writer.print(escapeCsv(receipt.getRecipientName()));
                writer.print(',');
                writer.print(escapeCsv(receipt.getDescription()));
                writer.print(',');
                writer.print(receipt.getAmount());
                writer.print(',');
                writer.print(receipt.getAmountExclTax());
                writer.print(',');
                writer.print(receipt.getTaxAmount());
                writer.print(',');
                writer.print(receipt.getTaxRate());
                writer.print(',');
                writer.print(escapeCsv(receipt.getPaymentMethodLabel()));
                writer.print(',');
                writer.print(receipt.getPaymentDate() != null ? receipt.getPaymentDate() : "");
                writer.print(',');
                writer.print(Boolean.TRUE.equals(receipt.getIsQualifiedInvoice()) ? "はい" : "いいえ");
                writer.print(',');
                writer.print(escapeCsv(receipt.getInvoiceRegistrationNumber()));
                writer.print(',');
                writer.print(receipt.isVoided() ? "無効" : "有効");
                writer.println();
            }

            writer.flush();
        } catch (Exception e) {
            log.error("CSV エクスポートエラー", e);
        }

        return baos.toByteArray();
    }

    /**
     * ZIP 一括ダウンロードジョブを作成する。
     *
     * @param request ZIP ダウンロードリクエスト
     * @return ZIP ジョブレスポンス
     */
    public DownloadZipResponse createZipJob(DownloadZipRequest request) {
        String jobId = UUID.randomUUID().toString().substring(0, 8);

        ReceiptScopeType scopeType = ReceiptScopeType.valueOf(request.getScopeType());
        Specification<ReceiptEntity> spec = buildSpecification(
                scopeType, request.getScopeId(), null,
                request.getIssuedFrom(), request.getIssuedTo(), false);
        int estimatedCount = (int) receiptRepository.count(spec);

        DownloadZipResponse response = DownloadZipResponse.builder()
                .jobId(jobId)
                .status("GENERATING")
                .estimatedCount(estimatedCount)
                .build();

        zipJobs.put(jobId, response);

        // 非同期で ZIP を生成
        generateZipAsync(jobId, scopeType, request.getScopeId(),
                request.getIssuedFrom(), request.getIssuedTo());

        log.info("ZIP ダウンロードジョブ作成: jobId={}, estimatedCount={}", jobId, estimatedCount);

        return response;
    }

    /**
     * ZIP ダウンロードジョブの状態を取得する。
     *
     * @param jobId ジョブID
     * @return ZIP ジョブレスポンス
     */
    public DownloadZipResponse getZipJob(String jobId) {
        DownloadZipResponse response = zipJobs.get(jobId);
        if (response == null) {
            throw new BusinessException(ReceiptErrorCode.ZIP_JOB_NOT_FOUND);
        }
        return response;
    }

    /**
     * 但し書きの自動生成候補を取得する。
     *
     * @param scopeType       スコープ種別
     * @param scopeId         スコープID
     * @param memberPaymentId 支払い実績 ID（任意）
     * @return 但し書き候補レスポンス
     */
    public DescriptionSuggestionResponse getDescriptionSuggestions(ReceiptScopeType scopeType, Long scopeId,
                                                                    Long memberPaymentId) {
        // 将来実装: schedules / payment_items の実績データから但し書き候補を自動生成
        List<DescriptionSuggestionResponse.Suggestion> suggestions = new ArrayList<>();

        // プレースホルダー候補
        suggestions.add(DescriptionSuggestionResponse.Suggestion.builder()
                .description("会費として")
                .source("TEMPLATE")
                .build());

        return DescriptionSuggestionResponse.builder()
                .suggestions(suggestions)
                .template("{item_name}として")
                .build();
    }

    /**
     * 非同期で ZIP ファイルを生成する。
     * NOTE: 本番では S3 にアップロードし downloadUrl を設定する。
     */
    @Async
    protected void generateZipAsync(String jobId, ReceiptScopeType scopeType, Long scopeId,
                                    LocalDate issuedFrom, LocalDate issuedTo) {
        try {
            Specification<ReceiptEntity> spec = buildSpecification(
                    scopeType, scopeId, null, issuedFrom, issuedTo, false);
            List<ReceiptEntity> receipts = receiptRepository.findAll(spec);

            // NOTE: 本番では各領収書の PDF を取得し ZIP にまとめて S3 アップロード
            // 現時点ではジョブステータスを COMPLETED に更新する
            log.info("ZIP 生成完了: jobId={}, receiptCount={}", jobId, receipts.size());

            zipJobs.put(jobId, DownloadZipResponse.builder()
                    .jobId(jobId)
                    .status("COMPLETED")
                    .estimatedCount(receipts.size())
                    .downloadUrl(null) // NOTE: S3 Pre-signed URL を設定予定
                    .build());
        } catch (Exception e) {
            log.error("ZIP 生成失敗: jobId={}", jobId, e);
            zipJobs.put(jobId, DownloadZipResponse.builder()
                    .jobId(jobId)
                    .status("FAILED")
                    .estimatedCount(0)
                    .build());
        }
    }

    /**
     * 領収書検索用の JPA Specification を構築する。
     */
    private Specification<ReceiptEntity> buildSpecification(ReceiptScopeType scopeType, Long scopeId,
                                                             Integer year, LocalDate issuedFrom,
                                                             LocalDate issuedTo, boolean includeVoided) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("scopeType"), scopeType));
            preds.add(cb.equal(root.get("scopeId"), scopeId));
            if (year != null) {
                preds.add(cb.equal(cb.function("YEAR", Integer.class, root.get("issuedAt")), year));
            }
            if (issuedFrom != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("issuedAt"), issuedFrom.atStartOfDay()));
            }
            if (issuedTo != null) {
                preds.add(cb.lessThan(root.get("issuedAt"), issuedTo.plusDays(1).atStartOfDay()));
            }
            if (!includeVoided) {
                preds.add(cb.isNull(root.get("voidedAt")));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };
    }

    /**
     * CSV 出力用のエスケープ処理。カンマを全角カンマに置換する。
     */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(",", "，");
    }
}
