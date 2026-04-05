package com.mannschaft.app.search.service;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.facility.repository.FacilityBookingRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.queue.repository.QueueTicketRepository;
import com.mannschaft.app.safetycheck.repository.SafetyCheckRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.search.dto.SearchResultResponse;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * グローバル検索サービス。9種別（schedules, events, reservations, shifts,
 * safetyChecks, queues, teams, organizations, users）を横断検索する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GlobalSearchService {

    private final ScheduleRepository scheduleRepository;
    private final EventRepository eventRepository;
    private final FacilityBookingRepository facilityBookingRepository;
    private final ShiftScheduleRepository shiftScheduleRepository;
    private final SafetyCheckRepository safetyCheckRepository;
    private final QueueTicketRepository queueTicketRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;

    private static final int SEARCH_LIMIT = 10;

    /**
     * 9種別横断検索を実行する。
     *
     * @param query  検索クエリ
     * @param userId 検索実行ユーザーID
     * @return 統合検索結果
     */
    public SearchResultResponse search(String query, Long userId) {
        long startTime = System.currentTimeMillis();
        Pageable limit = PageRequest.of(0, SEARCH_LIMIT);

        Map<String, List<Map<String, Object>>> results = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();

        // schedules
        var schedules = scheduleRepository.searchByKeyword(query, limit);
        results.put("schedules", schedules.stream()
                .map(s -> Map.<String, Object>of(
                        "id", s.getId(), "title", s.getTitle(),
                        "location", s.getLocation() != null ? s.getLocation() : ""))
                .collect(Collectors.toList()));
        counts.put("schedules", (long) schedules.size());

        // events
        var events = eventRepository.searchByKeyword(query, limit);
        results.put("events", events.stream()
                .map(e -> Map.<String, Object>of(
                        "id", e.getId(), "title", e.getSubtitle() != null ? e.getSubtitle() : "",
                        "venueName", e.getVenueName() != null ? e.getVenueName() : ""))
                .collect(Collectors.toList()));
        counts.put("events", (long) events.size());

        // reservations (facility bookings)
        var reservations = facilityBookingRepository.searchByKeyword(query, limit);
        results.put("reservations", reservations.stream()
                .map(b -> Map.<String, Object>of(
                        "id", b.getId(), "purpose", b.getPurpose() != null ? b.getPurpose() : ""))
                .collect(Collectors.toList()));
        counts.put("reservations", (long) reservations.size());

        // shifts
        var shifts = shiftScheduleRepository.searchByKeyword(query, limit);
        results.put("shifts", shifts.stream()
                .map(s -> Map.<String, Object>of(
                        "id", s.getId(), "title", s.getTitle()))
                .collect(Collectors.toList()));
        counts.put("shifts", (long) shifts.size());

        // safetyChecks
        var safetyChecks = safetyCheckRepository.searchByKeyword(query, limit);
        results.put("safetyChecks", safetyChecks.stream()
                .map(sc -> Map.<String, Object>of(
                        "id", sc.getId(), "title", sc.getTitle()))
                .collect(Collectors.toList()));
        counts.put("safetyChecks", (long) safetyChecks.size());

        // queues
        var queues = queueTicketRepository.searchByKeyword(query, limit);
        results.put("queues", queues.stream()
                .map(q -> Map.<String, Object>of(
                        "id", q.getId(),
                        "ticketNumber", q.getTicketNumber(),
                        "guestName", q.getGuestName() != null ? q.getGuestName() : ""))
                .collect(Collectors.toList()));
        counts.put("queues", (long) queues.size());

        // teams
        var teams = teamRepository.searchByKeyword(query, limit);
        results.put("teams", teams.getContent().stream()
                .map(t -> Map.<String, Object>of(
                        "id", t.getId(), "name", t.getName()))
                .collect(Collectors.toList()));
        counts.put("teams", teams.getTotalElements());

        // organizations
        var orgs = organizationRepository.searchByKeyword(query, limit);
        results.put("organizations", orgs.getContent().stream()
                .map(o -> Map.<String, Object>of(
                        "id", o.getId(), "name", o.getName()))
                .collect(Collectors.toList()));
        counts.put("organizations", orgs.getTotalElements());

        // users
        var users = userRepository.searchByKeyword(query, limit);
        results.put("users", users.stream()
                .map(u -> Map.<String, Object>of(
                        "id", u.getId(), "displayName", u.getDisplayName()))
                .collect(Collectors.toList()));
        counts.put("users", (long) users.size());

        long executionTimeMs = System.currentTimeMillis() - startTime;
        log.info("グローバル検索実行: query='{}', userId={}, executionTime={}ms", query, userId, executionTimeMs);

        return new SearchResultResponse(query, results, counts, executionTimeMs);
    }
}
