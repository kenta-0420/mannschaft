package com.mannschaft.app.disclosure;

/**
 * 重要事項説明書ドラフトのステータス。
 * F09.14 設計書 §3 disclosure_form_drafts.status に対応。
 */
public enum DraftStatus {

    /** 入力中（編集可能） */
    DRAFT,

    /** 出力準備完了（必須項目入力済み） */
    READY,

    /** 出力済み（再編集時は新規ドラフトとしてコピー） */
    EXPORTED
}
