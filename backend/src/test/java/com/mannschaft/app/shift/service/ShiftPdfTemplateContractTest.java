package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.config.PdfFontConfig;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.ShiftSlotResponse;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * シフト PDF テンプレート契約テスト。
 *
 * <p>PdfGeneratorService をモックせず、実在のテンプレート（pdf/shift-team / pdf/shift-personal）を
 * 実際にレンダリングして PDF を生成し、テキスト抽出で内容を検証する。
 * ShiftScheduleResponse / ShiftSlotResponse がネスト DTO 化された後にテンプレートが旧フラット名のまま
 * 取り残され、本番で PDF_001（SpEL 評価例外）になっていた欠陥の再発防止。</p>
 */
@DisplayName("シフト PDF テンプレート契約テスト")
class ShiftPdfTemplateContractTest {

    private static final Long SCHEDULE_ID = 10L;
    private static final Long USER_ID = 7L;

    private ShiftPdfService service;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        PdfFontConfig fontConfig = new PdfFontConfig();
        fontConfig.init();

        ShiftScheduleService scheduleService = mock(ShiftScheduleService.class);
        ShiftSlotService slotService = mock(ShiftSlotService.class);

        ShiftScheduleResponse schedule = ShiftScheduleResponse.builder()
                .id(SCHEDULE_ID)
                .teamId(1L)
                .content(new ShiftScheduleResponse.ShiftContentDto("十月前半シフト", "WEEKLY", null))
                .period(new ShiftScheduleResponse.ShiftPeriodDto(
                        LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), null))
                .status(new ShiftScheduleResponse.ShiftStatusDto(
                        "PUBLISHED", LocalDateTime.of(2026, 9, 25, 9, 0), 1L))
                .build();
        ShiftSlotResponse slot = ShiftSlotResponse.builder()
                .id(100L)
                .scheduleId(SCHEDULE_ID)
                .time(new ShiftSlotResponse.ShiftSlotTimeDto(
                        LocalDate.of(2026, 10, 3), LocalTime.of(9, 30), LocalTime.of(17, 45), false))
                .position(new ShiftSlotResponse.ShiftSlotPositionDto(5L, "レジ担当", 6))
                .assignedUserIds(List.of(USER_ID, 8L))
                .build();
        when(scheduleService.getSchedule(SCHEDULE_ID, true)).thenReturn(schedule);
        when(slotService.listSlots(SCHEDULE_ID, true)).thenReturn(List.of(slot));

        service = new ShiftPdfService(scheduleService, slotService, new PdfGeneratorService(engine, fontConfig));
    }

    private static String extractText(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("チーム全体表: タイトル・期間・枠の日付/時刻/ポジション名/必要人数が PDF に出る")
    void teamPdfContainsNestedDtoValues() throws Exception {
        String text = extractText(service.generateTeamPdf(SCHEDULE_ID, USER_ID, true));

        assertThat(text)
                .contains("十月前半シフト")
                .contains("2026-10-01")
                .contains("2026-10-15")
                .contains("2026-10-03")
                .contains("09:30")
                .contains("17:45")
                .contains("レジ担当");
        // 枠の行は「日付 開始 終了 ポジション名 必要人数 メンバー」の並び。日付・時刻と衝突しない必要人数(6)を行単位で検証する
        assertThat(normalize(text)).contains("2026-10-03 09:30 17:45 レジ担当 6 7, 8");
    }

    @Test
    @DisplayName("個人タイムライン: タイトル・期間・枠の日付/時刻/ポジション名が PDF に出る")
    void personalPdfContainsNestedDtoValues() throws Exception {
        String text = extractText(service.generatePersonalPdf(SCHEDULE_ID, USER_ID, true));

        assertThat(text)
                .contains("十月前半シフト")
                .contains("2026-10-01")
                .contains("2026-10-15")
                .contains("2026-10-03")
                .contains("09:30")
                .contains("17:45")
                .contains("レジ担当");
        // 枠の行は「日付 開始 終了 ポジション名」の並びで出る
        assertThat(normalize(text)).contains("2026-10-03 09:30 17:45 レジ担当");
    }
}
