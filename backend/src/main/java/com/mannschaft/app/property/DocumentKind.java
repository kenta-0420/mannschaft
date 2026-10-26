package com.mannschaft.app.property;

/**
 * 物件履歴文書（property_work_documents）の種別タグ。
 * F09.13 設計書 §3 property_work_documents.document_kind に対応。
 */
public enum DocumentKind {

    /** 議事録 */
    MINUTES,

    /** 見積書 */
    QUOTE,

    /** 契約書 */
    CONTRACT,

    /** 報告書 */
    REPORT,

    /** 写真 */
    PHOTO,

    /** 図面 */
    DRAWING,

    /** 請求書 */
    INVOICE,

    /** 領収書 */
    RECEIPT,

    /** その他 */
    OTHER
}
