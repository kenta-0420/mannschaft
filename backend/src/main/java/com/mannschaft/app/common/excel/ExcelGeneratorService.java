package com.mannschaft.app.common.excel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * 汎用 Excel 生成共通基盤。
 *
 * <p>{@link com.mannschaft.app.common.pdf.PdfGeneratorService} の Excel 版。
 * Apache POI の {@link SXSSFWorkbook} によるストリーミング実装で、
 * 大量レコード（〜20,000件目安）を低メモリで書き出せる。
 *
 * <p>主な特徴:
 * <ul>
 *   <li>SXSSF ウィンドウサイズ 100 行（古い行はディスクに退避）</li>
 *   <li>ヘッダー行は太字 + 背景グレー、{@code freezePane} で固定</li>
 *   <li>セル値の型に応じて書式を自動付与（数値: {@code #,##0} / 日付: {@code yyyy/MM/dd}）</li>
 *   <li>try-with-resources + {@link SXSSFWorkbook#dispose()} で一時ファイルを確実に削除</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelGeneratorService {

    /** SXSSFWorkbook のメモリウィンドウサイズ。これを超えた行はディスクに退避する。 */
    private static final int DEFAULT_WINDOW_SIZE = 100;

    /** 列幅のデフォルト値（POI 単位 = 1/256 文字幅。約 18 文字相当）。 */
    private static final int DEFAULT_COLUMN_WIDTH = 18 * 256;

    private final ExcelFontConfig fontConfig;

    /**
     * 単一シートの一覧データを Excel として生成する。
     *
     * @param headers   ヘッダー行のラベル配列
     * @param rows      データ行（各行は headers と同じ長さの Object 配列）
     * @param sheetName シート名（Excel の制約に従いサニタイズされる）
     * @return XLSX ファイルの byte 配列
     */
    public byte[] generateListExcel(List<String> headers, List<List<Object>> rows, String sheetName) {
        return generateMultiSheetExcel(List.of(new ExcelSheet(sheetName, headers, rows)));
    }

    /**
     * 複数シートを持つ Excel を生成する（サマリ + 一覧 + 集計など）。
     *
     * @param sheets シート定義のリスト
     * @return XLSX ファイルの byte 配列
     */
    public byte[] generateMultiSheetExcel(List<ExcelSheet> sheets) {
        SXSSFWorkbook workbook = new SXSSFWorkbook(DEFAULT_WINDOW_SIZE);
        // 一時ファイル圧縮で I/O 負荷を抑える
        workbook.setCompressTempFiles(true);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyles styles = buildStyles(workbook);

            for (ExcelSheet sheetDef : sheets) {
                writeSheet(workbook, sheetDef, styles);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("Excel 生成失敗", e);
            throw new ExcelGenerationException("Excel 生成に失敗しました", e);
        } finally {
            // 一時ファイルを確実に削除する
            workbook.dispose();
            try {
                workbook.close();
            } catch (IOException e) {
                log.warn("SXSSFWorkbook クローズ失敗", e);
            }
        }
    }

    private void writeSheet(SXSSFWorkbook workbook, ExcelSheet sheetDef, CellStyles styles) {
        String safeName = WorkbookUtil.createSafeSheetName(sheetDef.name());
        SXSSFSheet sheet = workbook.createSheet(safeName);

        // ヘッダー行
        Row headerRow = sheet.createRow(0);
        List<String> headers = sheetDef.headers();
        for (int c = 0; c < headers.size(); c++) {
            Cell cell = headerRow.createCell(c);
            cell.setCellValue(headers.get(c));
            cell.setCellStyle(styles.header());
            sheet.setColumnWidth(c, DEFAULT_COLUMN_WIDTH);
        }

        // データ行
        List<List<Object>> rows = sheetDef.rows();
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r + 1);
            List<Object> rowData = rows.get(r);
            for (int c = 0; c < rowData.size(); c++) {
                Cell cell = row.createCell(c);
                applyValue(cell, rowData.get(c), styles);
            }
        }

        // ヘッダー固定（freeze pane: 上 1 行）
        sheet.createFreezePane(0, 1);
    }

    private void applyValue(Cell cell, Object value, CellStyles styles) {
        if (value == null) {
            cell.setBlank();
            return;
        }
        if (value instanceof Number n) {
            // BigDecimal は doubleValue() でセット、書式で千区切り表示
            if (value instanceof BigDecimal bd) {
                cell.setCellValue(bd.doubleValue());
            } else if (value instanceof Long || value instanceof Integer
                    || value instanceof Short || value instanceof Byte) {
                cell.setCellValue(n.longValue());
            } else {
                cell.setCellValue(n.doubleValue());
            }
            cell.setCellStyle(styles.number());
            return;
        }
        if (value instanceof LocalDate ld) {
            Date date = Date.from(ld.atStartOfDay(ZoneId.systemDefault()).toInstant());
            cell.setCellValue(date);
            cell.setCellStyle(styles.date());
            return;
        }
        if (value instanceof LocalDateTime ldt) {
            Date date = Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
            cell.setCellValue(date);
            cell.setCellStyle(styles.dateTime());
            return;
        }
        if (value instanceof Boolean b) {
            cell.setCellValue(b);
            cell.setCellStyle(styles.body());
            return;
        }
        // フォールバック: 文字列
        cell.setCellValue(value.toString());
        cell.setCellStyle(styles.body());
    }

    private CellStyles buildStyles(SXSSFWorkbook workbook) {
        Font baseFont = workbook.createFont();
        baseFont.setFontName(fontConfig.getDefaultFontName());
        baseFont.setFontHeightInPoints(fontConfig.getDefaultFontSize());

        Font headerFont = workbook.createFont();
        headerFont.setFontName(fontConfig.getDefaultFontName());
        headerFont.setFontHeightInPoints(fontConfig.getHeaderFontSize());
        headerFont.setBold(true);

        CreationHelper creationHelper = workbook.getCreationHelper();
        DataFormat dataFormat = creationHelper.createDataFormat();

        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        applyThinBorder(headerStyle);

        CellStyle bodyStyle = workbook.createCellStyle();
        bodyStyle.setFont(baseFont);
        applyThinBorder(bodyStyle);

        CellStyle numberStyle = workbook.createCellStyle();
        numberStyle.setFont(baseFont);
        numberStyle.setDataFormat(dataFormat.getFormat("#,##0"));
        numberStyle.setAlignment(HorizontalAlignment.RIGHT);
        applyThinBorder(numberStyle);

        CellStyle dateStyle = workbook.createCellStyle();
        dateStyle.setFont(baseFont);
        dateStyle.setDataFormat(dataFormat.getFormat("yyyy/mm/dd"));
        applyThinBorder(dateStyle);

        CellStyle dateTimeStyle = workbook.createCellStyle();
        dateTimeStyle.setFont(baseFont);
        dateTimeStyle.setDataFormat(dataFormat.getFormat("yyyy/mm/dd hh:mm"));
        applyThinBorder(dateTimeStyle);

        return new CellStyles(headerStyle, bodyStyle, numberStyle, dateStyle, dateTimeStyle);
    }

    private void applyThinBorder(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    /**
     * シート単位の入力データ。
     *
     * @param name    シート名
     * @param headers ヘッダー行ラベル
     * @param rows    データ行（各行は {@code headers.size()} 件の Object）
     */
    public record ExcelSheet(String name, List<String> headers, List<List<Object>> rows) {
    }

    /** 内部用: 共有セルスタイル群。 */
    private record CellStyles(
            CellStyle header,
            CellStyle body,
            CellStyle number,
            CellStyle date,
            CellStyle dateTime) {
    }

    /** Excel 生成失敗時の例外。{@link com.mannschaft.app.common.BusinessException} 化は呼び出し側に委ねる。 */
    public static class ExcelGenerationException extends RuntimeException {
        public ExcelGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
