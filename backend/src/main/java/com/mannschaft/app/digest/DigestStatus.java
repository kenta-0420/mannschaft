package com.mannschaft.app.digest;

/**
 * ダイジェストのステータス。
 */
public enum DigestStatus {
    /** AI 処理中 */
    GENERATING,
    /** 生成完了・未公開 */
    GENERATED,
    /** ブログ記事として公開済み */
    PUBLISHED,
    /** 破棄 */
    DISCARDED,
    /** 生成失敗 */
    FAILED
}
