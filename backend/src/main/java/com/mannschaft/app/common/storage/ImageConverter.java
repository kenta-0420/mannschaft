package com.mannschaft.app.common.storage;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 画像変換ユーティリティ。
 * WebP変換・サムネイル生成を提供し、CloudFront転送コストを削減する。
 */
@Slf4j
public final class ImageConverter {

    public static final String WEBP_CONTENT_TYPE = "image/webp";
    public static final String WEBP_FORMAT = "webp";

    private ImageConverter() {
    }

    /**
     * 画像バイト配列をWebP形式に変換する。
     * 変換に失敗した場合は元のデータをそのまま返す。
     *
     * @param imageBytes    元画像のバイト配列
     * @param sourceFormat  元画像のContent-Type（ログ用）
     * @return WebP変換後のバイト配列（変換不可の場合は元データ）
     */
    public static ConversionResult convertToWebP(byte[] imageBytes, String sourceFormat) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                log.warn("画像読み込み失敗（非画像ファイル）: format={}", sourceFormat);
                return new ConversionResult(imageBytes, sourceFormat, false);
            }

            byte[] webpBytes = writeWebP(image);
            if (webpBytes.length == 0) {
                log.warn("WebP変換失敗: format={}", sourceFormat);
                return new ConversionResult(imageBytes, sourceFormat, false);
            }

            log.debug("WebP変換完了: {}→WebP, {}bytes→{}bytes ({}%削減)",
                    sourceFormat, imageBytes.length, webpBytes.length,
                    100 - (webpBytes.length * 100L / imageBytes.length));
            return new ConversionResult(webpBytes, WEBP_CONTENT_TYPE, true);
        } catch (IOException e) {
            log.warn("WebP変換中にエラー: format={}", sourceFormat, e);
            return new ConversionResult(imageBytes, sourceFormat, false);
        }
    }

    /**
     * サムネイル画像を生成してWebP形式で返す。
     *
     * @param imageBytes  元画像のバイト配列
     * @param maxSize     サムネイルの最大辺（px）
     * @return サムネイルのバイト配列（WebP形式）
     */
    public static ConversionResult createThumbnailWebP(byte[] imageBytes, int maxSize) throws IOException {
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (original == null) {
            throw new IOException("画像の読み込みに失敗しました");
        }

        BufferedImage thumbnail = resize(original, maxSize);
        byte[] webpBytes = writeWebP(thumbnail);

        if (webpBytes.length > 0) {
            return new ConversionResult(webpBytes, WEBP_CONTENT_TYPE, true);
        }

        // WebP変換失敗時はJPEGフォールバック
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(thumbnail, "JPEG", baos);
        return new ConversionResult(baos.toByteArray(), "image/jpeg", false);
    }

    /**
     * 画像のリサイズ（アスペクト比維持）。
     */
    static BufferedImage resize(BufferedImage original, int maxSize) {
        int origWidth = original.getWidth();
        int origHeight = original.getHeight();

        double scale = Math.min((double) maxSize / origWidth, (double) maxSize / origHeight);
        if (scale >= 1.0) {
            scale = 1.0;
        }

        int thumbWidth = (int) (origWidth * scale);
        int thumbHeight = (int) (origHeight * scale);

        BufferedImage thumbnail = new BufferedImage(thumbWidth, thumbHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = thumbnail.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(original, 0, 0, thumbWidth, thumbHeight, null);
        g2d.dispose();

        return thumbnail;
    }

    private static byte[] writeWebP(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        boolean written = ImageIO.write(image, WEBP_FORMAT, baos);
        return written ? baos.toByteArray() : new byte[0];
    }

    /**
     * 画像変換の結果。
     *
     * @param data        変換後のバイト配列
     * @param contentType 変換後のContent-Type
     * @param converted   WebP変換に成功したかどうか
     */
    public record ConversionResult(byte[] data, String contentType, boolean converted) {
    }
}
