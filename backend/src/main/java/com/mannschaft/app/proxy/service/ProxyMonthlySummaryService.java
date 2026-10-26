package com.mannschaft.app.proxy.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
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
    private final ProxyInputConsentRepository consentRepository;
    private final PdfGeneratorService pdfGeneratorService;
    private final StorageService storageService;
    private final AccessControlService accessControlService;

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
     * 月次サマリPDFのダウンロードURLを生成する（本人 or 管理者向け）。
     *
     * <p><b>認可</b>: 同一ドメインの {@code ProxyInputConsentService#generateScanDownloadUrl} と
     * 同じ「本人 or ADMIN」の作法に揃える。{@code proxy_input_records} は
     * {@code organizationId} を持たないため、対象住民（{@code subjectUserId}）の
     * <b>同意書 entity 由来の {@code organizationId}</b> を管理権原の判定軸とする
     * （path の値をそのまま権限スコープとして扱わない＝BOLA 封鎖）。</p>
     *
     * <ul>
     *   <li>本人（{@code requestUserId == subjectUserId}）: 許可</li>
     *   <li>SYSTEM_ADMIN: 許可</li>
     *   <li>対象住民の未失効同意書が属するいずれかの組合で ADMIN/DEPUTY_ADMIN: 許可</li>
     *   <li>いずれにも該当しない: 403（{@code COMMON_002}）</li>
     * </ul>
     *
     * @param requestUserId 操作者ID
     * @param subjectUserId 本人ユーザーID
     * @param targetMonth   対象年月
     * @return presigned GET URL（5分TTL）
     */
    public String getDownloadUrl(Long requestUserId, Long subjectUserId, YearMonth targetMonth) {
        authorizeSummaryAccess(requestUserId, subjectUserId);
        String s3Key = buildS3Key(subjectUserId, targetMonth);
        return storageService.generateDownloadUrl(s3Key, Duration.ofMinutes(5));
    }

    /**
     * 月次サマリ参照の認可（本人 / SYSTEM_ADMIN / 対象住民の同意書組合の ADMIN）。
     *
     * <p>番人テスト {@code AuthzControllerGuardArchTest} は Controller 起点で 2 ホップまでしか
     * 委譲を辿らないため、{@code accessControlService} は本メソッドから<b>直接</b>呼ぶこと。</p>
     */
    private void authorizeSummaryAccess(Long requestUserId, Long subjectUserId) {
        if (requestUserId == null || subjectUserId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        if (requestUserId.equals(subjectUserId)) {
            return;
        }
        if (accessControlService.isSystemAdmin(requestUserId)) {
            return;
        }
        for (ProxyInputConsentEntity consent : consentRepository.findActiveBySubjectUserId(subjectUserId)) {
            if (consent.getOrganizationId() != null
                    && accessControlService.isAdminOrAbove(
                            requestUserId, consent.getOrganizationId(), "ORGANIZATION")) {
                return;
            }
        }
        throw new BusinessException(CommonErrorCode.COMMON_002);
    }
}
