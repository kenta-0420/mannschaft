package com.mannschaft.app.scopefolder.dto;

import java.util.List;

/**
 * スコープフォルダレスポンスDTO。
 * フロントエンドはitemScopeIdsを用いて実際のチーム/組織情報をストアからenrichする。
 *
 * <p>F15.3 で {@code isDefault} / {@code icon} / {@code notificationUnreadCount} を追加。</p>
 *
 * @param id                      フォルダID
 * @param name                    フォルダ名
 * @param color                   カラーコード（#RRGGBB）。NULL 可
 * @param icon                    PrimeIcons アイコン名（例: pi-briefcase）。NULL 可
 * @param sortOrder               並び順
 * @param isDefault               未分類フォルダフラグ
 * @param itemScopeIds            フォルダに含まれるスコープ ID（チーム / 組織）一覧
 * @param notificationUnreadCount フォルダ単位の未読通知件数（取得元 API が集計しない場合は 0）
 */
public record ScopeFolderResponse(
        Long id,
        String name,
        String color,
        String icon,
        int sortOrder,
        boolean isDefault,
        List<Long> itemScopeIds,
        long notificationUnreadCount
) {
}
