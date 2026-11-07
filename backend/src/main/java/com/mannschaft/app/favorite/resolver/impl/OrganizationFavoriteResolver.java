package com.mannschaft.app.favorite.resolver.impl;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;
import com.mannschaft.app.favorite.dto.FavoriteEntityStatus;
import com.mannschaft.app.favorite.resolver.FavoriteEntityResolver;
import com.mannschaft.app.organization.dto.OrganizationNameIconView;
import com.mannschaft.app.organization.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 組織お気に入りのResolver実装。
 *
 * <p>公開Serviceで名前・アイコンとslugをバッチ取得し、存在しないIDはUNAVAILABLEとして返す。
 * @SQLRestrictionにより論理削除済み組織は自動的に除外される。</p>
 *
 * <p><b>可視性（認可根治戦役 第1波）</b>: 表示メタ（組織名・アイコン）を返す対象は、
 * F00 共通可視性ラダー（{@link ContentVisibilityChecker#filterAccessible}）で
 * <b>閲覧できると判定された組織のみ</b>に限る。閲覧できない組織（非公開組織の非メンバー・
 * アーカイブ済み等）は UNAVAILABLE（{@code available=false}）として名称・アイコンを返さない。</p>
 */
@Component
@RequiredArgsConstructor
public class OrganizationFavoriteResolver implements FavoriteEntityResolver {

    private final OrganizationService organizationService;
    private final AccessControlService accessControlService;
    private final MediaUrlResolver mediaUrlResolver;
    private final ContentVisibilityChecker contentVisibilityChecker;

    @Override
    public FavoriteEntityType entityType() {
        return FavoriteEntityType.ORGANIZATION;
    }

    @Override
    public Map<String, FavoriteEntityMetaDto> resolveAll(List<String> entityIds, Long currentUserId) {
        Map<Long, String> idMapping = new HashMap<>();
        Map<String, FavoriteEntityMetaDto> result = new HashMap<>();

        for (String entityId : entityIds) {
            try {
                idMapping.put(Long.parseLong(entityId), entityId);
            } catch (NumberFormatException e) {
                result.put(entityId, FavoriteEntityMetaDto.unavailable(entityId, FavoriteEntityType.ORGANIZATION));
            }
        }

        if (!idMapping.isEmpty()) {
            // F00可視性ラダーを先に適用し、公開Serviceには閲覧可能なIDだけを渡す。
            Set<Long> visibleIds = contentVisibilityChecker.filterAccessible(
                    ReferenceType.ORGANIZATION, idMapping.keySet(), currentUserId);
            Map<Long, OrganizationNameIconView> nameIcons = organizationService.getNameIconsByIds(visibleIds);
            Map<Long, String> slugs = organizationService.getSlugsByIds(visibleIds);

            for (Map.Entry<Long, String> entry : idMapping.entrySet()) {
                Long id = entry.getKey();
                String entityId = entry.getValue();
                OrganizationNameIconView nameIcon = nameIcons.get(id);
                String slug = slugs.get(id);
                // 閲覧不可・一括取得の欠落時は名前やURLを公開しない。
                if (!visibleIds.contains(id) || nameIcon == null || slug == null) {
                    result.put(entityId, FavoriteEntityMetaDto.unavailable(entityId, FavoriteEntityType.ORGANIZATION));
                    continue;
                }
                boolean canEdit = accessControlService.isAdminOrAbove(currentUserId, id, "ORGANIZATION");
                result.put(entityId, new FavoriteEntityMetaDto(
                        entityId,
                        FavoriteEntityType.ORGANIZATION,
                        nameIcon.name(),
                        // DB には生の R2 キーが入る。表示用署名付き URL へ解決して返す（生キーは 404）。
                        mediaUrlResolver.resolve(nameIcon.iconUrl()),
                        "/organizations/" + slug,
                        canEdit,
                        FavoriteEntityStatus.AVAILABLE
                ));
            }
        }

        return result;
    }
}
