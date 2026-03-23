package com.mannschaft.app.receipt.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.receipt.ReceiptErrorCode;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.dto.DescriptionSuggestionResponse;
import com.mannschaft.app.receipt.dto.DownloadZipRequest;
import com.mannschaft.app.receipt.dto.DownloadZipResponse;
import com.mannschaft.app.receipt.repository.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        // 将来実装: JpaSpecificationExecutor による動的フィルタリング（年・発行日範囲・無効化フラグ）
        // 最大 10,000 件チェック

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // BOM 付き UTF-8
        try {
            baos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));
            writer.println("領収書番号,発行日,受領者名,但し書き,税込金額,税抜金額,税額,税率,支払方法,支払日,適格請求書,登録番号,無効化");
            // TODO: 実際のデータ出力
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

        DownloadZipResponse response = DownloadZipResponse.builder()
                .jobId(jobId)
                .status("GENERATING")
                .estimatedCount(0) // TODO: 件数を事前に算出
                .build();

        zipJobs.put(jobId, response);

        // TODO: 非同期で ZIP を生成して S3 にアップロード
        log.info("ZIP ダウンロードジョブ作成: jobId={}", jobId);

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
}
