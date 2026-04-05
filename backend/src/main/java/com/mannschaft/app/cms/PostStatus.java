package com.mannschaft.app.cms;

/**
 * ブログ記事の公開ステータス。
 */
public enum PostStatus {
    DRAFT,
    PENDING_REVIEW,
    PENDING_SELF_REVIEW,
    PUBLISHED,
    REJECTED,
    ARCHIVED
}
