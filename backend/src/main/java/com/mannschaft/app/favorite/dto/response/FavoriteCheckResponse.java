package com.mannschaft.app.favorite.dto.response;

/**
 * お気に入り登録状態チェックのレスポンス。
 *
 * <p>{@code GET /api/v1/me/favorites/check?entityType=X&entityId=Y}
 * のレスポンスとして用いる。
 *
 * <p>フロントエンドの {@code FavoriteToggleButton.vue} がマウント時に呼び出し、
 * トグルボタンの初期状態（オン/オフ）と削除時に使う favoriteId を取得する。
 *
 * <p>未登録の場合は {@code isFavorited=false} かつ {@code favoriteId=null} を返す。
 *
 * <p>WHY: Java record を使う理由 — Lombok {@code @Value} + {@code boolean isFavorited}
 * は Jackson が "is" 接頭辞を剥がして {@code favorited} としてシリアライズしてしまう。
 * record は component 名そのままで JSON 出力されるため、フロント型と整合する。
 *
 * @param isFavorited 登録済みなら true
 * @param favoriteId  登録済みなら favoriteId（UUID 文字列）、未登録なら null
 */
public record FavoriteCheckResponse(boolean isFavorited, String favoriteId) {
}
