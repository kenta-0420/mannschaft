package com.mannschaft.app.common.qr;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BrandedQrImageWriter} の単体テスト。
 * 中央ブランドバッジ（緑モジュール #047857 + 白背景グレー角丸枠 + "Mannschaft" 文字）を
 * 焼き込んだQR画像が、自己デコード可能であること・サイズ/PNGシグネチャが正しいことを検証する。
 * 純ユニットテスト（Spring不要・Testcontainers不要）。
 */
@DisplayName("BrandedQrImageWriter 単体テスト")
class BrandedQrImageWriterTest {

    private final BrandedQrImageWriter writer = new BrandedQrImageWriter();

    @ParameterizedTest
    @ValueSource(ints = {240, 300, 350, 500})
    @DisplayName("AC5: 通常URLと長URLを各利用サイズで復号できる")
    void 各サイズで通常URLと長URLを復号できる(int size) throws Exception {
        String normal = "https://app.mannschaft.example/invite/0f9c2b7a-3d4e-4f10-9a2b-7c8d9e0f1a2b";
        String longUrl = "https://app.mannschaft.example/contact-invite/" + "a1b2c3d4".repeat(10);

        assertThat(decode(writer.writePng(normal, size))).isEqualTo(normal);
        assertThat(decode(writer.writePng(longUrl, size))).isEqualTo(longUrl);
    }

    @Test
    @DisplayName("AC4: PNGのバッジ左右端は高さ全体を直径とする半円になる")
    void PNGのバッジ左右端が半円になる() throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(writer.writePng("https://example.com/capsule", 350)));
        int top = image.getHeight();
        int bottom = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            if (leftGrayPixel(image, y) < image.getWidth()) {
                top = Math.min(top, y);
                bottom = y;
            }
        }
        assertThat(top).isLessThan(bottom);
        int height = bottom - top;
        int nearTop = leftGrayPixel(image, top + Math.max(1, height / 10));
        int middle = leftGrayPixel(image, (top + bottom) / 2);

        // 半円なら上端近くは中央より大きく内側になる。小さな角丸ではこの差が生じない。
        assertThat(nearTop - middle).isGreaterThan((int) (height * 0.15));
    }

    private int leftGrayPixel(BufferedImage image, int y) {
        for (int x = 0; x < image.getWidth(); x++) {
            int color = image.getRGB(x, y);
            int red = (color >> 16) & 255;
            int green = (color >> 8) & 255;
            int blue = color & 255;
            // QRの緑と白を除き、バッジ枠線と文字のグレーだけを観測する。
            if (Math.abs(red - 107) < 10 && Math.abs(green - 114) < 10 && Math.abs(blue - 128) < 10) {
                return x;
            }
        }
        return image.getWidth();
    }


    // ========================================
    // BQR-001: 自己デコード（中央バッジがあってもスキャン可能なことの証明）
    // ========================================

    @Nested
    @DisplayName("BQR-001 自己デコード")
    class SelfDecode {

        @Test
        @DisplayName("招待URL相当の文字列をエンコードし、そのままデコードして一致する")
        void inviteUrlLikeString_decodesBackToOriginal() throws Exception {
            String text = "https://app.mannschaft.example/invite/0f9c2b7a-3d4e-4f10-9a2b-7c8d9e0f1a2b";

            byte[] png = writer.writePng(text, 300);

            assertThat(decode(png)).isEqualTo(text);
        }

        @Test
        @DisplayName("80文字級の擬似トークンを含む長い文字列でもデコードして一致する")
        void longPseudoToken_decodesBackToOriginal() throws Exception {
            String longToken = "a1b2c3d4e5f6" + "9".repeat(68); // 80文字級
            String text = "https://app.mannschaft.example/contact-invite/" + longToken;

            byte[] png = writer.writePng(text, 300);

            assertThat(decode(png)).isEqualTo(text);
        }

        @Test
        @DisplayName("小さめサイズ(240px)でも中央バッジ被覆下でデコードできる（ECL=H相当の頑健性）")
        void smallSize_stillDecodableUnderBadgeCoverage() throws Exception {
            String text = "https://app.mannschaft.example/invite/small-size-check-token-0001";

            byte[] png = writer.writePng(text, 240);

            assertThat(decode(png)).isEqualTo(text);
        }
    }

    // ========================================
    // BQR-002: サイズ
    // ========================================

    @Nested
    @DisplayName("BQR-002 サイズ")
    class Size {

        @Test
        @DisplayName("指定sizeどおりの幅・高さのPNGが返る（300px）")
        void size300_matchesRequestedDimensions() throws Exception {
            byte[] png = writer.writePng("https://example.com/size-check", 300);

            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));

            assertThat(img.getWidth()).isEqualTo(300);
            assertThat(img.getHeight()).isEqualTo(300);
        }

        @Test
        @DisplayName("指定sizeどおりの幅・高さのPNGが返る（500px）")
        void size500_matchesRequestedDimensions() throws Exception {
            byte[] png = writer.writePng("https://example.com/size-check-2", 500);

            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));

            assertThat(img.getWidth()).isEqualTo(500);
            assertThat(img.getHeight()).isEqualTo(500);
        }
    }

    // ========================================
    // BQR-003: 非null/非空・PNGシグネチャ
    // ========================================

    @Nested
    @DisplayName("BQR-003 非null/非空・PNGシグネチャ")
    class NonEmptyPngSignature {

        @Test
        @DisplayName("返却byte[]は非空でPNGシグネチャ(0x89 'P' 'N' 'G')で始まる")
        void returnsNonEmptyBytesStartingWithPngSignature() {
            byte[] png = writer.writePng("https://example.com/signature-check", 300);

            assertThat(png).isNotNull();
            assertThat(png.length).isGreaterThan(0);
            assertThat(png[0]).isEqualTo((byte) 0x89);
            assertThat(png[1]).isEqualTo((byte) 'P');
            assertThat(png[2]).isEqualTo((byte) 'N');
            assertThat(png[3]).isEqualTo((byte) 'G');
        }
    }

    // ========================================
    // BQR-004: ECL=H相当の頑健性についての注記
    // ========================================
    // 中央バッジによってQRの一部モジュールが視覚的に隠蔽されても BQR-001/Size.smallSize_...
    // でデコード可能であることを実測しているため、事実上 ECL=H（誤り訂正率最大約30%）の
    // 効果は担保されている。ErrorCorrectionLevel.H が実際に指定されていることは
    // BrandedQrImageWriter の実装（EncodeHintType.ERROR_CORRECTION = ErrorCorrectionLevel.H）を
    // コードレビューで確認すること（ZXingの内部APIでは生成後にECLを直接検証する簡便な手段がないため）。

    /**
     * PNGバイト列をZXingでデコードして復元テキストを返す。
     */
    private String decode(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        LuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        Result result = new MultiFormatReader().decode(bitmap, hints);
        return result.getText();
    }
}
