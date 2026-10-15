package com.mannschaft.app.role;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.qr.BrandedQrImageWriter;
import com.mannschaft.app.config.PdfFontConfig;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 実製品のテンプレート・日本語フォント・Flying Saucerで招待QRの印刷契約を検証する。
 * 通常・100文字の和文/連続ASCII・任意メタデータ・失効状態で一枚に全文を保持する。
 * 実PDFの埋込画像も復号し、目視確認用のPDFと全ページPNGをbuild配下へ出力する。
 */
@DisplayName("招待QR PDFの一枚印刷契約")
class InviteQrPdfTemplateContractTest {

    private static final String INVITE_URL = "https://app.mannschaft.example/invite/0f9c2b7a-3d4e-4f10-9a2b-7c8d9e0f1a2b";
    private static final Path EVIDENCE = Path.of("build", "qr-print-preview");
    private PdfGeneratorService generator;

    @BeforeEach
    void 準備する() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        PdfFontConfig fonts = new PdfFontConfig();
        fonts.init();
        assertThat(fonts.getRegisteredFonts()).isNotEmpty();
        generator = new PdfGeneratorService(engine, fonts);
    }

    static Stream<PrintCase> 印刷条件() {
        return Stream.of(
                new PrintCase("boundary40", "あ".repeat(40), true, true, false),
                new PrintCase("boundary41", "あ".repeat(41), true, true, false),
                new PrintCase("normal", "まんしゃふとスポーツクラブ", false, false, false),
                new PrintCase("normal-meta", "まんしゃふとスポーツクラブ", true, false, false),
                new PrintCase("japanese100-revoked", "あいうえお".repeat(20), true, true, true),
                new PrintCase("ascii100-expired", "ABCDEFGHIJ".repeat(10), true, false, true),
                new PrintCase("japanese100-unlimited", "あいうえお".repeat(20), false, false, false),
                new PrintCase("ascii100-revoked", "ABCDEFGHIJ".repeat(10), true, true, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("印刷条件")
    @DisplayName("AC1/2/3/6: A4一頁に全文と状態を保持し埋込QRを復号できる")
    void 一頁に全文を保持してQRを読み取れる(PrintCase sample) throws Exception {
        byte[] qr = new BrandedQrImageWriter().writePng(INVITE_URL, 350);
        Map<String, Object> variables = new HashMap<>();
        variables.put("teamName", sample.teamName());
        variables.put("qrBase64", Base64.getEncoder().encodeToString(qr));
        variables.put("expiresAt", sample.metadata() ? LocalDateTime.of(2026, 10, 15, 23, 59) : null);
        variables.put("maxUses", sample.metadata() ? Integer.MAX_VALUE : null);
        variables.put("usedCount", Integer.MAX_VALUE);
        variables.put("isRevoked", sample.revoked());
        variables.put("isExpired", sample.expired());

        byte[] pdf = generator.generateFromTemplate("pdf/invite-qr", variables);

        Files.createDirectories(EVIDENCE);
        Files.write(EVIDENCE.resolve(sample.id() + ".pdf"), pdf);
        Files.write(EVIDENCE.resolve("qr-350.png"), qr);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                ImageIO.write(renderer.renderImageWithDPI(page, 144), "PNG",
                        EVIDENCE.resolve(sample.id() + "-page-" + (page + 1) + ".png").toFile());
            }
            assertThat(document.getNumberOfPages()).as(sample.id() + "のページ数").isEqualTo(1);
            assertThat(document.getPage(0).getMediaBox().getWidth()).isBetween(595f, 596f);
            assertThat(document.getPage(0).getMediaBox().getHeight()).isBetween(841f, 843f);
            BoundsTextStripper stripper = new BoundsTextStripper();
            String text = stripper.getText(document).replaceAll("\\s+", "");
            assertThat(text).contains(sample.teamName(), "Mannschaft始めました。",
                    "このQRコードをスキャンしてチームに参加してください。", "PoweredbyMannschaft");
            assertThat(stripper.positions).allSatisfy(position -> {
                assertThat(position.getXDirAdj()).isGreaterThanOrEqualTo(0);
                assertThat(position.getXDirAdj() + position.getWidthDirAdj())
                        .as("文字が用紙の右端を越えない").isLessThanOrEqualTo(596f);
            });
            if (sample.metadata()) {
                assertThat(text).contains("有効期限:2026年10月15日23:59", "最大利用回数:2147483647回", "使用済み:2147483647回");
            } else {
                assertThat(text).doesNotContain("有効期限:", "最大利用回数:", "使用済み:");
            }
            if (sample.revoked()) {
                assertThat(text).contains("このQRコードは無効化されています").doesNotContain("このQRコードは有効期限を過ぎています");
            } else if (sample.expired()) {
                assertThat(text).contains("このQRコードは有効期限を過ぎています").doesNotContain("このQRコードは無効化されています");
            } else {
                assertThat(text).doesNotContain("このQRコードは無効化されています", "このQRコードは有効期限を過ぎています");
            }
            int images = 0;
            var resources = document.getPage(0).getResources();
            for (var name : resources.getXObjectNames()) {
                if (resources.getXObject(name) instanceof PDImageXObject image) {
                    var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image.getImage())));
                    assertThat(new MultiFormatReader().decode(bitmap).getText()).isEqualTo(INVITE_URL);
                    assertThat(image.getWidth()).isEqualTo(350);
                    assertThat(image.getHeight()).isEqualTo(350);
                    images++;
                }
            }
            assertThat(images).as("PDFの招待QR画像").isEqualTo(1);
        }
    }

    /** 試験条件。時刻の判定はServiceの責務で、ここではテンプレートへ渡された状態を検査する。 */
    record PrintCase(String id, String teamName, boolean metadata, boolean revoked, boolean expired) {
        @Override
        public String toString() {
            return id;
        }
    }

    /** 抽出テキストが残っていても、連続ASCIIが用紙外へはみ出す失敗を検出する。 */
    private static class BoundsTextStripper extends PDFTextStripper {
        private final List<TextPosition> positions = new ArrayList<>();

        BoundsTextStripper() throws IOException {
            super();
            setSortByPosition(true);
        }

        @Override
        protected void processTextPosition(TextPosition position) {
            positions.add(position);
            super.processTextPosition(position);
        }
    }
}
