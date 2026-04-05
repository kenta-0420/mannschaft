package com.mannschaft.app.digest;

/**
 * ダイジェストの生成スタイル。
 */
public enum DigestStyle {
    /** 箇条書き要約 */
    SUMMARY,
    /** 読み物風記事 */
    NARRATIVE,
    /** ハイライトピックアップ */
    HIGHLIGHTS,
    /** AI 不使用。統計ベーステンプレート */
    TEMPLATE
}
