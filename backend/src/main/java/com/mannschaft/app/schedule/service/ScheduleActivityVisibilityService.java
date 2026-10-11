package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.dto.ScheduleActivitySource;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.visibility.ScheduleVisibilityResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 活動のsource ACLを予定の既存F00でbulk判定する。checkerへの逆依存を作らない。 */
@Service
@RequiredArgsConstructor
public class ScheduleActivityVisibilityService {
    private final ScheduleRepository schedules;
    private final ScheduleVisibilityResolver visibility;

    public Map<Long, ScheduleActivitySource> visibleSources(Collection<Long> ids, Long viewerUserId) {
        if (ids.isEmpty()) return Map.of();
        var visibleIds = visibility.filterAccessible(ids, viewerUserId);
        if (visibleIds.isEmpty()) return Map.of();
        return schedules.findAllById(visibleIds).stream().map(s -> {
            String type = s.isTeamScope() ? "TEAM" : s.isOrganizationScope() ? "ORGANIZATION" : "PERSONAL";
            Long scopeId = s.isTeamScope() ? s.getTeamId() : s.isOrganizationScope() ? s.getOrganizationId() : s.getUserId();
            return new ScheduleActivitySource(s.getId(), type, scopeId, s.getTitle(), offset(s.getStartAt()),
                    offset(s.getEndAt()), s.getAllDay(), s.getStatus().name(), offset(s.getUpdatedAt()), List.of());
        }).collect(Collectors.toMap(ScheduleActivitySource::id, Function.identity()));
    }

    private java.time.OffsetDateTime offset(java.time.LocalDateTime value) {
        return value == null ? null : value.atZone(
                com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE).toOffsetDateTime();
    }
}
