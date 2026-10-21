package com.mannschaft.app.team.service;

import com.mannschaft.app.team.repository.TeamRepository;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 閲覧者の所属によらず、公開有効なフォロー先概要だけを返す読取境界。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamFollowTargetQueryService {
    private final TeamRepository teamRepository;

    public Map<Long, TeamFollowTargetSummary> findDisclosableSummaries(Collection<Long> teamIds) {
        if (teamIds.isEmpty()) {
            return Map.of();
        }
        return teamRepository.findDisclosableFollowTargetSummaries(teamIds).stream()
                .collect(Collectors.toMap(TeamFollowTargetSummary::id, Function.identity()));
    }
}
