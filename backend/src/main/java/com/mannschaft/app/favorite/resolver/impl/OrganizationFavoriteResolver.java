package com.mannschaft.app.favorite.resolver.impl;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;
import com.mannschaft.app.favorite.dto.FavoriteEntityStatus;
import com.mannschaft.app.favorite.resolver.FavoriteEntityResolver;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 組織お気に入りのResolver実装。
 *
 * <p>OrganizationRepositoryでバッチ取得し、存在しないIDはUNAVAILABLEとして返す。
 * @SQLRestrictionにより論理削除済み組織は自動的に除外される。</p>
 */
@Component
@RequiredArgsConstructor
public class OrganizationFavoriteResolver implements FavoriteEntityResolver {

    private final OrganizationRepository organizationRepository;
    private final AccessControlService accessControlService;
    private final MediaUrlResolver mediaUrlResolver;

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
            List<OrganizationEntity> orgs = organizationRepository.findAllById(idMapping.keySet());
            Set<Long> foundIds = orgs.stream().map(OrganizationEntity::getId).collect(Collectors.toSet());

            for (OrganizationEntity org : orgs) {
                String entityId = idMapping.get(org.getId());
                boolean canEdit = accessControlService.isAdminOrAbove(currentUserId, org.getId(), "ORGANIZATION");
                result.put(entityId, new FavoriteEntityMetaDto(
                        entityId,
                        FavoriteEntityType.ORGANIZATION,
                        org.getName(),
                        // DB には生の R2 キーが入る。表示用署名付き URL へ解決して返す（生キーは 404）。
                        mediaUrlResolver.resolve(org.getIconUrl()),
                        "/organizations/" + org.getId(),
                        canEdit,
                        FavoriteEntityStatus.AVAILABLE
                ));
            }

            // 存在しないID（論理削除済み含む）はUNAVAILABLE
            for (Map.Entry<Long, String> entry : idMapping.entrySet()) {
                if (!foundIds.contains(entry.getKey())) {
                    result.put(entry.getValue(), FavoriteEntityMetaDto.unavailable(entry.getValue(), FavoriteEntityType.ORGANIZATION));
                }
            }
        }

        return result;
    }
}
