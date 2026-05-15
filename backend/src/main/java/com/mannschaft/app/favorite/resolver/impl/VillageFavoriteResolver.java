package com.mannschaft.app.favorite.resolver.impl;

import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;
import com.mannschaft.app.favorite.resolver.FavoriteEntityResolver;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 村お気に入りのResolver実装（スタブ）。
 *
 * <p>F17.1 村機能が未実装のため、全IDをUNAVAILABLEで返す。
 * TODO: F17.1 実装時にこのスタブを本実装に置き換える。</p>
 */
@Component
public class VillageFavoriteResolver implements FavoriteEntityResolver {

    @Override
    public FavoriteEntityType entityType() {
        return FavoriteEntityType.VILLAGE;
    }

    @Override
    public Map<String, FavoriteEntityMetaDto> resolveAll(List<String> entityIds, Long currentUserId) {
        // F17.1 村機能未実装のため、全IDをUNAVAILABLEで返す
        Map<String, FavoriteEntityMetaDto> result = new HashMap<>();
        for (String entityId : entityIds) {
            result.put(entityId, FavoriteEntityMetaDto.unavailable(entityId, FavoriteEntityType.VILLAGE));
        }
        return result;
    }
}
