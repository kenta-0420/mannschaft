package com.mannschaft.app.village.entity.enums;

/**
 * 村への参加・投稿主体種別。
 *
 * <ul>
 *   <li>{@link #USER}: 個人（自分自身）</li>
 *   <li>{@link #TEAM}: チーム代表として参加・投稿</li>
 *   <li>{@link #ORGANIZATION}: 組織代表として参加・投稿</li>
 * </ul>
 */
public enum VillageSubjectType {
    USER,
    TEAM,
    ORGANIZATION
}
