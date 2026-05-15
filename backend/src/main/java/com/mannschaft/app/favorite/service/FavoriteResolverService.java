package com.mannschaft.app.favorite.service;

import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;
import com.mannschaft.app.favorite.entity.UserFavoriteEntity;
import com.mannschaft.app.favorite.resolver.FavoriteEntityResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * お気に入りエンティティのメタデータ解決ディスパッチャーサービス。
 *
 * <p>N+1問題を防ぐため、entityType別にグループ化してバッチ解決を行う。
 * Springが自動収集した全FavoriteEntityResolverをenumキーでキャッシュする。</p>
 */
@Slf4j
@Service
public class FavoriteResolverService {

    /** entityType → Resolver のマップ（起動時に構築、イミュータブル） */
    private final Map<FavoriteEntityType, FavoriteEntityResolver> resolverMap;

    public FavoriteResolverService(List<FavoriteEntityResolver> resolvers) {
        // SpringがDIした全Resolverをenumキーでマップ化
        this.resolverMap = resolvers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        FavoriteEntityResolver::entityType,
                        r -> r
                ));
    }

    /**
     * お気に入りエンティティリストのメタデータをバッチ解決する。
     *
     * <p>entityType別にグループ化して各Resolverに委譲することで、N+1問題を回避する。</p>
     *
     * @param favorites  お気に入りエンティティリスト
     * @param userId     現在のユーザーID
     * @return entityId → FavoriteEntityMetaDto のマップ
     */
    public Map<String, FavoriteEntityMetaDto> resolveAll(List<UserFavoriteEntity> favorites, Long userId) {
        Map<String, FavoriteEntityMetaDto> result = new HashMap<>();

        // entityType別にグループ化（N+1防止）
        Map<FavoriteEntityType, List<UserFavoriteEntity>> groupedByType = favorites.stream()
                .collect(Collectors.groupingBy(UserFavoriteEntity::getEntityType));

        for (Map.Entry<FavoriteEntityType, List<UserFavoriteEntity>> entry : groupedByType.entrySet()) {
            FavoriteEntityType type = entry.getKey();
            List<String> entityIds = entry.getValue().stream()
                    .map(UserFavoriteEntity::getEntityId)
                    .toList();

            FavoriteEntityResolver resolver = resolverMap.get(type);
            if (resolver == null) {
                // 対応するResolverが存在しない場合はログを出力してスキップ
                log.warn("FavoriteEntityType {} に対応するResolverが見つかりません。", type);
                continue;
            }

            Map<String, FavoriteEntityMetaDto> resolved = resolver.resolveAll(entityIds, userId);
            result.putAll(resolved);
        }

        return result;
    }
}
