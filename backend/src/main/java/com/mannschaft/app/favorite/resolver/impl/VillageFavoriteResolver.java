package com.mannschaft.app.favorite.resolver.impl;

import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;
import com.mannschaft.app.favorite.resolver.FavoriteEntityResolver;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 村お気に入りのResolver実装（未対応）。
 *
 * <p>本 Resolver は<b>全 ID を UNAVAILABLE で返す</b>。したがって村はお気に入りに登録できず
 * （{@code addFavorite} は {@code FAV_003}）、既存行があっても表示メタ（村名等）は返らない
 * ＝ 村の属性がお気に入り経由で漏れることはない。</p>
 *
 * <p>TODO: 村お気に入りを解禁する際は、村の公開設定（UNLISTED 等）に沿った可視性判定を
 * 本 Resolver に実装したうえで AVAILABLE を返すこと。無条件に村名を返す実装にしてはならない。</p>
 */
@Component
public class VillageFavoriteResolver implements FavoriteEntityResolver {

    @Override
    public FavoriteEntityType entityType() {
        return FavoriteEntityType.VILLAGE;
    }

    @Override
    public Map<String, FavoriteEntityMetaDto> resolveAll(List<String> entityIds, Long currentUserId) {
        // 村お気に入りは未対応。全IDをUNAVAILABLEで返す（村の属性は一切返さない）
        Map<String, FavoriteEntityMetaDto> result = new HashMap<>();
        for (String entityId : entityIds) {
            result.put(entityId, FavoriteEntityMetaDto.unavailable(entityId, FavoriteEntityType.VILLAGE));
        }
        return result;
    }
}
