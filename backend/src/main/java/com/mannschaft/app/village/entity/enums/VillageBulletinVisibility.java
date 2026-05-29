package com.mannschaft.app.village.entity.enums;

/**
 * 村掲示板の公開範囲（F17.1 村掲示板グローバル方式）。
 *
 * <p>村本体の {@link VillageVisibility}（検索可否）とは独立した概念で、
 * 村掲示板（スレッド／カテゴリ）の閲覧可否を制御する。</p>
 *
 * <ul>
 *   <li>{@link #PUBLIC}: 村の非メンバー（ログイン済ユーザー）でも掲示板を閲覧可</li>
 *   <li>{@link #MEMBERS_ONLY}: 村メンバーのみ閲覧可（デフォルト）</li>
 * </ul>
 */
public enum VillageBulletinVisibility {
    PUBLIC,
    MEMBERS_ONLY
}
