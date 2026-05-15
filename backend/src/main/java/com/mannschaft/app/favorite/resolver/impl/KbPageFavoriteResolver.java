package com.mannschaft.app.favorite.resolver.impl;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;
import com.mannschaft.app.favorite.dto.FavoriteEntityStatus;
import com.mannschaft.app.favorite.resolver.FavoriteEntityResolver;
import com.mannschaft.app.knowledgebase.PageAccessLevel;
import com.mannschaft.app.knowledgebase.entity.KbPageEntity;
import com.mannschaft.app.knowledgebase.repository.KbPageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ナレッジベースページお気に入りのResolver実装。
 *
 * <p>KbPageRepositoryでバッチ取得し、アクセス権の有無も判定する。
 * 非メンバー（getRoleName = null）のページはUNAVAILABLEとして返す。</p>
 */
@Component
@RequiredArgsConstructor
public class KbPageFavoriteResolver implements FavoriteEntityResolver {

    private final KbPageRepository kbPageRepository;
    private final AccessControlService accessControlService;

    @Override
    public FavoriteEntityType entityType() {
        return FavoriteEntityType.KB_PAGE;
    }

    @Override
    public Map<String, FavoriteEntityMetaDto> resolveAll(List<String> entityIds, Long currentUserId) {
        Map<Long, String> idMapping = new HashMap<>();
        Map<String, FavoriteEntityMetaDto> result = new HashMap<>();

        for (String entityId : entityIds) {
            try {
                idMapping.put(Long.parseLong(entityId), entityId);
            } catch (NumberFormatException e) {
                result.put(entityId, FavoriteEntityMetaDto.unavailable(entityId, FavoriteEntityType.KB_PAGE));
            }
        }

        if (!idMapping.isEmpty()) {
            // @SQLRestrictionにより論理削除済みページは自動除外される
            List<KbPageEntity> pages = kbPageRepository.findAllById(idMapping.keySet());
            Set<Long> foundIds = pages.stream().map(KbPageEntity::getId).collect(Collectors.toSet());

            for (KbPageEntity page : pages) {
                String entityId = idMapping.get(page.getId());
                Long scopeId = page.getScopeId();

                // チームメンバーでない場合はUNAVAILABLE（アクセス権なし）
                String roleName = accessControlService.getRoleName(currentUserId, scopeId, "TEAM");
                if (roleName == null) {
                    result.put(entityId, FavoriteEntityMetaDto.unavailable(entityId, FavoriteEntityType.KB_PAGE));
                    continue;
                }

                boolean canEdit = resolveCanEdit(page.getAccessLevel(), roleName);
                String pageUrl = "/teams/" + scopeId + "/knowledge-base/" + page.getSlug();

                result.put(entityId, new FavoriteEntityMetaDto(
                        entityId,
                        FavoriteEntityType.KB_PAGE,
                        page.getTitle(),
                        null,  // KBページはアイコンなし
                        pageUrl,
                        canEdit,
                        FavoriteEntityStatus.AVAILABLE
                ));
            }

            // 存在しないID（論理削除済み含む）はUNAVAILABLE
            for (Map.Entry<Long, String> entry : idMapping.entrySet()) {
                if (!foundIds.contains(entry.getKey()) && !result.containsKey(entry.getValue())) {
                    result.put(entry.getValue(), FavoriteEntityMetaDto.unavailable(entry.getValue(), FavoriteEntityType.KB_PAGE));
                }
            }
        }

        return result;
    }

    /**
     * ページのアクセスレベルとユーザーのロール名から編集可否を判定する。
     */
    private boolean resolveCanEdit(PageAccessLevel accessLevel, String roleName) {
        return switch (accessLevel) {
            // 全メンバーが編集可
            case ALL_MEMBERS -> true;
            // ADMIN/DEPUTY_ADMINのみ編集可
            case ADMIN_ONLY -> Set.of("ADMIN", "DEPUTY_ADMIN").contains(roleName);
            // カスタム設定時は保守的にADMIN/DEPUTY_ADMINのみ編集可（詳細権限はF02.9以降で拡張）
            case CUSTOM -> Set.of("ADMIN", "DEPUTY_ADMIN").contains(roleName);
        };
    }
}
