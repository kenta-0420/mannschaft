package com.mannschaft.app.team.service;

import com.mannschaft.app.team.TeamQueryMapper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** チーム一覧の読み取り境界。Entityを公開せず、一覧用情報を一括取得する。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamQueryService {

    private final TeamRepository teamRepository;
    private final TeamQueryMapper teamQueryMapper;

    /** 他ドメインへ渡すチーム一覧情報。公開範囲はEntityのenumに依存しない文字列とする。 */
    public record TeamListItem(Long id, String slug, String name, String visibility,
                               Instant archivedAt, String template) {
    }

    /** チームID群を1 SQLで取得する。不存在・論理削除済みは除外し、空入力はSQLを発行しない。 */
    public Map<Long, TeamListItem> findListItemsByIds(Collection<Long> teamIds) {
        Map<Long, TeamListItem> result = new LinkedHashMap<>();
        if (teamIds == null || teamIds.isEmpty()) {
            return result;
        }
        for (TeamEntity team : teamRepository.findAllById(teamIds)) {
            TeamListItem item = teamQueryMapper.toListItem(team);
            result.put(item.id(), item);
        }
        return result;
    }
}
