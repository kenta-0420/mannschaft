package com.mannschaft.app.translation;

/**
 * 翻訳コンテンツのステータス。
 */
public enum TranslationStatus {

    /** 下書き */
    DRAFT,

    /** レビュー中 */
    IN_REVIEW,

    /** 公開済み */
    PUBLISHED,

    /** 原文更新により再翻訳が必要 */
    NEEDS_UPDATE
}
