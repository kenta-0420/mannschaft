package com.mannschaft.app.role.dto;

/**
 * 招待による参加リクエストDTO（F15.3 §5.1.1）。
 *
 * <p>後方互換維持のためボディは Optional。{@code folderId} 未指定時は
 * 「未分類」フォルダへ自動配置される。</p>
 *
 * @param folderId 配置先のマイスコープフォルダ ID（任意）。NULL 時は未分類へ自動配置
 */
public record InviteJoinRequest(
        Long folderId
) {
}
