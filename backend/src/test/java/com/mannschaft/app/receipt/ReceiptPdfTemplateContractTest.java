package com.mannschaft.app.receipt;

import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.config.PdfFontConfig;
import com.mannschaft.app.receipt.entity.ReceiptEntity;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * F08.12 §5.1「PDF 生成の一本化」の前提整備に対する試練（red）。
 *
 * <p>本テストはモックを一切使わず、<strong>実在するテンプレートファイル・実在する
 * {@link ReceiptEntity}・実在する {@link PdfFontConfig}</strong> を通して PDF を生成する。
 * すなわちここが赤いということは、団体側の既存の領収書 PDF も本番で壊れているということである。
 *
 * <p>対応する受け入れ条件:
 * <ul>
 *   <li>AC-13: 団体領収書 PDF が 200 で返る（現状 {@code receipt.issuedDate} /
 *       {@code receipt.totalAmount} の Thymeleaf 評価例外で必ず失敗する）</li>
 *   <li>AC-11: {@code PdfFontConfig.getRegisteredFonts()} が空でない
 *       （現状 {@code resources/fonts/} は {@code .gitkeep} のみ）</li>
 *   <li>AC-12: 生成 PDF のテキスト抽出に日本語（宛名・但し書き）が含まれる</li>
 * </ul>
 */
@DisplayName("F08.12 領収書 PDF テンプレート契約テスト")
class ReceiptPdfTemplateContractTest {

    private ReceiptPdfGeneratorImpl generator;
    private PdfFontConfig fontConfig;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        fontConfig = new PdfFontConfig();
        fontConfig.init();

        generator = new ReceiptPdfGeneratorImpl(new PdfGeneratorService(engine, fontConfig));
    }

    /** テスト用の領収書エンティティ（日本語の宛名・但し書きを持つ）。 */
    private ReceiptEntity japaneseReceipt() {
        return ReceiptEntity.builder()
                .scopeType(ReceiptScopeType.TEAM)
                .scopeId(1L)
                .status(ReceiptStatus.ISSUED)
                .receiptNumber("R-2026-09-00001")
                .recipientName("株式会社まんしゃふと")
                .issuerName("運営事務局")
                .description("広告掲載料として")
                .amount(new BigDecimal("11000"))
                .taxRate(new BigDecimal("10.00"))
                .taxAmount(new BigDecimal("1000"))
                .amountExclTax(new BigDecimal("10000"))
                .paymentDate(LocalDate.of(2026, 9, 1))
                .issuedAt(LocalDateTime.of(2026, 9, 2, 10, 0))
                .issuedBy(1L)
                .build();
    }

    @Test
    @DisplayName("AC-13: pdf/receipt テンプレートが現行 ReceiptEntity で評価でき、PDF が生成される")
    void ac13_receiptTemplateRendersWithCurrentEntity() {
        ReceiptEntity receipt = japaneseReceipt();

        assertThatCode(() -> generator.generate(receipt, List.of(), null, null, null))
                .as("テンプレートが receipt.issuedDate / receipt.totalAmount という"
                        + "存在しないプロパティを参照していると Thymeleaf 評価例外で必ず失敗する")
                .doesNotThrowAnyException();

        byte[] pdf = generator.generate(receipt, List.of(), null, null, null);
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1))
                .as("PDF ヘッダで始まること")
                .startsWith("%PDF-");
    }

    @Test
    @DisplayName("AC-11: PdfFontConfig.getRegisteredFonts() が空でない（日本語フォントの実体が存在する）")
    void ac11_registeredFontsAreNotEmpty() {
        assertThat(fontConfig.getRegisteredFonts())
                .as("resources/fonts/ に NotoSansJP / NotoSerifJP の実体が無いと optional=true で"
                        + "黙ってスキップされ、日本語が出ない PDF が静かに作られる")
                .isNotEmpty()
                .extracting(PdfFontConfig.FontEntry::familyName)
                .contains("NotoSansJP", "NotoSerifJP");
    }

    @Test
    @DisplayName("AC-12: 生成した PDF のテキスト抽出に日本語（宛名・但し書き）が含まれる")
    void ac12_generatedPdfContainsJapaneseText() throws Exception {
        byte[] pdf = generator.generate(japaneseReceipt(), List.of(), null, null, null);

        String text;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(document);
        }

        assertThat(text)
                .as("日本語フォントが登録されていないと文字が欠落し、宛名が抽出できない")
                .contains("株式会社まんしゃふと")
                .contains("広告掲載料として");
    }

    @Test
    @DisplayName("AC-13(void): pdf/receipt-voided テンプレートも現行 ReceiptEntity で評価できる")
    void ac13_voidedReceiptTemplateRendersWithCurrentEntity() {
        ReceiptEntity receipt = japaneseReceipt();
        receipt.voidReceipt(1L, "テスト無効化");

        assertThatCode(() -> generator.generateVoided(receipt, List.of(), null, null, null))
                .doesNotThrowAnyException();
    }
}
