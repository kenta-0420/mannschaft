package com.mannschaft.app.gallery;

/** ギャラリーメディアの後処理ステータス。 */
public enum GalleryProcessingStatus {
    PENDING,      // アップロード直後（Workers 処理待ち）
    PROCESSING,   // Workers 実行中
    READY,        // 完了（閲覧可能）
    FAILED        // 失敗（プレースホルダー表示）
}
