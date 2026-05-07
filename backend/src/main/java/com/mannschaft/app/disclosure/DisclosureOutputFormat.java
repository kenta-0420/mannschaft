package com.mannschaft.app.disclosure;

/**
 * 重要事項説明書の出力形式。
 * F09.14 設計書 §3 disclosure_exports.output_format に対応。
 */
public enum DisclosureOutputFormat {

    /** PDF（Phase 1 から対応） */
    PDF,

    /** Excel (.xlsx)（Phase 1 から対応） */
    EXCEL,

    /** Word (.docx)（Phase 3 から対応） */
    WORD
}
