package com.mannschaft.app.favorite.resolver;

import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;

import java.util.List;
import java.util.Map;

/**
 * お気に入りエンティティのメタデータ解決インターフェース。
 *
 * <p>各エンティティドメイン（Team/Organization/KbPage等）がこのインターフェースを実装し、
 * FavoriteResolverServiceがN+1問題を防ぎつつバッチ解決する。</p>
 *
 * <p>戻り値のMapには引数で渡した全IDに対するエントリが含まれること（存在しないIDはUNAVAILABLE）。</p>
 */
public interface FavoriteEntityResolver {

    /**
     * このResolverが対応するエンティティ種別を返す。
     */
    FavoriteEntityType entityType();

    /**
     * 指定したエンティティIDリストのメタデータをバッチ解決する。
     *
     * @param entityIds     エンティティIDリスト（BIGINT系は十進数文字列、VILLAGEはUUID文字列）
     * @param currentUserId 現在のユーザーID（権限判定に使用）
     * @return entityId → FavoriteEntityMetaDto のマップ（全IDに対するエントリを含む）
     */
    Map<String, FavoriteEntityMetaDto> resolveAll(List<String> entityIds, Long currentUserId);
}
