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
    PDF_005("PDF_005", "画像サイズが上限を超えています", Severity.WARN),
    /** F12.1 §5.14 / F09.15 §9.4 内部署名検証失敗 */
    PDF_006("PDF_006", "PDF の内部署名トークン検証に失敗しました", Severity.WARN),
    /** F12.1 §5.14 内部署名鍵未設定（起動時 fail-fast 用） */
    PDF_007("PDF_007", "PDF 内部署名鍵が設定されていません", Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;
}
