package com.mannschaft.app.pointcard.enums;

/**
 * F18 個人ポイントカードウォレットで扱えるバーコード形式。設計書 §4.3 準拠。
 */
public enum BarcodeFormat {
    /** Code 128（最も汎用的な 1D バーコード）。 */
    CODE128,
    /** Code 39（旧式の英数字 1D バーコード）。 */
    CODE39,
    /** EAN-13（13 桁、JAN13 と互換）。 */
    EAN13,
    /** EAN-8（8 桁）。 */
    EAN8,
    /** JAN-13（日本国内の商品コード、EAN13 と同形式）。 */
    JAN13,
    /** QR コード。 */
    QR,
    /** PDF417（2D バーコード、運転免許証等で使用）。 */
    PDF417,
    /** Interleaved 2 of 5（数値専用 1D バーコード）。 */
    ITF,
    /** バーコードを持たない（番号のみ提示）。 */
    NONE
}
