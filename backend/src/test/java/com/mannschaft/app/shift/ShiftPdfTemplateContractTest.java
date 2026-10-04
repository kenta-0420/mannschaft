package com.mannschaft.app.shift;

import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.config.PdfFontConfig;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.ShiftSlotResponse;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260903-0656 のシフト PDF 描画契約。
 * 実 DTO・SpringTemplateEngine・日本語フォント・PDF 変換を通し、旧 flat 参照の再発を防ぐ。
 * HTTP 認可は ShiftUnpublishedScheduleVisibilityContractIT が別途固定する。
 */
@DisplayName("CMP-260903-0656 シフト PDF テンプレート契約")
class ShiftPdfTemplateContractTest {

    private PdfGeneratorService generator;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        PdfFontConfig fonts = new PdfFontConfig();
        fonts.init();
        generator = new PdfGeneratorService(engine, fonts);
    }

    @ParameterizedTest(name = "{0}: 割当済み枠を描画できる")
    @ValueSource(strings = {"team", "personal"})
    @DisplayName("両 layout が現行 nested DTO の日本語・期間・枠を有効 PDF に描画する")
    void 割当済み枠の日本語と日付時刻を描画する(String layout) throws Exception {
        ShiftSlotResponse slot = ShiftSlotResponse.builder()
                .id(29L).scheduleId(19L)
                .time(new ShiftSlotResponse.ShiftSlotTimeDto(
                        LocalDate.of(2026, 10, 6), LocalTime.of(8, 30), LocalTime.of(10, 0), false))
                .position(new ShiftSlotResponse.ShiftSlotPositionDto(37L, "朝の受付", 2))
                .assignedUserIds(List.of(31L, 41L))
                .build();

        String text = renderPdfText(layout, List.of(slot));

        assertThat(text).contains("受付当番・秋", "2026-10-05", "2026-10-11",
                "2026-10-06", "08:30", "10:00", "朝の受付");
        if ("team".equals(layout)) {
            assertThat(text).contains("チーム全体シフト表", "必要人数", "割当メンバー")
                    .containsPattern("朝の受付\\s*2\\s*31,\\s*41");
        } else {
            assertThat(text).contains("個人タイムライン", "合計シフト数: 1 件")
                    .doesNotContain("割り当てられたシフトはありません", "31, 41");
        }
    }

    @ParameterizedTest(name = "{0}: 枠なしでも描画できる")
    @ValueSource(strings = {"team", "personal"})
    @DisplayName("両 layout が空の枠でも有効 PDF を生成し、期間と見出しを描画する")
    void 空の枠でも日本語見出しと期間を描画する(String layout) throws Exception {
        String text = renderPdfText(layout, List.of());

        assertThat(text).contains("受付当番・秋", "2026-10-05", "2026-10-11")
                .doesNotContain("2026-10-06", "朝の受付");
        if ("personal".equals(layout)) {
            assertThat(text).contains("個人タイムライン", "割り当てられたシフトはありません", "合計シフト数: 0 件");
        } else {
            assertThat(text).contains("チーム全体シフト表", "割当メンバー");
        }
    }

    private String renderPdfText(String layout, List<ShiftSlotResponse> slots) throws Exception {
        ShiftScheduleResponse schedule = ShiftScheduleResponse.builder()
                .id(19L).teamId(23L)
                .content(new ShiftScheduleResponse.ShiftContentDto("受付当番・秋", "WEEKLY", "検証用"))
                .period(new ShiftScheduleResponse.ShiftPeriodDto(
                        LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11), null))
                .build();
        byte[] pdf = generator.generateFromTemplate("pdf/shift-" + layout,
                Map.of("schedule", schedule, "slots", slots, "layout", layout, "userId", 31L));

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isPositive();
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        }
    }
}
