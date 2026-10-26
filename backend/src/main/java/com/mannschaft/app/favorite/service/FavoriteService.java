package com.mannschaft.app.favorite.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.FavoriteErrorCode;
import com.mannschaft.app.favorite.dto.FavoriteCheckResultDto;
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
    private final FavoriteAccessGuard favoriteAccessGuard;

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
     * <p>順序: <b>対象の閲覧可否（認可）</b> → entityIdフォーマット → エンティティ存在確認 →
     * 件数上限チェック → 保存。閲覧できない対象は {@code FAV_003}（404）で存在を秘匿し、
     * 上限・重複といった業務エラーの応答差から対象の実在が推測されないようにする
     * （{@link FavoriteAccessGuard#requireViewableTarget}）。</p>
     *
     * @param userId     ユーザーID
     * @param entityType エンティティ種別
     * @param entityId   エンティティID（文字列）
     * @return 追加されたお気に入りDTO
     */
    @Transactional
    public FavoriteItemDto addFavorite(Long userId, FavoriteEntityType entityType, String entityId) {
        // 認可: 対象が本人に閲覧可能か（F00 共通可視性ラダー・業務検証より前）
        favoriteAccessGuard.requireViewableTarget(userId, entityType, entityId);

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
        // 認可は FavoriteAccessGuard に一元化（不存在=FAV_003/404・他者所有=FAV_004/403）
        UserFavoriteEntity entity = favoriteAccessGuard.requireOwnedFavorite(userId, favoriteId);
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
        // 認可は FavoriteAccessGuard に一元化（不存在=FAV_003/404・他者所有=FAV_004/403）
        UserFavoriteEntity entity = favoriteAccessGuard.requireOwnedFavorite(userId, favoriteId);

        Map<String, FavoriteEntityMetaDto> metaMap = favoriteResolverService.resolveAll(List.of(entity), userId);
        return toDto(entity, metaMap.get(entity.getEntityId()));
    }

    /**
     * 指定エンティティが当該ユーザーのお気に入りに登録されているか確認する。
     *
     * <p>フロントエンドの {@code FavoriteToggleButton.vue} のマウント時に呼ばれ、
     * トグルボタンの初期状態と削除に用いる favoriteId を提供する用途。
     *
     * <p>entityId のフォーマット検証は実施しない（不正な ID なら未登録扱い＝
     * isFavorited=false で返す）。これは「チェック」が副作用なしの読み取り操作であり、
     * 不正な入力に対しても 200 で「登録されていません」と返すのが自然なため。
     *
     * @param userId     ユーザーID
     * @param entityType エンティティ種別
     * @param entityId   エンティティID（文字列）
     * @return チェック結果（登録済み true/false と favoriteId）
     */
    @Transactional(readOnly = true)
    public FavoriteCheckResultDto checkFavorite(Long userId, FavoriteEntityType entityType, String entityId) {
        return userFavoriteRepository.findByUserIdAndEntityTypeAndEntityId(userId, entityType, entityId)
                .map(entity -> new FavoriteCheckResultDto(true, entity.getId()))
                .orElse(new FavoriteCheckResultDto(false, null));
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
