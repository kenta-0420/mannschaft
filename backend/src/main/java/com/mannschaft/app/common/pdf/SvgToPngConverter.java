package com.mannschaft.app.common.pdf;

import com.mannschaft.app.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;

/**
 * SVG 文字列を PNG バイト配列に変換するユーティリティ。
 * Flying Saucer 単体では SVG レンダリングに対応していないため、
 * Apache Batik で事前変換する。
 */
@Slf4j
public final class SvgToPngConverter {

    private SvgToPngConverter() {}

    /**
     * SVG 文字列を PNG バイト配列に変換する。
     *
     * @param svgContent SVG 文字列
     * @param width      出力幅（px）
     * @param height     出力高さ（px）
     * @return PNG の byte[]
     */
    public static byte[] convert(String svgContent, int width, int height) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) width);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) height);

            TranscoderInput input = new TranscoderInput(new StringReader(svgContent));
            TranscoderOutput output = new TranscoderOutput(outputStream);

            transcoder.transcode(input, output);

            return outputStream.toByteArray();
        } catch (Exception e) {
            log.error("SVG → PNG 変換失敗", e);
            throw new BusinessException(PdfErrorCode.PDF_004);
        }
    }
}
