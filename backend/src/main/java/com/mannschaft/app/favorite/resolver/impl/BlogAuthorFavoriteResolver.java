package com.mannschaft.app.favorite.resolver.impl;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;
import com.mannschaft.app.favorite.dto.FavoriteEntityStatus;
import com.mannschaft.app.favorite.resolver.FavoriteEntityResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ブログ著者お気に入りのResolver実装。
 *
 * <p>UserRepositoryでバッチ取得し、自分自身のプロフィールのみ編集可として扱う。
 * 退会（匿名化）済みユーザーは@SQLRestrictionにより自動除外される。</p>
 *
 * <p><b>可視性</b>: 表示メタ（表示名・アバター）を返す対象は、本人自身、または
 * 公開プロフィール設定（{@link UserEntity#isPublicProfileEnabled()}）を有効にしている
 * ユーザーに限る。判定基準は公開プロフィール API（{@code PublicUserProfileQueryService}）と
 * 同一であり、お気に入り経由で別の可視性判定が生まれないようにしている。
 * 対象外のユーザーは UNAVAILABLE（{@code available=false}）として名称・アイコンを返さない。</p>
 */
@Component
@RequiredArgsConstructor
public class BlogAuthorFavoriteResolver implements FavoriteEntityResolver {

    private final UserRepository userRepository;
    private final MediaUrlResolver mediaUrlResolver;

    @Override
    public FavoriteEntityType entityType() {
        return FavoriteEntityType.BLOG_AUTHOR;
    }

    @Override
    public Map<String, FavoriteEntityMetaDto> resolveAll(List<String> entityIds, Long currentUserId) {
        Map<Long, String> idMapping = new HashMap<>();
        Map<String, FavoriteEntityMetaDto> result = new HashMap<>();

        for (String entityId : entityIds) {
            try {
                idMapping.put(Long.parseLong(entityId), entityId);
            } catch (NumberFormatException e) {
                result.put(entityId, FavoriteEntityMetaDto.unavailable(entityId, FavoriteEntityType.BLOG_AUTHOR));
            }
        }

        if (!idMapping.isEmpty()) {
            // バッチ取得（@SQLRestrictionにより論理削除済みユーザーは自動除外）
            List<UserEntity> users = userRepository.findByIdIn(idMapping.keySet());
            // 本人自身、または公開プロフィールを有効にしているユーザーのみ表示対象とする
            // （公開プロフィール API と同一判定。それ以外は下の UNAVAILABLE 処理に落ちる）
            users = users.stream()
                    .filter(u -> u.isPublicProfileEnabled()
                            || (currentUserId != null && currentUserId.equals(u.getId())))
                    .toList();
            Set<Long> foundIds = users.stream().map(UserEntity::getId).collect(Collectors.toSet());

            for (UserEntity user : users) {
                String entityId = idMapping.get(user.getId());
                // 自分のプロフィールのみ編集可。他者のブログはリードオンリー
                boolean canEdit = currentUserId != null && currentUserId.equals(user.getId());
                result.put(entityId, new FavoriteEntityMetaDto(
                        entityId,
                        FavoriteEntityType.BLOG_AUTHOR,
                        user.getDisplayName(),
                        // DB には生の R2 キーが入る。表示用署名付き URL へ解決して返す（生キーは 404）。
                        mediaUrlResolver.resolve(user.getAvatarUrl()),
                        "/users/" + user.getId() + "/blog",
                        canEdit,
                        FavoriteEntityStatus.AVAILABLE
                ));
            }

            // 存在しないID（論理削除・匿名化済み含む）、閲覧対象外のIDはUNAVAILABLE
            for (Map.Entry<Long, String> entry : idMapping.entrySet()) {
                if (!foundIds.contains(entry.getKey())) {
                    result.put(entry.getValue(), FavoriteEntityMetaDto.unavailable(entry.getValue(), FavoriteEntityType.BLOG_AUTHOR));
                }
            }
        }

        return result;
    }
}
