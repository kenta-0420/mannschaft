package com.mannschaft.app.common.excel;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            Date date = Date.from(ld.atStartOfDay(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant());
            cell.setCellValue(date);
            cell.setCellStyle(styles.date());
            return;
        }
        if (value instanceof LocalDateTime ldt) {
            Date date = Date.from(ldt.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant());
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
        // POI の日付書式: 月は MM（大文字）、分は mm（小文字）。
        // 旧コードの "yyyy/mm/dd" は月の代わりに分を埋め込むバグになるため修正。
        dateStyle.setDataFormat(dataFormat.getFormat("yyyy/MM/dd"));
        applyThinBorder(dateStyle);

        CellStyle dateTimeStyle = workbook.createCellStyle();
        dateTimeStyle.setFont(baseFont);
        // 時は HH（24時間制大文字）、分は mm（小文字）。
        dateTimeStyle.setDataFormat(dataFormat.getFormat("yyyy/MM/dd HH:mm"));
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

    // ========================================================================
    // テンプレート差し込み（F09.14 Phase 2-β-3）
    // ========================================================================

    /** プレースホルダ {@code ${key}} を抽出する正規表現。 */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    /**
     * 既存 Excel テンプレートのセルに含まれる {@code ${key}} プレースホルダを
     * 渡された {@code data} の値で置換し、新しい byte[] として返す。
     *
     * <p>SXSSF ではなく {@link XSSFWorkbook} を用いる（テンプレ読み込み + ランダムアクセスのため）。
     * 大量データ向けの一覧出力には {@link #generateMultiSheetExcel(List)} を使うこと。
     *
     * <p>サポートする data 値の型と書式:
     * <ul>
     *   <li>{@code Number}（Long/Integer/BigDecimal 等）→ 数値書式 {@code #,##0}</li>
     *   <li>{@link LocalDate} → 日付書式 {@code yyyy/MM/dd}</li>
     *   <li>{@link LocalDateTime} → 日時書式 {@code yyyy/MM/dd HH:mm}</li>
     *   <li>その他 → そのまま {@code toString()} で文字列化</li>
     * </ul>
     *
     * <p>セル文字列内に複数のプレースホルダがある場合（例: {@code "氏名: ${name}"}）も置換する。
     * data に存在しないキーは元の {@code ${key}} 表記のまま残す（誤データ消失を防ぐため）。
     *
     * @param templateStream xlsx テンプレートの InputStream（呼び出し側で close 不要）
     * @param data プレースホルダ名 → 値の Map
     * @return 置換後の xlsx byte 配列
     */
    public byte[] fillTemplate(InputStream templateStream, Map<String, Object> data) {
        if (templateStream == null) {
            throw new ExcelGenerationException("templateStream が null", null);
        }
        if (data == null) {
            throw new ExcelGenerationException("data が null", null);
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook(templateStream);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle numberStyle = createNumberStyle(workbook);
            CellStyle dateStyle = createDateStyleForTemplate(workbook);
            CellStyle dateTimeStyle = createDateTimeStyleForTemplate(workbook);

            for (int sheetIdx = 0; sheetIdx < workbook.getNumberOfSheets(); sheetIdx++) {
                Sheet sheet = workbook.getSheetAt(sheetIdx);
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                            String original = cell.getStringCellValue();
                            if (original != null && original.contains("${")) {
                                replacePlaceholders(cell, original, data,
                                        numberStyle, dateStyle, dateTimeStyle);
                            }
                        }
                    }
                }
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ExcelGenerationException("Excel テンプレート差し込み失敗", e);
        }
    }

    /**
     * 1セル内のプレースホルダを置換する。
     * 単一プレースホルダで型情報を保つ場合は数値/日付として書式付きセル化する。
     * 複数プレースホルダ混在の場合は文字列として連結する。
     */
    private void replacePlaceholders(Cell cell, String original, Map<String, Object> data,
            CellStyle numberStyle, CellStyle dateStyle, CellStyle dateTimeStyle) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(original);
        // 単一プレースホルダかつ前後に他文字なし → 型を保ったままセル化
        if (matcher.matches()) {
            String key = matcher.group(1);
            Object value = data.get(key);
            if (value == null) {
                // データ未提供は元のプレースホルダを残す
                return;
            }
            if (value instanceof Number num) {
                cell.setCellValue(num.doubleValue());
                cell.setCellStyle(numberStyle);
            } else if (value instanceof LocalDate ld) {
                cell.setCellValue(Date.from(ld.atStartOfDay(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant()));
                cell.setCellStyle(dateStyle);
            } else if (value instanceof LocalDateTime ldt) {
                cell.setCellValue(Date.from(ldt.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant()));
                cell.setCellStyle(dateTimeStyle);
            } else {
                cell.setCellValue(value.toString());
            }
            return;
        }
        // 複数プレースホルダ混在 → 文字列連結
        matcher.reset();
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = data.get(key);
            String replacement = (value == null) ? matcher.group(0) : value.toString();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        cell.setCellValue(sb.toString());
    }

    private CellStyle createNumberStyle(XSSFWorkbook workbook) {
        CreationHelper helper = workbook.getCreationHelper();
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(helper.createDataFormat().getFormat("#,##0"));
        return style;
    }

    private CellStyle createDateStyleForTemplate(XSSFWorkbook workbook) {
        CreationHelper helper = workbook.getCreationHelper();
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(helper.createDataFormat().getFormat("yyyy/MM/dd"));
        return style;
    }

    private CellStyle createDateTimeStyleForTemplate(XSSFWorkbook workbook) {
        CreationHelper helper = workbook.getCreationHelper();
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(helper.createDataFormat().getFormat("yyyy/MM/dd HH:mm"));
        return style;
    }

    // ========================================================================
    // テンプレート差込 + 繰り返し行ブロック（F01.10 Phase 3）
    // ========================================================================

    /**
     * テンプレート XLSX へのヘッダ値差込 + 繰り返し行ブロック差込。
     *
     * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §7.4（案 A 確定）
     *
     * <h3>動作仕様</h3>
     * <ol>
     *   <li>テンプレート内の {@code ${key}} プレースホルダーを {@code headerData} で置換する
     *       （{@link #fillTemplate(InputStream, Map)} と同じロジック）。</li>
     *   <li>行内のセルに {@code ${rowMarkerPrefix.key}} 形式（例: {@code ${rows[].yearMonth}}）を
     *       持つ「マーカー行」を検出する。マーカー行が見つかったら以下を行う:
     *       <ul>
     *         <li>マーカー行を {@code rows} の件数分だけ複製（行をコピーして下に挿入）</li>
     *         <li>各複製行の {@code ${rowMarkerPrefix.key}} を {@code rows.get(i).get("key")} で置換</li>
     *         <li>元のマーカー行は削除（先頭 1 行分が定義行のため）</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p><b>制限事項</b>: マーカー行は各シートにつき 1 ブロックのみ対応。
     * 複数ブロックが必要な場合は本メソッドを複数回呼ぶか、
     * {@link #generateMultiSheetExcel(List)} を使用すること。
     *
     * <p>テンプレートに使用するクラスは {@link XSSFWorkbook}（ランダムアクセス必要）。
     * 大量データ向けには {@link #generateMultiSheetExcel(List)} を使うこと。
     *
     * @param templateStream  テンプレート XLSX の InputStream
     * @param headerData      単発値の Map（{@code ${key}} を置換）
     * @param rows            繰り返し行のデータリスト（各行は {@code Map<String, Object>}）
     * @param rowMarkerPrefix 行マーカーの識別プレフィックス（例: {@code "rows[]"}）
     * @return 生成された XLSX の byte 配列
     */
    public byte[] fillTemplateWithRows(
            InputStream templateStream,
            Map<String, Object> headerData,
            List<Map<String, Object>> rows,
            String rowMarkerPrefix) {

        if (templateStream == null) {
            throw new ExcelGenerationException("templateStream が null", null);
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(templateStream);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle numberStyle = createNumberStyle(workbook);
            CellStyle dateStyle = createDateStyleForTemplate(workbook);
            CellStyle dateTimeStyle = createDateTimeStyleForTemplate(workbook);

            for (int sheetIdx = 0; sheetIdx < workbook.getNumberOfSheets(); sheetIdx++) {
                Sheet sheet = workbook.getSheetAt(sheetIdx);

                // ── ステップ 1: マーカー行を検出 ──
                int markerRowIndex = findMarkerRowIndex(sheet, rowMarkerPrefix);

                if (markerRowIndex >= 0 && rows != null && !rows.isEmpty()) {
                    // ── ステップ 2: マーカー行テンプレートを rows.size() 行に展開 ──
                    expandMarkerRows(workbook, sheet, markerRowIndex, rows, rowMarkerPrefix,
                            numberStyle, dateStyle, dateTimeStyle);
                }

                // ── ステップ 3: ヘッダ値の ${key} を置換 ──
                if (headerData != null) {
                    for (Row row : sheet) {
                        for (Cell cell : row) {
                            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                                String original = cell.getStringCellValue();
                                if (original != null && original.contains("${")) {
                                    replacePlaceholders(cell, original, headerData,
                                            numberStyle, dateStyle, dateTimeStyle);
                                }
                            }
                        }
                    }
                }
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ExcelGenerationException("Excel テンプレート差込（繰り返し行）失敗", e);
        }
    }

    /**
     * シート内でマーカー行（{@code ${rowMarkerPrefix.xxx}} を持つセルがある行）のインデックスを返す。
     * 見つからない場合は -1 を返す。
     */
    private int findMarkerRowIndex(Sheet sheet, String rowMarkerPrefix) {
        String markerStart = "${" + rowMarkerPrefix + ".";
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                    String val = cell.getStringCellValue();
                    if (val != null && val.contains(markerStart)) {
                        return row.getRowNum();
                    }
                }
            }
        }
        return -1;
    }

    /**
     * マーカー行を {@code rows} の件数分だけ展開・置換し、元のマーカー行を削除する。
     *
     * <p>展開方式:
     * <ol>
     *   <li>マーカー行より下の既存行を {@code rows.size() - 1} 行分だけ下にシフトして空白行を確保する。</li>
     *   <li>各データ行（{@code rows.get(i)}）の内容を挿入する。</li>
     *   <li>元のマーカー行は最初のデータ行で上書きする（行シフト前に複製して対処）。</li>
     * </ol>
     */
    private void expandMarkerRows(XSSFWorkbook workbook, Sheet sheet, int markerRowIndex,
                                   List<Map<String, Object>> rows,
                                   String rowMarkerPrefix,
                                   CellStyle numberStyle, CellStyle dateStyle, CellStyle dateTimeStyle) {
        String markerPrefix = "${" + rowMarkerPrefix + ".";
        int rowCount = rows.size();
        int lastRowNum = sheet.getLastRowNum();

        // rows が 1 件より多い場合は下の行を rows.size()-1 行分シフトして空白を確保する
        if (rowCount > 1) {
            sheet.shiftRows(markerRowIndex + 1, lastRowNum, rowCount - 1);
        }

        // マーカー行のセル定義を取得（スタイル・列数を保持するため）
        Row templateRow = sheet.getRow(markerRowIndex);
        if (templateRow == null) {
            return;
        }

        // 各データ行を書き込む
        for (int i = 0; i < rowCount; i++) {
            Row targetRow;
            if (i == 0) {
                // 最初のデータはマーカー行（テンプレート行）をそのまま上書き
                targetRow = templateRow;
            } else {
                // 2 件目以降はシフトで確保した空白行に書き込む
                targetRow = sheet.getRow(markerRowIndex + i);
                if (targetRow == null) {
                    targetRow = sheet.createRow(markerRowIndex + i);
                }
                // テンプレート行のスタイル・列数を複製
                copyRowStyle(templateRow, targetRow);
            }

            Map<String, Object> rowData = rows.get(i);
            // 各セルの ${rowMarkerPrefix.key} プレースホルダーを rowData の値で置換
            for (Cell cell : targetRow) {
                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                    String original = cell.getStringCellValue();
                    if (original != null && original.contains(markerPrefix)) {
                        String replaced = replaceRowMarkers(original, rowData, markerPrefix);
                        cell.setCellValue(replaced);
                    }
                }
            }
        }
    }

    /**
     * セル内の {@code ${rowMarkerPrefix.key}} を {@code rowData} の値で置換した文字列を返す。
     */
    private String replaceRowMarkers(String original, Map<String, Object> rowData,
                                     String markerPrefix) {
        String result = original;
        for (Map.Entry<String, Object> entry : rowData.entrySet()) {
            String placeholder = markerPrefix + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }

    /**
     * テンプレート行のセルスタイルを対象行にコピーする。
     */
    private void copyRowStyle(Row source, Row target) {
        target.setHeight(source.getHeight());
        for (Cell sourceCell : source) {
            Cell targetCell = target.getCell(sourceCell.getColumnIndex());
            if (targetCell == null) {
                targetCell = target.createCell(sourceCell.getColumnIndex());
            }
            targetCell.setCellStyle(sourceCell.getCellStyle());
            // 値はプレースホルダーのまま複製（後で replaceRowMarkers で置換される）
            if (sourceCell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                targetCell.setCellValue(sourceCell.getStringCellValue());
            }
        }
    }
}
