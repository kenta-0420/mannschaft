package com.mannschaft.app.favorite.dto;

import com.mannschaft.app.favorite.FavoriteEntityType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * FavoriteServiceが返すサービス層DTO。
 *
 * <p>お気に入り一覧・個別取得APIのレスポンスデータとして使用する。
 * available=false の場合、displayName・iconUrl・pageUrl は null になる可能性がある。</p>
 *
 * @param id          UserFavoriteEntity の主キー
 * @param entityType  エンティティ種別
 * @param entityId    エンティティID（文字列形式）
 * @param displayOrder 表示順
 * @param displayName 表示名
 * @param iconUrl     アイコンURL
 * @param pageUrl     ナビゲーション先URL
 * @param canEdit     クイック編集ボタン表示フラグ
 * @param available   true=利用可能、false=利用不可（削除済み等）
 * @param createdAt   お気に入り登録日時
 */
public record FavoriteItemDto(
        UUID id,
        FavoriteEntityType entityType,
        String entityId,
        int displayOrder,
        String displayName,
        String iconUrl,
        String pageUrl,
        boolean canEdit,
        boolean available,
        LocalDateTime createdAt
) {
}
