package com.mannschaft.app.favorite.dto.response;

import com.mannschaft.app.favorite.dto.FavoriteItemDto;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * お気に入りAPIレスポンスDTO。
 *
 * <p>サービス層の {@link FavoriteItemDto} をフロントエンド向けに整形したクラス。
 * available=false の場合、displayName・iconUrl・pageUrl は null になる。</p>
 */
@Getter
@Builder
public class FavoriteResponse {

    private final UUID id;
    /** FavoriteEntityType の name()（例: "TEAM"）。 */
    private final String entityType;
    private final String entityId;
    private final int displayOrder;
    /** エンティティの表示名。UNAVAILABLE 時は null。 */
    private final String displayName;
    /** アイコンURL。UNAVAILABLE 時は null。 */
    private final String iconUrl;
    /** ナビゲーション先URL。UNAVAILABLE 時は null。 */
    private final String pageUrl;
    /** クイック編集ボタン表示フラグ。 */
    private final boolean canEdit;
    /** true=利用可能、false=利用不可（削除済み等）。 */
    private final boolean available;
    private final LocalDateTime createdAt;

    /**
     * FavoriteItemDto を FavoriteResponse に変換する。
     *
     * @param dto サービス層DTO
     * @return APIレスポンスDTO
     */
    public static FavoriteResponse from(FavoriteItemDto dto) {
        return FavoriteResponse.builder()
                .id(dto.id())
                .entityType(dto.entityType().name())
                .entityId(dto.entityId())
                .displayOrder(dto.displayOrder())
                .displayName(dto.displayName())
                .iconUrl(dto.iconUrl())
                .pageUrl(dto.pageUrl())
                .canEdit(dto.canEdit())
                .available(dto.available())
                .createdAt(dto.createdAt())
                .build();
    }
}
