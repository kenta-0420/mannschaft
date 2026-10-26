package com.mannschaft.app.favorite.dto;

/**
 * お気に入りエンティティの利用可否状態。
 *
 * <p>AVAILABLE: エンティティが存在し、かつ現在ユーザーがアクセス可能な状態。
 * UNAVAILABLE: エンティティが削除済み・未存在、またはアクセス権がない状態。</p>
 */
public enum FavoriteEntityStatus {
    /** 利用可能（エンティティが存在し、アクセス権あり） */
    AVAILABLE,
    /** 利用不可（削除済み・存在しない・アクセス権なし） */
    UNAVAILABLE
}
