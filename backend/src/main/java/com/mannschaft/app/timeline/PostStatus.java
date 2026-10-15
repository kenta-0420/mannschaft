package com.mannschaft.app.timeline;

/**
 * タイムライン投稿のステータス。
 */
public enum PostStatus {
    /** 公開中 */
    PUBLISHED,
    /** 下書き */
    DRAFT,
    /** 予約投稿 */
    SCHEDULED,
    /** 非表示（モデレーション等） */
    HIDDEN,
    /** 削除済み */
    DELETED
}
