package com.mannschaft.app.proxy.service;

import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 代理入力の月次サマリPDF生成サービス（F14.1 Phase 13-β）。
 * 代理入力対象住民（subjectUserId）ごとに月次サマリPDFを生成しS3に保存する。
 * proxy_input_records テーブルには organizationId が存在しないため、
 * subjectUserId のみでグループ化する。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProxyMonthlySummaryService {

    private final ProxyInputRecordRepository recordRepository;
    private final PdfGeneratorService pdfGeneratorService;
    private final StorageService storageService;

    /** S3キーのプレフィックス。 */
    static final String S3_KEY_PREFIX = "proxy-monthly-summaries";

    /**
     * 指定月の月次サマリPDFを全住民分生成しS3に保存する。
     *
     * @param targetMonth 対象年月
     * @return 生成したPDFの件数
     */
    @Transactional(readOnly = true)
    public int generateForMonth(YearMonth targetMonth) {
        LocalDateTime fromDate = targetMonth.atDay(1).atStartOfDay();
        LocalDateTime toDate = targetMonth.plusMonths(1).atDay(1).atStartOfDay();

        List<ProxyInputRecordEntity> allRecords = recordRepository.findForMonthlySummary(fromDate, toDate);

        if (allRecords.isEmpty()) {
            log.info("月次サマリ対象レコードなし: {}", targetMonth);
            return 0;
        }

        // subjectUserId でグループ化して住民ごとにPDFを生成する
        Map<Long, List<ProxyInputRecordEntity>> bySubject =
                allRecords.stream()
                        .collect(Collectors.groupingBy(ProxyInputRecordEntity::getSubjectUserId));

        int count = 0;
        for (Map.Entry<Long, List<ProxyInputRecordEntity>> entry : bySubject.entrySet()) {
            Long subjectUserId = entry.getKey();
            List<ProxyInputRecordEntity> records = entry.getValue();

            generateAndUpload(subjectUserId, records, targetMonth);
            count++;
        }

        log.info("月次サマリPDF生成完了: {}件 ({})", count, targetMonth);
        return count;
    }

    /**
     * 単一ユーザーの月次サマリPDFを生成してS3にアップロードする。
     *
     * @param subjectUserId 本人ユーザーID
     * @param records       対象月の代理入力レコード一覧
     * @param targetMonth   対象年月
     */
    private void generateAndUpload(Long subjectUserId,
                                   List<ProxyInputRecordEntity> records,
                                   YearMonth targetMonth) {
        // 機能スコープ別の件数集計
        Map<String, Long> byFeatureScope = records.stream()
                .collect(Collectors.groupingBy(ProxyInputRecordEntity::getFeatureScope, Collectors.counting()));

        // Thymeleafテンプレートに渡す変数を構築する
        Map<String, Object> variables = Map.of(
                "year", targetMonth.getYear(),
                "month", targetMonth.getMonthValue(),
                "subjectUserId", subjectUserId,
                "totalCount", records.size(),
                "byFeatureScope", byFeatureScope,
                "generatedAt", LocalDate.now().toString()
        );

        // PDF生成 & S3アップロード
        byte[] pdfBytes = pdfGeneratorService.generateFromTemplate("pdf/proxy-monthly-summary", variables);
        String s3Key = buildS3Key(subjectUserId, targetMonth);
        storageService.upload(s3Key, pdfBytes, "application/pdf");
        log.debug("月次サマリPDFアップロード完了: {}", s3Key);
    }

    /**
     * S3キーを構築する。
     * 形式: proxy-monthly-summaries/{subjectUserId}/{year}/{month:02d}/summary.pdf
     *
     * @param subjectUserId 本人ユーザーID
     * @param targetMonth   対象年月
     * @return S3オブジェクトキー
     */
    String buildS3Key(Long subjectUserId, YearMonth targetMonth) {
        return String.format("%s/%d/%04d/%02d/summary.pdf",
                S3_KEY_PREFIX, subjectUserId,
                targetMonth.getYear(), targetMonth.getMonthValue());
    }

    /**
     * 月次サマリPDFのダウンロードURLを生成する（ADMIN向け）。
     *
     * @param subjectUserId 本人ユーザーID
     * @param targetMonth   対象年月
     * @return presigned GET URL（5分TTL）
     */
    public String getDownloadUrl(Long subjectUserId, YearMonth targetMonth) {
        String s3Key = buildS3Key(subjectUserId, targetMonth);
        return storageService.generateDownloadUrl(s3Key, Duration.ofMinutes(5));
    }
}
