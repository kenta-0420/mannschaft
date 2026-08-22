package com.mannschaft.app.common.timezone;

import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * チームの IANA タイムゾーンを解決し、業務ローカル壁時計を Instant に変換する。
 *
 * <p>DST gap は存在しない壁時計なので拒否する。overlap は再実行時にも結果が変わらないよう、
 * JDK が返す最初の（通常は earlier）オフセットを明示的に採用する。</p>
 */
@Component
public class TeamTimezoneResolver {

    public static final String DEFAULT_TIMEZONE = "Asia/Tokyo";

    private final TeamRepository teamRepository;

    public TeamTimezoneResolver(TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    public ZoneId resolveZone(Long teamId) {
        if (teamId == null) {
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
        return teamRepository.findById(teamId)
                .map(TeamEntity::getTimezone)
                .map(TeamTimezoneResolver::zoneOrDefault)
                .orElseGet(() -> ZoneId.of(DEFAULT_TIMEZONE));
    }

    /** 複数チームを一括取得し、ループ内の N+1 を避ける。 */
    public Map<Long, ZoneId> resolveZones(Collection<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, ZoneId> zones = new HashMap<>();
        for (TeamEntity team : teamRepository.findAllById(teamIds)) {
            zones.put(team.getId(), zoneOrDefault(team.getTimezone()));
        }
        return zones;
    }

    /** 壁時計を Instant へ変換する。gap は DateTimeException とする。 */
    public Instant toInstant(LocalDate date, LocalTime time, ZoneId zone) {
        if (date == null || time == null || zone == null) {
            throw new IllegalArgumentException("date, time and zone are required");
        }
        LocalDateTime wallClock = LocalDateTime.of(date, time);
        ZoneRules rules = zone.getRules();
        var offsets = rules.getValidOffsets(wallClock);
        if (offsets.isEmpty()) {
            ZoneOffsetTransition gap = rules.getTransition(wallClock);
            throw new DateTimeException("DST gap is not a valid wall clock: " + wallClock + " in " + zone
                    + " (transition=" + gap + ")");
        }
        // overlap policy: earlier offset, deterministic across retries.
        ZoneOffset selected = offsets.get(0);
        return ZonedDateTime.ofLocal(wallClock, zone, selected).toInstant();
    }

    public Instant toInstant(Long teamId, LocalDate date, LocalTime time) {
        return toInstant(date, time, resolveZone(teamId));
    }

    private static ZoneId zoneOrDefault(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException ignored) {
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
    }
}
