package com.mannschaft.app.common.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * 中央ブランドバッジ入りQRコード画像（PNG）を生成する共通ユーティリティ。
 * フロントエンド共有コンポーネント {@code QrCodeImage.vue} の見た目
 * （緑モジュール #047857・中央やや下に白背景/グレー角丸枠/グレー太字「Mannschaft」バッジ）
 * にBE生成QRを統一するために新設した。
 *
 * <p>誤り訂正レベルは常に {@link ErrorCorrectionLevel#H}（最大約30%）を使用する。
 * これはバッジがQRコードの一部モジュールを視覚的に隠蔽してもスキャン可能にするため。
 * BrandedQrImageWriterTest の BQR-001 で、バッジ被覆下でも自己デコード可能であることを実測している。</p>
 */
@Component
public class BrandedQrImageWriter {

    /** バッジに表示するブランド名。 */
    private static final String BRAND_TEXT = "Mannschaft";

    /** QRモジュールの前景色（緑）。ARGB。 */
    private static final int QR_ON_COLOR = 0xFF047857;

    /** QRモジュールの背景色（白）。ARGB。 */
    private static final int QR_OFF_COLOR = 0xFFFFFFFF;

    /** バッジ文字色（グレー）。 */
    private static final Color BADGE_TEXT_COLOR = new Color(0x6b7280);

    /** バッジ枠線色（グレー）。文字色と同一。 */
    private static final Color BADGE_BORDER_COLOR = new Color(0x6b7280);

    /** バッジ背景色（白）。 */
    private static final Color BADGE_BACKGROUND_COLOR = Color.WHITE;

    /** バッジ文字サイズの size に対する比率。 */
    private static final float BADGE_FONT_SIZE_RATIO = 0.052f;

    /** バッジ左右パディングの size に対する比率。 */
    private static final float BADGE_PADDING_X_RATIO = 0.05f;

    /** バッジ上下パディングの size に対する比率。 */
    private static final float BADGE_PADDING_Y_RATIO = 0.04f;

    /** バッジ角丸半径の size に対する比率。 */
    private static final float BADGE_CORNER_RADIUS_RATIO = 0.05f;

    /** バッジ枠線幅の size に対する比率（下限2px）。 */
    private static final float BADGE_BORDER_WIDTH_RATIO = 0.009f;

    /** バッジ枠線幅の下限（px）。 */
    private static final float BADGE_BORDER_WIDTH_MIN = 2f;

    /** バッジ幅の size に対する上限比率（超える場合はフォントを縮小する）。 */
    private static final float BADGE_MAX_WIDTH_RATIO = 0.46f;

    /** バッジの垂直位置補正（中央から「バッジ高さの約0.1個分」下にずらす）。 */
    private static final float BADGE_VERTICAL_OFFSET_RATIO = 0.1f;

    /**
     * 中央ブランドバッジ入りQRコードをPNGバイト配列として生成する。
     *
     * @param text テキスト（招待URL等）。ZXingでQR_CODEとしてエンコードする
     * @param size QR画像の一辺のサイズ（px）
     * @return PNGバイト配列
     */
    public byte[] writePng(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);

            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix matrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, size, size, hints);

            MatrixToImageConfig config = new MatrixToImageConfig(QR_ON_COLOR, QR_OFF_COLOR);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix, config);

            drawCenterBadge(image, size);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("ブランドバッジ入りQRコードの生成に失敗しました", e);
        }
    }

    /**
     * QR画像の中央（やや下）にブランドバッジ（白背景＋グレー角丸枠＋グレー太字「Mannschaft」）を合成する。
     */
    private void drawCenterBadge(BufferedImage image, int size) {
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            float fontSize = size * BADGE_FONT_SIZE_RATIO;
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, Math.round(fontSize));
            g.setFont(font);
            FontMetrics metrics = g.getFontMetrics();

            float paddingX = size * BADGE_PADDING_X_RATIO;
            float paddingY = size * BADGE_PADDING_Y_RATIO;

            int textWidth = metrics.stringWidth(BRAND_TEXT);
            int textHeight = metrics.getAscent() + metrics.getDescent();

            float badgeWidth = textWidth + paddingX * 2;
            float maxBadgeWidth = size * BADGE_MAX_WIDTH_RATIO;
            if (badgeWidth > maxBadgeWidth) {
                // バッジ幅が上限を超える場合はフォントを縮小して再計測する。
                float scale = maxBadgeWidth / badgeWidth;
                font = font.deriveFont(fontSize * scale);
                g.setFont(font);
                metrics = g.getFontMetrics();
                textWidth = metrics.stringWidth(BRAND_TEXT);
                textHeight = metrics.getAscent() + metrics.getDescent();
                badgeWidth = Math.min(textWidth + paddingX * 2, maxBadgeWidth);
            }

            float badgeHeight = textHeight + paddingY * 2;
            float cornerRadius = size * BADGE_CORNER_RADIUS_RATIO;
            float borderWidth = Math.max(BADGE_BORDER_WIDTH_MIN, size * BADGE_BORDER_WIDTH_RATIO);

            float centerX = size / 2f;
            float centerY = size / 2f + badgeHeight * BADGE_VERTICAL_OFFSET_RATIO;

            float badgeX = centerX - badgeWidth / 2f;
            float badgeY = centerY - badgeHeight / 2f;

            RoundRectangle2D badgeShape = new RoundRectangle2D.Float(
                    badgeX, badgeY, badgeWidth, badgeHeight, cornerRadius, cornerRadius);

            g.setColor(BADGE_BACKGROUND_COLOR);
            g.fill(badgeShape);

            g.setColor(BADGE_BORDER_COLOR);
            g.setStroke(new java.awt.BasicStroke(borderWidth));
            g.draw(badgeShape);

            g.setColor(BADGE_TEXT_COLOR);
            int textX = Math.round(centerX - textWidth / 2f);
            int textY = Math.round(centerY - textHeight / 2f + metrics.getAscent());
            g.drawString(BRAND_TEXT, textX, textY);
        } finally {
            g.dispose();
        }
    }
}
