package com.mannschaft.app.property.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.excel.ExcelGeneratorService;
import com.mannschaft.app.common.excel.ExcelGeneratorService.ExcelSheet;
import com.mannschaft.app.common.excel.ExcelResponseHelper;
import com.mannschaft.app.common.pdf.PdfFileNameBuilder;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.pdf.PdfResponseHelper;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.property.PropertyHistoryErrorCode;
import com.mannschaft.app.property.WorkPackageStatus;
import com.mannschaft.app.property.WorkType;
import com.mannschaft.app.property.entity.PropertyWorkDocumentEntity;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.entity.VendorEntity;
import com.mannschaft.app.property.repository.PropertyWorkDocumentRepository;
import com.mannschaft.app.property.repository.PropertyWorkPackageRepository;
import com.mannschaft.app.property.service.PropertyWorkPackageMaskingService.MaskedView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物件履歴台帳 PDF / Excel エクスポートサービス（F09.13 Phase 1-δ）。
 *
 * <p>設計書 {@code docs/features/F09.13_property_history.md} §5.6「エクスポート」に対応。
 * マスキング適用済データから PDF（Thymeleaf テンプレート）または
 * Excel（4 シート構成: サマリ / 履歴一覧 / 業者別 / カテゴリ別）を生成して返す。</p>
 *
 * <p><strong>頻度制限（設計書 §5.6: 1 ユーザー 1 分 10 回）</strong>: 本フェーズでは
 * 未実装。Rate Limiter 基盤導入時に Phase 4 で対応する旨を申し送り事項に記載する。</p>
 *
 * <p><strong>マスキング統合</strong>: パッケージごとに
 * {@link PropertyWorkPackageMaskingService#applyMasking(PropertyWorkPackageEntity, VendorEntity, UserScopeRoleSnapshot)}
 * を呼び、{@link MaskedView#visible()} が false のパッケージはエクスポートから除外する
 * （fail-closed）。金額閲覧不可の場合は金額カラムを {@code null}、業者連絡先カラムを
 * "●●●" として出力する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropertyWorkExportService {

    /** 設計書 §5.6: PDF 同期処理の最大件数。 */
    private static final int PDF_MAX_RECORDS = 5_000;

    /** 設計書 §5.6: Excel SXSSF ストリーミングの最大件数。 */
    private static final int EXCEL_MAX_RECORDS = 20_000;

    /** マスキングされた金額表示用の代替文字列（Excel 用）。 */
    private static final String AMOUNT_MASK_PLACEHOLDER = "●●●";

    private final PropertyWorkPackageRepository packageRepository;
    private final PropertyWorkDocumentRepository documentRepository;
    private final PropertyWorkPackageMaskingService maskingService;
    private final VendorService vendorService;
    private final ExcelGeneratorService excelGenerator;
    private final ExcelResponseHelper excelResponseHelper;
    private final PdfGeneratorService pdfGenerator;
    // PdfResponseHelper は static のため inject 不要

    // =========================================================================
    // 公開メソッド
    // =========================================================================

    /**
     * 1 パッケージ単独の PDF / Excel を出力する。
     *
     * @throws BusinessException PROPERTY_001（不在）/ PROPERTY_002（不可視）/ PROPERTY_004（フォーマット不正）
     */
    public ResponseEntity<byte[]> exportSinglePackage(
            String scopeType, Long scopeId, Long packageId, String format,
            UserScopeRoleSnapshot viewer) {
        PropertyWorkPackageEntity entity = packageRepository.findByIdAndDeletedAtIsNull(packageId)
                .orElseThrow(() -> new BusinessException(PropertyHistoryErrorCode.PROPERTY_001));

        // IDOR 防止: 取得したパッケージが指定 scope と一致しない場合は不在扱い
        if (!scopeType.equals(entity.getScopeType()) || !scopeId.equals(entity.getScopeId())) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_001);
        }

        VendorEntity vendor = entity.getVendorId() != null
                ? safeLoadVendor(entity.getScopeType(), entity.getScopeId(), entity.getVendorId())
                : null;
        MaskedView masked = maskingService.applyMasking(entity, vendor, viewer);
        if (!masked.visible()) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_002);
        }

        return switch (normalizeFormat(format)) {
            case "pdf" -> renderSinglePdf(entity, masked);
            case "xlsx" -> renderSingleExcel(entity, masked);
            default -> throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        };
    }

    /**
     * フィルタ条件で抽出した一覧の PDF / Excel を出力する。
     *
     * <p>不可視パッケージは出力前に除外する（マスキング fail-closed）。
     * 件数上限超過時は PROPERTY_004 を投げる（Phase 2 で非同期ジョブ基盤検討）。</p>
     */
    public ResponseEntity<byte[]> exportList(
            String scopeType, Long scopeId,
            LocalDate from, LocalDate to, WorkType workType, Long vendorId, WorkPackageStatus status,
            String format, UserScopeRoleSnapshot viewer) {
        // 1-δ: Specification を組まず Repository の汎用 list 経由で取得して
        // メモリ上でフィルタする（件数が多くなる組織はガード値で弾く）。
        List<PropertyWorkPackageEntity> all = packageRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                        scopeType, scopeId,
                        org.springframework.data.domain.Pageable.unpaged())
                .getContent();

        List<PropertyWorkPackageEntity> filtered = all.stream()
                .filter(e -> from == null || (e.getActualEndDate() != null
                        && !e.getActualEndDate().isBefore(from)))
                .filter(e -> to == null || (e.getActualEndDate() != null
                        && !e.getActualEndDate().isAfter(to)))
                .filter(e -> workType == null || workType == e.getWorkType())
                .filter(e -> vendorId == null || vendorId.equals(e.getVendorId()))
                .filter(e -> status == null || status == e.getStatus())
                .toList();

        String fmt = normalizeFormat(format);
        int limit = "pdf".equals(fmt) ? PDF_MAX_RECORDS : EXCEL_MAX_RECORDS;
        if (filtered.size() > limit) {
            log.warn("エクスポート件数上限超過: scope={}/{}, format={}, count={}, limit={}",
                    scopeType, scopeId, fmt, filtered.size(), limit);
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }

        // パッケージごとに MaskedView を作って可視のみ残す
        List<MaskedRow> rows = new ArrayList<>(filtered.size());
        for (PropertyWorkPackageEntity e : filtered) {
            VendorEntity vendor = e.getVendorId() != null
                    ? safeLoadVendor(e.getScopeType(), e.getScopeId(), e.getVendorId())
                    : null;
            MaskedView mv = maskingService.applyMasking(e, vendor, viewer);
            if (mv.visible()) {
                rows.add(new MaskedRow(e, vendor, mv));
            }
        }

        return switch (fmt) {
            case "pdf" -> renderListPdf(rows);
            case "xlsx" -> renderListExcel(rows);
            default -> throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        };
    }

    // =========================================================================
    // PDF 生成
    // =========================================================================

    private ResponseEntity<byte[]> renderSinglePdf(PropertyWorkPackageEntity entity, MaskedView masked) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("package", buildPdfPackageMap(entity, masked));
        vars.put("documents", buildPdfDocuments(entity.getId()));
        vars.put("exportedAt", LocalDateTime.now());
        vars.put("maskedFlag", !masked.canViewAmount());
        vars.put("organizationName", null);
        vars.put("exportedByName", null);

        byte[] pdf = pdfGenerator.generateFromTemplate("pdf/property-work-history", vars);
        String fileName = PdfFileNameBuilder.of("物件履歴台帳")
                .date(LocalDate.now())
                .identifier("ID" + entity.getId())
                .build();
        return PdfResponseHelper.toResponse(pdf, fileName);
    }

    private ResponseEntity<byte[]> renderListPdf(List<MaskedRow> rows) {
        // 一覧 PDF はテンプレート未準備のため、Phase 2 で property-work-history-list.html を作成して
        // 切替予定。本フェーズでは既存テンプレートを最大件数 1 で代替するのは無理筋なので、
        // 一覧 PDF が呼ばれた場合は最初の 1 件分のテンプレートを使った詳細 PDF として返す
        // 暫定実装。フロントは「件数が多い時は Excel を使ってください」案内を出す前提とする。
        if (rows.isEmpty()) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }
        log.warn("一覧 PDF は Phase 1-δ では最初の 1 件のみ詳細表示する暫定実装。完全対応は Phase 2 で実装予定。 件数={}",
                rows.size());
        MaskedRow head = rows.get(0);
        return renderSinglePdf(head.entity(), head.masked());
    }

    private Map<String, Object> buildPdfPackageMap(PropertyWorkPackageEntity entity, MaskedView masked) {
        Map<String, Object> map = new HashMap<>();
        map.put("title", entity.getTitle());
        map.put("workType", entity.getWorkType());
        map.put("category", entity.getCategory());
        map.put("status", entity.getStatus());
        map.put("vendorNameSnapshot", entity.getVendorNameSnapshot());
        map.put("incidentDate", entity.getIncidentDate());
        map.put("startedAt", entity.getActualStartDate());
        map.put("completedAt", entity.getActualEndDate());
        map.put("estimatedAmount", masked.estimatedAmount());
        map.put("contractAmount", masked.contractAmount());
        map.put("actualAmount", masked.actualAmount());
        map.put("description", entity.getDescription());
        map.put("incidentNarrative", entity.getIncidentNarrative());
        return map;
    }

    private List<Map<String, Object>> buildPdfDocuments(Long packageId) {
        List<PropertyWorkDocumentEntity> docs =
                documentRepository.findByPackageIdOrderByDisplayOrderAscIdAsc(packageId);
        List<Map<String, Object>> list = new ArrayList<>(docs.size());
        for (PropertyWorkDocumentEntity d : docs) {
            Map<String, Object> m = new HashMap<>();
            m.put("documentKind", d.getDocumentKind());
            m.put("displayOrder", d.getDisplayOrder());
            // SharedFile 名は本フェーズで未取得のため fileId を表示。Phase 2 で SharedFile join 予定。
            m.put("fileName", "file:" + d.getSharedFileId());
            list.add(m);
        }
        return list;
    }

    // =========================================================================
    // Excel 生成（4 シート）
    // =========================================================================

    private ResponseEntity<byte[]> renderSingleExcel(PropertyWorkPackageEntity entity, MaskedView masked) {
        return renderListExcel(List.of(new MaskedRow(entity,
                entity.getVendorId() != null
                        ? safeLoadVendor(entity.getScopeType(), entity.getScopeId(), entity.getVendorId())
                        : null,
                masked)));
    }

    private ResponseEntity<byte[]> renderListExcel(List<MaskedRow> rows) {
        ExcelSheet summary = buildSummarySheet(rows);
        ExcelSheet detail = buildDetailSheet(rows);
        ExcelSheet byVendor = buildVendorAggregateSheet(rows);
        ExcelSheet byCategory = buildCategoryAggregateSheet(rows);

        byte[] excel = excelGenerator.generateMultiSheetExcel(
                List.of(summary, detail, byVendor, byCategory));

        String fileName = LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "_物件履歴台帳.xlsx";
        return excelResponseHelper.toResponse(excel, fileName);
    }

    /** シート 1: サマリ — 総件数・状態別件数・年度別件数（actualEndDate ベース）。 */
    private ExcelSheet buildSummarySheet(List<MaskedRow> rows) {
        List<String> headers = List.of("項目", "値");
        List<List<Object>> body = new ArrayList<>();
        body.add(List.of("総件数", rows.size()));

        // 状態別
        Map<WorkPackageStatus, Long> byStatus = new HashMap<>();
        for (MaskedRow r : rows) {
            byStatus.merge(r.entity().getStatus(), 1L, Long::sum);
        }
        for (Map.Entry<WorkPackageStatus, Long> e : byStatus.entrySet()) {
            body.add(List.of("ステータス: " + e.getKey().name(), e.getValue()));
        }

        // 年度別（actualEndDate の年）
        Map<Integer, Long> byYear = new HashMap<>();
        for (MaskedRow r : rows) {
            LocalDate end = r.entity().getActualEndDate();
            if (end != null) {
                byYear.merge(end.getYear(), 1L, Long::sum);
            }
        }
        byYear.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> body.add(List.of("年度 " + e.getKey() + " 完了件数", e.getValue())));

        return new ExcelSheet("サマリ", headers, body);
    }

    /** シート 2: 履歴一覧。マスキング適用済の金額を含む。 */
    private ExcelSheet buildDetailSheet(List<MaskedRow> rows) {
        List<String> headers = List.of(
                "ID", "工事種別", "カテゴリ", "タイトル", "ステータス",
                "業者名", "業者電話", "業者メール",
                "計画開始日", "計画終了日", "実施開始日", "実施終了日",
                "見積金額", "契約金額", "実施金額", "通貨", "保証期限");
        List<List<Object>> body = new ArrayList<>(rows.size());
        for (MaskedRow r : rows) {
            PropertyWorkPackageEntity e = r.entity();
            VendorEntity v = r.vendor();
            MaskedView m = r.masked();
            body.add(java.util.Arrays.asList(
                    e.getId(),
                    e.getWorkType() != null ? e.getWorkType().name() : null,
                    e.getCategory(),
                    e.getTitle(),
                    e.getStatus() != null ? e.getStatus().name() : null,
                    e.getVendorNameSnapshot(),
                    v != null ? maskedVendorPhone(v, m) : null,
                    v != null ? maskedVendorEmail(v, m) : null,
                    e.getPlannedStartDate(),
                    e.getPlannedEndDate(),
                    e.getActualStartDate(),
                    e.getActualEndDate(),
                    amountCell(m.estimatedAmount(), m.canViewAmount()),
                    amountCell(m.contractAmount(), m.canViewAmount()),
                    amountCell(m.actualAmount(), m.canViewAmount()),
                    e.getCurrency(),
                    e.getWarrantyUntil()));
        }
        return new ExcelSheet("履歴一覧", headers, body);
    }

    /** シート 3: 業者別集計（vendor_name_snapshot ベース、件数 + 実施金額合計）。 */
    private ExcelSheet buildVendorAggregateSheet(List<MaskedRow> rows) {
        List<String> headers = List.of("業者名", "件数", "実施金額合計");
        Map<String, long[]> agg = new HashMap<>(); // [count, sumAmount]
        boolean anyMasked = false;
        for (MaskedRow r : rows) {
            String key = r.entity().getVendorNameSnapshot() != null
                    ? r.entity().getVendorNameSnapshot()
                    : "(業者未割当)";
            long[] cur = agg.computeIfAbsent(key, k -> new long[]{0L, 0L});
            cur[0]++;
            if (r.masked().canViewAmount() && r.masked().actualAmount() != null) {
                cur[1] += r.masked().actualAmount();
            }
            if (!r.masked().canViewAmount()) {
                anyMasked = true;
            }
        }
        List<List<Object>> body = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            // 全行マスクされている場合は金額合計も意味が無いため null
            Object sum = anyMasked && e.getValue()[1] == 0 ? null : e.getValue()[1];
            body.add(java.util.Arrays.asList(e.getKey(), e.getValue()[0], sum));
        }
        return new ExcelSheet("業者別集計", headers, body);
    }

    /** シート 4: カテゴリ別集計。 */
    private ExcelSheet buildCategoryAggregateSheet(List<MaskedRow> rows) {
        List<String> headers = List.of("カテゴリ", "件数", "実施金額合計");
        Map<String, long[]> agg = new HashMap<>();
        for (MaskedRow r : rows) {
            String key = r.entity().getCategory() != null ? r.entity().getCategory() : "(未分類)";
            long[] cur = agg.computeIfAbsent(key, k -> new long[]{0L, 0L});
            cur[0]++;
            if (r.masked().canViewAmount() && r.masked().actualAmount() != null) {
                cur[1] += r.masked().actualAmount();
            }
        }
        List<List<Object>> body = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            body.add(java.util.Arrays.asList(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        return new ExcelSheet("カテゴリ別集計", headers, body);
    }

    // =========================================================================
    // 内部ヘルパー
    // =========================================================================

    /** IDOR 防止のため、scope を渡して vendor が同一スコープか検証する。 */
    private VendorEntity safeLoadVendor(String scopeType, Long scopeId, Long vendorId) {
        try {
            return vendorService.getVendor(scopeType, scopeId, vendorId);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeFormat(String format) {
        if (format == null) {
            return "pdf";
        }
        String f = format.toLowerCase();
        return switch (f) {
            case "pdf", "xlsx" -> f;
            case "excel", "xls" -> "xlsx";
            default -> f;
        };
    }

    /** 金額セルの値を返す。マスク時は AMOUNT_MASK_PLACEHOLDER 文字列、それ以外は数値そのまま。 */
    private Object amountCell(Long amount, boolean canViewAmount) {
        if (!canViewAmount) {
            return AMOUNT_MASK_PLACEHOLDER;
        }
        return amount;
    }

    /** 業者連絡先のマスク表示（金額閲覧不可時は ●●● を返却、null は null のまま）。 */
    private String maskedVendorPhone(VendorEntity v, MaskedView m) {
        if (v.getPhone() == null) {
            return null;
        }
        return m.canViewAmount() ? v.getPhone() : AMOUNT_MASK_PLACEHOLDER;
    }

    private String maskedVendorEmail(VendorEntity v, MaskedView m) {
        if (v.getEmail() == null) {
            return null;
        }
        return m.canViewAmount() ? v.getEmail() : AMOUNT_MASK_PLACEHOLDER;
    }

    /** マスキング適用後のエクスポート行データ。 */
    private record MaskedRow(
            PropertyWorkPackageEntity entity,
            VendorEntity vendor,
            MaskedView masked) {
    }
}
