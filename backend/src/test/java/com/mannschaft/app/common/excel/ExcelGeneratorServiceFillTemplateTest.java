package com.mannschaft.app.common.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ExcelGeneratorService#fillTemplate} の単体テスト（F09.14 Phase 2-β-3）。
 */
@DisplayName("ExcelGeneratorService.fillTemplate")
class ExcelGeneratorServiceFillTemplateTest {

    private final ExcelGeneratorService service = new ExcelGeneratorService(new ExcelFontConfig());

    /** プレースホルダ入りの簡易テンプレを byte[] で生成する。 */
    private byte[] buildTemplate(String... placeholders) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            for (int i = 0; i < placeholders.length; i++) {
                Row row = sheet.createRow(i);
                Cell cell = row.createCell(0);
                cell.setCellValue(placeholders[i]);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("単一プレースホルダの文字列置換が動く")
    void replaceStringPlaceholder() throws IOException {
        byte[] template = buildTemplate("${name}");
        Map<String, Object> data = new HashMap<>();
        data.put("name", "山田太郎");

        byte[] result = service.fillTemplate(new ByteArrayInputStream(template), data);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Cell c = wb.getSheetAt(0).getRow(0).getCell(0);
            assertThat(c.getStringCellValue()).isEqualTo("山田太郎");
        }
    }

    @Test
    @DisplayName("数値プレースホルダは数値書式付きセルに変換される")
    void replaceNumberPlaceholder() throws IOException {
        byte[] template = buildTemplate("${amount}");
        Map<String, Object> data = new HashMap<>();
        data.put("amount", 1234567L);

        byte[] result = service.fillTemplate(new ByteArrayInputStream(template), data);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Cell c = wb.getSheetAt(0).getRow(0).getCell(0);
            assertThat(c.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(c.getNumericCellValue()).isEqualTo(1234567.0);
            assertThat(c.getCellStyle().getDataFormatString()).isEqualTo("#,##0");
        }
    }

    @Test
    @DisplayName("LocalDate プレースホルダは日付セル + yyyy/MM/dd 書式に変換される")
    void replaceLocalDatePlaceholder() throws IOException {
        byte[] template = buildTemplate("${reportDate}");
        Map<String, Object> data = new HashMap<>();
        data.put("reportDate", LocalDate.of(2026, 5, 7));

        byte[] result = service.fillTemplate(new ByteArrayInputStream(template), data);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Cell c = wb.getSheetAt(0).getRow(0).getCell(0);
            assertThat(c.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(c.getCellStyle().getDataFormatString()).isEqualTo("yyyy/MM/dd");
        }
    }

    @Test
    @DisplayName("LocalDateTime プレースホルダは日時セル + yyyy/MM/dd HH:mm 書式に変換される")
    void replaceLocalDateTimePlaceholder() throws IOException {
        byte[] template = buildTemplate("${reportAt}");
        Map<String, Object> data = new HashMap<>();
        data.put("reportAt", LocalDateTime.of(2026, 5, 7, 14, 30));

        byte[] result = service.fillTemplate(new ByteArrayInputStream(template), data);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Cell c = wb.getSheetAt(0).getRow(0).getCell(0);
            assertThat(c.getCellStyle().getDataFormatString()).isEqualTo("yyyy/MM/dd HH:mm");
        }
    }

    @Test
    @DisplayName("複数プレースホルダ混在は文字列連結で置換される")
    void replaceMultiplePlaceholdersInOneCell() throws IOException {
        byte[] template = buildTemplate("名前: ${name} / 年齢: ${age}");
        Map<String, Object> data = new HashMap<>();
        data.put("name", "山田");
        data.put("age", 30);

        byte[] result = service.fillTemplate(new ByteArrayInputStream(template), data);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Cell c = wb.getSheetAt(0).getRow(0).getCell(0);
            assertThat(c.getStringCellValue()).isEqualTo("名前: 山田 / 年齢: 30");
        }
    }

    @Test
    @DisplayName("data に存在しないキーはプレースホルダのまま残る（誤データ防止）")
    void missingKeyKeepsPlaceholder() throws IOException {
        byte[] template = buildTemplate("${absentKey}");
        Map<String, Object> data = new HashMap<>();

        byte[] result = service.fillTemplate(new ByteArrayInputStream(template), data);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Cell c = wb.getSheetAt(0).getRow(0).getCell(0);
            assertThat(c.getStringCellValue()).isEqualTo("${absentKey}");
        }
    }

    @Test
    @DisplayName("プレースホルダ以外のセルは変更されない")
    void nonPlaceholderCellsUntouched() throws IOException {
        byte[] template = buildTemplate("通常の文字列セル", "${name}");
        Map<String, Object> data = new HashMap<>();
        data.put("name", "山田");

        byte[] result = service.fillTemplate(new ByteArrayInputStream(template), data);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("通常の文字列セル");
            assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("山田");
        }
    }

    @Test
    @DisplayName("templateStream が null なら ExcelGenerationException")
    void nullTemplateStream() {
        assertThatThrownBy(() -> service.fillTemplate(null, new HashMap<>()))
                .isInstanceOf(ExcelGeneratorService.ExcelGenerationException.class);
    }

    @Test
    @DisplayName("data が null なら ExcelGenerationException")
    void nullData() throws IOException {
        byte[] template = buildTemplate("${name}");
        assertThatThrownBy(() ->
                service.fillTemplate(new ByteArrayInputStream(template), null))
                .isInstanceOf(ExcelGeneratorService.ExcelGenerationException.class);
    }
}
