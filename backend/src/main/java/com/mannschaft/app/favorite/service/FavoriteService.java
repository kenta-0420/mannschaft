package com.mannschaft.app.favorite.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.FavoriteErrorCode;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;
import com.mannschaft.app.favorite.dto.FavoriteEntityStatus;
import com.mannschaft.app.favorite.dto.FavoriteItemDto;
import com.mannschaft.app.favorite.entity.UserFavoriteEntity;
import com.mannschaft.app.favorite.repository.UserFavoriteRepository;
import com.mannschaft.app.favorite.resolver.FavoriteEntityResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * お気に入りCRUD主ロジックサービス。
 *
 * <p>お気に入りの追加・削除・並び替え・一覧取得を提供する。
 * @Transactionalはfavoriteドメイン内に閉じる（CLAUDE.md 原則5）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final UserFavoriteRepository userFavoriteRepository;
    private final FavoriteResolverService favoriteResolverService;
    /** entityType別のResolver（addFavorite時の存在確認に使用） */
    private final List<FavoriteEntityResolver> resolvers;

    /**
     * ユーザーのお気に入り一覧を表示順で取得する。
     *
     * @param userId ユーザーID
     * @return お気に入りDTOリスト（表示順昇順）
     */
    @Transactional(readOnly = true)
    public List<FavoriteItemDto> getFavorites(Long userId) {
        List<UserFavoriteEntity> favorites = userFavoriteRepository.findByUserIdOrderByDisplayOrderAsc(userId);
        if (favorites.isEmpty()) {
            return List.of();
        }

        Map<String, FavoriteEntityMetaDto> metaMap = favoriteResolverService.resolveAll(favorites, userId);

        return favorites.stream()
                .map(f -> toDto(f, metaMap.get(f.getEntityId())))
                .toList();
    }

    /**
     * お気に入りを追加する。
     *
     * <p>バリデーション順: entityIdフォーマット → エンティティ存在+アクセス確認 → 件数上限チェック → 保存。</p>
     *
     * @param userId     ユーザーID
     * @param entityType エンティティ種別
     * @param entityId   エンティティID（文字列）
     * @return 追加されたお気に入りDTO
     */
    @Transactional
    public FavoriteItemDto addFavorite(Long userId, FavoriteEntityType entityType, String entityId) {
        // entityIdフォーマット検証
        validateEntityIdFormat(entityType, entityId);

        // エンティティ存在＋アクセス確認
        FavoriteEntityResolver resolver = findResolver(entityType);
        Map<String, FavoriteEntityMetaDto> metaMap = resolver.resolveAll(List.of(entityId), userId);
        FavoriteEntityMetaDto meta = metaMap.get(entityId);
        if (meta == null || meta.status() == FavoriteEntityStatus.UNAVAILABLE) {
            throw new BusinessException(FavoriteErrorCode.FAV_003);
        }

        // 上限チェック（20件）
        if (userFavoriteRepository.countByUserId(userId) >= 20) {
            throw new BusinessException(FavoriteErrorCode.FAV_002);
        }

        try {
            // 既存エントリの表示順を全て+1して先頭に空きを作る
            userFavoriteRepository.incrementAllDisplayOrders(userId);

            // display_order=0（先頭）でお気に入りを保存
            UserFavoriteEntity entity = new UserFavoriteEntity();
            entity.setUserId(userId);
            entity.setEntityType(entityType);
            entity.setEntityId(entityId);
            entity.setDisplayOrder((short) 0);
            entity = userFavoriteRepository.save(entity);

            return toDto(entity, meta);

        } catch (DataIntegrityViolationException e) {
            // UNIQUE制約違反（同一エンティティを再登録）
            throw new BusinessException(FavoriteErrorCode.FAV_001);
        }
    }

    /**
     * お気に入りを削除する。
     *
     * @param userId     ユーザーID
     * @param favoriteId お気に入りエンティティのID
     */
    @Transactional
    public void removeFavorite(Long userId, UUID favoriteId) {
        UserFavoriteEntity entity = userFavoriteRepository.findById(favoriteId)
                .orElseThrow(() -> new BusinessException(FavoriteErrorCode.FAV_003));

        // 他ユーザーのお気に入りへのアクセス試行を防ぐ（IDOR対策）
        if (!entity.getUserId().equals(userId)) {
            throw new BusinessException(FavoriteErrorCode.FAV_004);
        }

        userFavoriteRepository.delete(entity);
    }

    /**
     * お気に入りの表示順を一括更新する。
     *
     * @param userId     ユーザーID
     * @param orderedIds 新しい表示順でのお気に入りIDリスト
     */
    @Transactional
    public void reorderFavorites(Long userId, List<UUID> orderedIds) {
        List<UserFavoriteEntity> favorites = userFavoriteRepository.findByUserIdOrderByDisplayOrderAsc(userId);

        // 全エントリが当該ユーザーのものか検証（IDOR対策）
        for (UserFavoriteEntity fav : favorites) {
            if (!fav.getUserId().equals(userId)) {
                throw new BusinessException(FavoriteErrorCode.FAV_004);
            }
        }

        // 各IDにインデックス順でdisplayOrderを割り当て
        Map<UUID, UserFavoriteEntity> favMap = new java.util.HashMap<>();
        for (UserFavoriteEntity fav : favorites) {
            favMap.put(fav.getId(), fav);
        }

        for (int i = 0; i < orderedIds.size(); i++) {
            UserFavoriteEntity fav = favMap.get(orderedIds.get(i));
            if (fav == null) {
                throw new BusinessException(FavoriteErrorCode.FAV_003);
            }
            fav.setDisplayOrder((short) i);
        }

        userFavoriteRepository.saveAll(favorites);
    }

    /**
     * IDでお気に入りを1件取得する。
     *
     * @param userId     ユーザーID
     * @param favoriteId お気に入りエンティティのID
     * @return お気に入りDTO
     */
    @Transactional(readOnly = true)
    public FavoriteItemDto getFavoriteById(Long userId, UUID favoriteId) {
        UserFavoriteEntity entity = userFavoriteRepository.findById(favoriteId)
                .orElseThrow(() -> new BusinessException(FavoriteErrorCode.FAV_003));

        // IDOR対策
        if (!entity.getUserId().equals(userId)) {
            throw new BusinessException(FavoriteErrorCode.FAV_004);
        }

        Map<String, FavoriteEntityMetaDto> metaMap = favoriteResolverService.resolveAll(List.of(entity), userId);
        return toDto(entity, metaMap.get(entity.getEntityId()));
    }

    // ========================================
    // プライベートヘルパー
    // ========================================

    /**
     * entityIdのフォーマットをentityTypeに合わせて検証する。
     * 不正な形式の場合はFAV_006をスローする。
     */
    private void validateEntityIdFormat(FavoriteEntityType entityType, String entityId) {
        try {
            if (entityType == FavoriteEntityType.VILLAGE) {
                UUID.fromString(entityId);
            } else {
                Long.parseLong(entityId);
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessException(FavoriteErrorCode.FAV_006);
        }
    }

    /**
     * entityTypeに対応するResolverを取得する。
     * 対応するResolverが存在しない場合はFAV_005をスローする。
     */
    private FavoriteEntityResolver findResolver(FavoriteEntityType entityType) {
        return resolvers.stream()
                .filter(r -> r.entityType() == entityType)
                .findFirst()
                .orElseThrow(() -> new BusinessException(FavoriteErrorCode.FAV_005));
    }

    /**
     * UserFavoriteEntityとFavoriteEntityMetaDtoをFavoriteItemDtoに変換する。
     * metaがnullの場合はavailable=falseのフォールバックDTOを返す。
     */
    private FavoriteItemDto toDto(UserFavoriteEntity entity, FavoriteEntityMetaDto meta) {
        boolean available = meta != null && meta.status() == FavoriteEntityStatus.AVAILABLE;
        return new FavoriteItemDto(
                entity.getId(),
                entity.getEntityType(),
                entity.getEntityId(),
                entity.getDisplayOrder(),
                meta != null ? meta.displayName() : null,
                meta != null ? meta.iconUrl() : null,
                meta != null ? meta.pageUrl() : null,
                meta != null && meta.canEdit(),
                available,
                entity.getCreatedAt()
        );
    }
}
