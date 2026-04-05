package com.mannschaft.app.common.pdf;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * PDF生成機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum PdfErrorCode implements ErrorCode {
    PDF_001("PDF_001", "PDF テンプレートの読み込みに失敗しました", Severity.ERROR),
    PDF_002("PDF_002", "PDF の生成に失敗しました", Severity.ERROR),
    PDF_003("PDF_003", "フォントの読み込みに失敗しました", Severity.ERROR),
    PDF_004("PDF_004", "SVG の変換に失敗しました", Severity.ERROR),
    PDF_005("PDF_005", "画像サイズが上限を超えています", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
