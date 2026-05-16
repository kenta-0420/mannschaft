package com.mannschaft.app.favorite.dto;

import java.util.UUID;

/**
 * Service層のお気に入りチェック結果（内部DTO）。
 *
 * <p>Controller 側で {@link com.mannschaft.app.favorite.dto.response.FavoriteCheckResponse}
 * に変換する。Service 層では UUID 型のまま保持し、API レスポンス時に文字列化することで
 * 型安全性を確保する設計とする。</p>
 *
 * @param isFavorited 登録済みなら true
 * @param favoriteId  登録済みなら favoriteId、未登録なら null
 */
public record FavoriteCheckResultDto(boolean isFavorited, UUID favoriteId) {
}
