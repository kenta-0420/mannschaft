package com.mannschaft.app.seal.service;

import com.mannschaft.app.seal.SealVariant;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 電子印鑑 SVG 生成・SHA-256 ハッシュ計算ユーティリティ。
 * 日本の印鑑スタイル（丸印）の SVG を生成する。
 */
@Component
public class SealGenerator {

    private static final int SEAL_SIZE = 100;
    private static final int SEAL_RADIUS = 45;
    private static final String SEAL_COLOR = "#D12B2B";
    private static final int STROKE_WIDTH = 3;

    /**
     * 印鑑 SVG を生成する。
     *
     * @param displayText 表示テキスト
     * @param variant     印鑑バリアント
     * @return SVG 文字列
     */
    public String generateSvg(String displayText, SealVariant variant) {
        int cx = SEAL_SIZE / 2;
        int cy = SEAL_SIZE / 2;

        StringBuilder svg = new StringBuilder();
        svg.append(String.format(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">",
                SEAL_SIZE, SEAL_SIZE, SEAL_SIZE, SEAL_SIZE));

        // 背景透過
        svg.append(String.format(
                "<circle cx=\"%d\" cy=\"%d\" r=\"%d\" fill=\"none\" stroke=\"%s\" stroke-width=\"%d\" opacity=\"0.85\"/>",
                cx, cy, SEAL_RADIUS, SEAL_COLOR, STROKE_WIDTH));

        // テキスト配置（文字数に応じてレイアウトを変える）
        if (displayText.length() <= 2) {
            // 1-2文字: 横書き
            int fontSize = displayText.length() == 1 ? 40 : 28;
            svg.append(String.format(
                    "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" dominant-baseline=\"central\" "
                            + "fill=\"%s\" font-size=\"%d\" font-family=\"serif\" font-weight=\"bold\">%s</text>",
                    cx, cy, SEAL_COLOR, fontSize, escapeXml(displayText)));
        } else if (displayText.length() <= 4) {
            // 3-4文字: 縦書き（上から下）
            int fontSize = 22;
            int startY = cy - (displayText.length() - 1) * fontSize / 2;
            for (int i = 0; i < displayText.length(); i++) {
                svg.append(String.format(
                        "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" dominant-baseline=\"central\" "
                                + "fill=\"%s\" font-size=\"%d\" font-family=\"serif\" font-weight=\"bold\">%s</text>",
                        cx, startY + i * fontSize, SEAL_COLOR, fontSize,
                        escapeXml(String.valueOf(displayText.charAt(i)))));
            }
        } else {
            // 5文字以上: 2列縦書き
            int fontSize = 18;
            int half = (displayText.length() + 1) / 2;
            // 右列（先頭〜half）
            int rightX = cx + 14;
            int startY1 = cy - (half - 1) * fontSize / 2;
            for (int i = 0; i < half; i++) {
                svg.append(String.format(
                        "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" dominant-baseline=\"central\" "
                                + "fill=\"%s\" font-size=\"%d\" font-family=\"serif\" font-weight=\"bold\">%s</text>",
                        rightX, startY1 + i * fontSize, SEAL_COLOR, fontSize,
                        escapeXml(String.valueOf(displayText.charAt(i)))));
            }
            // 左列（half〜末尾）
            int leftX = cx - 14;
            int remaining = displayText.length() - half;
            int startY2 = cy - (remaining - 1) * fontSize / 2;
            for (int i = half; i < displayText.length(); i++) {
                svg.append(String.format(
                        "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" dominant-baseline=\"central\" "
                                + "fill=\"%s\" font-size=\"%d\" font-family=\"serif\" font-weight=\"bold\">%s</text>",
                        leftX, startY2 + (i - half) * fontSize, SEAL_COLOR, fontSize,
                        escapeXml(String.valueOf(displayText.charAt(i)))));
            }
        }

        svg.append("</svg>");
        return svg.toString();
    }

    /**
     * SVG データの SHA-256 ハッシュを計算する。
     *
     * @param svgData SVG 文字列
     * @return 16進数表現の SHA-256 ハッシュ
     */
    public String computeHash(String svgData) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(svgData.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String escapeXml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
