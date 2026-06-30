package com.mannschaft.app.favorite.resolver.impl;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;
import com.mannschaft.app.favorite.dto.FavoriteEntityStatus;
import com.mannschaft.app.favorite.resolver.FavoriteEntityResolver;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * チームお気に入りのResolver実装。
 *
 * <p>TeamRepositoryでバッチ取得し、存在しないIDはUNAVAILABLEとして返す。
 * @SQLRestrictionにより論理削除済みチームは自動的に除外される。</p>
 */
@Component
@RequiredArgsConstructor
public class TeamFavoriteResolver implements FavoriteEntityResolver {

    private final TeamRepository teamRepository;
    private final AccessControlService accessControlService;
    private final MediaUrlResolver mediaUrlResolver;

    @Override
    public FavoriteEntityType entityType() {
        return FavoriteEntityType.TEAM;
    }

    @Override
    public Map<String, FavoriteEntityMetaDto> resolveAll(List<String> entityIds, Long currentUserId) {
        // 文字列IDをLongに変換（変換できないIDはUNAVAILABLE扱い）
        Map<Long, String> idMapping = new HashMap<>();
        Map<String, FavoriteEntityMetaDto> result = new HashMap<>();

        for (String entityId : entityIds) {
            try {
                idMapping.put(Long.parseLong(entityId), entityId);
            } catch (NumberFormatException e) {
                result.put(entityId, FavoriteEntityMetaDto.unavailable(entityId, FavoriteEntityType.TEAM));
            }
        }

        if (!idMapping.isEmpty()) {
            // バッチ取得（@SQLRestrictionで論理削除済みは自動除外）
            List<TeamEntity> teams = teamRepository.findAllById(idMapping.keySet());
            Set<Long> foundIds = teams.stream().map(TeamEntity::getId).collect(Collectors.toSet());

            for (TeamEntity team : teams) {
                String entityId = idMapping.get(team.getId());
                boolean canEdit = accessControlService.isAdminOrAbove(currentUserId, team.getId(), "TEAM");
                result.put(entityId, new FavoriteEntityMetaDto(
                        entityId,
                        FavoriteEntityType.TEAM,
                        team.getName(),
                        // DB には生の R2 キーが入る。表示用署名付き URL へ解決して返す（生キーは 404）。
                        mediaUrlResolver.resolve(team.getIconUrl()),
                        "/teams/" + team.getId(),
                        canEdit,
                        FavoriteEntityStatus.AVAILABLE
                ));
            }

            // 存在しないID（論理削除済み含む）はUNAVAILABLE
            for (Map.Entry<Long, String> entry : idMapping.entrySet()) {
                if (!foundIds.contains(entry.getKey())) {
                    result.put(entry.getValue(), FavoriteEntityMetaDto.unavailable(entry.getValue(), FavoriteEntityType.TEAM));
                }
            }
        }

        return result;
    }
}
