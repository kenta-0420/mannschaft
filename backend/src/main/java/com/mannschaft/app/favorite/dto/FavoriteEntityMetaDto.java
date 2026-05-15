package com.mannschaft.app.favorite.dto;

import com.mannschaft.app.favorite.FavoriteEntityType;

/**
 * Resolverが返す内部DTO。
 *
 * <p>各エンティティの表示に必要なメタデータを統一インターフェースで返す。
 * status が UNAVAILABLE の場合、displayName・iconUrl・pageUrl は null になることがある。</p>
 *
 * @param entityId    エンティティID（文字列形式）
 * @param entityType  エンティティ種別
 * @param displayName 表示名（UNAVAILABLE時はnullまたは「（削除済み）」等）
 * @param iconUrl     アイコンURL（UNAVAILABLE時またはアイコンなし時はnull）
 * @param pageUrl     ナビゲーション先URL（UNAVAILABLE時はnull）
 * @param canEdit     クイック編集ボタン表示フラグ
 * @param status      利用可否状態
 */
public record FavoriteEntityMetaDto(
        String entityId,
        FavoriteEntityType entityType,
        String displayName,
        String iconUrl,
        String pageUrl,
        boolean canEdit,
        FavoriteEntityStatus status
) {

    /**
     * UNAVAILABLE なメタDTOを生成するファクトリメソッド。
     */
    public static FavoriteEntityMetaDto unavailable(String entityId, FavoriteEntityType entityType) {
        return new FavoriteEntityMetaDto(entityId, entityType, null, null, null, false, FavoriteEntityStatus.UNAVAILABLE);
    }
}
