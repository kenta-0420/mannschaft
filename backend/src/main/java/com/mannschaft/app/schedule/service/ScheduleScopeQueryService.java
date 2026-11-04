package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.dto.ScopeScheduleView;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 複数のチーム・組織の予定を期間指定でまとめて引く照会サービス（CMP-261103-1225 / #4270 の N+1 解消用・read-only）。
 *
 * <p>個人ダッシュボードの「今後の予定」が所属スコープごとに予定を引いていた（所属数比例の SQL）のを、
 * IN 句 1 クエリにまとめる。並びは旧実装（スコープごとに start_at 昇順で取得して連結）と同じ
 * 「渡されたスコープ ID の順 → 各スコープ内は start_at 昇順」に組み直して返す。
 * 他ドメインへ Entity を渡さない（番人 D-1 API 境界）ため {@link ScopeScheduleView} で返す。
 * 論理削除済みの予定は Entity の {@code @SQLRestriction} で除外される。入力が空なら SQL を発行しない。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleScopeQueryService {

    private final ScheduleRepository scheduleRepository;

    /**
     * 複数チームの予定を期間指定で返す。
     *
     * @param teamIds チーム ID 群（この順に並べる）
     * @param from    期間開始（含む）
     * @param to      期間終了（含む）
     * @return 予定（チーム ID の順 → start_at 昇順）
     */
    public List<ScopeScheduleView> findTeamSchedulesBetween(List<Long> teamIds, LocalDateTime from, LocalDateTime to) {
        if (teamIds == null || teamIds.isEmpty()) {
            return List.of();
        }
        return orderByScope(teamIds, scheduleRepository.findByTeamIdInAndStartAtBetween(teamIds, from, to),
                ScheduleEntity::getTeamId);
    }

    /**
     * 複数組織の予定を期間指定で返す。
     *
     * @param orgIds 組織 ID 群（この順に並べる）
     * @param from   期間開始（含む）
     * @param to     期間終了（含む）
     * @return 予定（組織 ID の順 → start_at 昇順）
     */
    public List<ScopeScheduleView> findOrganizationSchedulesBetween(
            List<Long> orgIds, LocalDateTime from, LocalDateTime to) {
        if (orgIds == null || orgIds.isEmpty()) {
            return List.of();
        }
        return orderByScope(orgIds, scheduleRepository.findByOrganizationIdInAndStartAtBetween(orgIds, from, to),
                ScheduleEntity::getOrganizationId);
    }

    private static List<ScopeScheduleView> orderByScope(List<Long> scopeIds, List<ScheduleEntity> schedules,
                                                        Function<ScheduleEntity, Long> scopeOf) {
        Map<Long, List<ScopeScheduleView>> byScope = new LinkedHashMap<>();
        for (Long id : scopeIds) {
            byScope.putIfAbsent(id, new ArrayList<>());
        }
        for (ScheduleEntity s : schedules) {
            List<ScopeScheduleView> bucket = byScope.get(scopeOf.apply(s));
            if (bucket != null) {
                bucket.add(new ScopeScheduleView(s.getId(), s.getTeamId(), s.getOrganizationId(), s.getTitle(),
                        s.getStartAt() != null ? s.getStartAt().toLocalDate() : null,
                        s.getStartAt() != null ? s.getStartAt().toLocalTime() : null,
                        s.getEndAt() != null ? s.getEndAt().toLocalDate() : null,
                        s.getEndAt() != null ? s.getEndAt().toLocalTime() : null,
                        s.getLocation(), s.getAllDay()));
            }
        }
        List<ScopeScheduleView> ordered = new ArrayList<>(schedules.size());
        byScope.values().forEach(ordered::addAll);
        return ordered;
    }
}
