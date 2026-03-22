package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.dashboard.DashboardMapper;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.dto.ActivityFeedResponse;
import com.mannschaft.app.dashboard.entity.ActivityFeedEntity;
import com.mannschaft.app.dashboard.repository.ActivityFeedRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * アクティビティフィードのクエリサービス。
 * 個人ダッシュボードの「最近のアクティビティ」ウィジェットのデータ取得を担当する。
 * 書き込みは ActivityFeedEventListener が @Async で行う（別クラス）。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class ActivityFeedService {

    private final ActivityFeedRepository activityFeedRepository;
    private final DashboardMapper dashboardMapper;
    private final NameResolverService nameResolverService;

    /** アクティビティ取得のデフォルト件数 */
    private static final int DEFAULT_LIMIT = 10;
    /** アクティビティ取得の最大件数 */
    private static final int MAX_LIMIT = 50;

    /**
     * 所属スコープを横断してアクティビティフィードを取得する。
     * 自分の行動は含まない（actor_id != userId でフィルタ）。
     *
     * @param userId   ユーザーID
     * @param cursor   カーソル（アクティビティID。nullの場合は最新から）
     * @param limit    取得件数
     * @param scopeIds 対象スコープID一覧
     */
    public List<ActivityFeedResponse> getActivityFeed(Long userId, Long cursor, Integer limit, List<Long> scopeIds) {
        int resolvedLimit = resolveLimit(limit);

        List<ScopeType> scopeTypes = List.of(ScopeType.TEAM, ScopeType.ORGANIZATION);

        List<ActivityFeedEntity> entities;
        if (cursor != null) {
            entities = activityFeedRepository.findByScopeAndExcludeActorWithCursor(
                    scopeTypes, scopeIds, userId, cursor, PageRequest.of(0, resolvedLimit));
        } else {
            entities = activityFeedRepository.findByScopesAndExcludeActor(
                    scopeTypes, scopeIds, userId, PageRequest.of(0, resolvedLimit));
        }

        // actorId のバッチ名前解決
        Set<Long> actorIds = entities.stream()
                .map(ActivityFeedEntity::getActorId)
                .collect(Collectors.toSet());
        Map<Long, String> actorNames = nameResolverService.resolveUserDisplayNames(actorIds);

        // scopeType + scopeId のバッチ名前解決
        Set<Long> teamScopeIds = entities.stream()
                .filter(e -> e.getScopeType() == ScopeType.TEAM)
                .map(ActivityFeedEntity::getScopeId)
                .collect(Collectors.toSet());
        Set<Long> orgScopeIds = entities.stream()
                .filter(e -> e.getScopeType() == ScopeType.ORGANIZATION)
                .map(ActivityFeedEntity::getScopeId)
                .collect(Collectors.toSet());

        Map<Long, String> teamNames = nameResolverService.resolveTeamNames(teamScopeIds);
        Map<Long, String> orgNames = nameResolverService.resolveOrganizationNames(orgScopeIds);

        // scopeType ごとの名前マップを統合
        Map<ScopeType, Map<Long, String>> scopeNameMaps = new HashMap<>();
        scopeNameMaps.put(ScopeType.TEAM, teamNames);
        scopeNameMaps.put(ScopeType.ORGANIZATION, orgNames);

        return entities.stream()
                .map(entity -> {
                    String actorDisplayName = actorNames.getOrDefault(entity.getActorId(), "不明なユーザー");
                    String scopeName = scopeNameMaps
                            .getOrDefault(entity.getScopeType(), Map.of())
                            .getOrDefault(entity.getScopeId(), "不明なスコープ");
                    return dashboardMapper.toActivityFeedResponse(
                            entity,
                            new ActivityFeedResponse.ActorSummary(entity.getActorId(), actorDisplayName, null),
                            scopeName
                    );
                })
                .toList();
    }

    /**
     * 取得件数を解決する。
     */
    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
