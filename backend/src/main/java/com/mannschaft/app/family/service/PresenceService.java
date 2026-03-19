package com.mannschaft.app.family.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.family.EventType;
import com.mannschaft.app.family.FamilyErrorCode;
import com.mannschaft.app.family.dto.PresenceBulkResponse;
import com.mannschaft.app.family.dto.PresenceEventResponse;
import com.mannschaft.app.family.dto.PresenceGoingOutRequest;
import com.mannschaft.app.family.dto.PresenceHomeRequest;
import com.mannschaft.app.family.dto.PresenceStatsResponse;
import com.mannschaft.app.family.dto.PresenceStatusResponse;
import com.mannschaft.app.family.entity.PresenceEventEntity;
import com.mannschaft.app.family.repository.PresenceEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PresenceService {

    private static final int UNKNOWN_THRESHOLD_HOURS = 24;
    private final PresenceEventRepository presenceEventRepository;

    @Transactional
    public ApiResponse<PresenceEventResponse> sendHome(Long teamId, Long userId, PresenceHomeRequest request) {
        closeOpenGoingOut(teamId, userId);
        PresenceEventEntity event = PresenceEventEntity.builder()
                .teamId(teamId).userId(userId).eventType(EventType.HOME)
                .message(request != null ? request.getMessage() : null).build();
        return ApiResponse.of(toResponse(presenceEventRepository.save(event)));
    }

    @Transactional
    public ApiResponse<PresenceEventResponse> sendGoingOut(Long teamId, Long userId, PresenceGoingOutRequest request) {
        if (request.getExpectedReturnAt() != null && request.getExpectedReturnAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(FamilyErrorCode.FAMILY_001);
        }
        closeOpenGoingOut(teamId, userId);
        PresenceEventEntity event = PresenceEventEntity.builder()
                .teamId(teamId).userId(userId).eventType(EventType.GOING_OUT)
                .destination(request.getDestination()).expectedReturnAt(request.getExpectedReturnAt())
                .message(request.getMessage()).build();
        return ApiResponse.of(toResponse(presenceEventRepository.save(event)));
    }

    @Transactional
    public ApiResponse<PresenceBulkResponse> sendHomeBulk(Long userId) {
        List<PresenceBulkResponse.NotifiedTeam> notified = new ArrayList<>();
        List<PresenceBulkResponse.SkippedTeam> skipped = new ArrayList<>();
        return ApiResponse.of(new PresenceBulkResponse(notified, skipped));
    }

    @Transactional
    public ApiResponse<PresenceBulkResponse> sendGoingOutBulk(Long userId, PresenceGoingOutRequest request) {
        if (request.getExpectedReturnAt() != null && request.getExpectedReturnAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(FamilyErrorCode.FAMILY_001);
        }
        List<PresenceBulkResponse.NotifiedTeam> notified = new ArrayList<>();
        List<PresenceBulkResponse.SkippedTeam> skipped = new ArrayList<>();
        return ApiResponse.of(new PresenceBulkResponse(notified, skipped));
    }

    public ApiResponse<List<PresenceStatusResponse>> getStatus(Long teamId) {
        List<PresenceEventEntity> latestEvents = presenceEventRepository.findLatestByTeamId(teamId);
        LocalDateTime threshold = LocalDateTime.now().minusHours(UNKNOWN_THRESHOLD_HOURS);
        List<PresenceStatusResponse> responses = latestEvents.stream().map(event -> {
            String status = event.getCreatedAt().isBefore(threshold) ? "UNKNOWN" : event.getEventType().name();
            return new PresenceStatusResponse(
                    new PresenceEventResponse.UserSummary(event.getUserId(), "User#" + event.getUserId()),
                    status,
                    EventType.GOING_OUT.equals(event.getEventType()) ? event.getDestination() : null,
                    EventType.GOING_OUT.equals(event.getEventType()) ? event.getExpectedReturnAt() : null,
                    event.getCreatedAt());
        }).toList();
        return ApiResponse.of(responses);
    }

    public CursorPagedResponse<PresenceEventResponse> getHistory(Long teamId, Long userId, Long cursor, int limit) {
        List<PresenceEventEntity> events = presenceEventRepository.findHistory(teamId, userId, cursor, PageRequest.of(0, limit + 1));
        boolean hasNext = events.size() > limit;
        List<PresenceEventEntity> page = hasNext ? events.subList(0, limit) : events;
        List<PresenceEventResponse> responses = page.stream().map(this::toResponse).toList();
        String nextCursor = hasNext ? String.valueOf(page.get(page.size() - 1).getId()) : null;
        return CursorPagedResponse.of(responses, new CursorPagedResponse.CursorMeta(nextCursor, hasNext, limit));
    }

    public ApiResponse<PresenceStatsResponse> getStats(Long teamId, String period) {
        int days = switch (period) { case "7d" -> 7; case "90d" -> 90; default -> 30; };
        LocalDateTime after = LocalDateTime.now().minusDays(days);
        List<PresenceEventEntity> events = presenceEventRepository.findByTeamIdAndCreatedAtAfterOrderByCreatedAtDesc(teamId, after);
        int totalHome = 0, totalGoingOut = 0, overdueCount = 0;
        Map<Long, List<PresenceEventEntity>> byUser = events.stream().collect(Collectors.groupingBy(PresenceEventEntity::getUserId));
        List<PresenceStatsResponse.MemberStats> memberStats = new ArrayList<>();
        for (Map.Entry<Long, List<PresenceEventEntity>> entry : byUser.entrySet()) {
            int home = 0, goingOut = 0, overdue = 0;
            for (PresenceEventEntity e : entry.getValue()) {
                if (EventType.HOME.equals(e.getEventType())) { home++; }
                else { goingOut++; if (e.getOverdueLevel() > 0) { overdue++; } }
            }
            totalHome += home; totalGoingOut += goingOut; overdueCount += overdue;
            memberStats.add(new PresenceStatsResponse.MemberStats(entry.getKey(), home, goingOut, overdue));
        }
        return ApiResponse.of(new PresenceStatsResponse(period, events.size(), totalHome, totalGoingOut, overdueCount, memberStats));
    }

    private void closeOpenGoingOut(Long teamId, Long userId) {
        presenceEventRepository.findFirstByTeamIdAndUserIdAndEventTypeAndReturnedAtIsNullOrderByCreatedAtDesc(
                teamId, userId, EventType.GOING_OUT).ifPresent(PresenceEventEntity::markReturned);
    }

    private PresenceEventResponse toResponse(PresenceEventEntity entity) {
        return new PresenceEventResponse(entity.getId(), entity.getEventType().name(), entity.getMessage(),
                entity.getDestination(), entity.getExpectedReturnAt(),
                new PresenceEventResponse.UserSummary(entity.getUserId(), "User#" + entity.getUserId()), entity.getCreatedAt());
    }
}
