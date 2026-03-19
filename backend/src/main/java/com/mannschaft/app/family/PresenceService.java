package com.mannschaft.app.family;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.family.dto.PresenceBulkResponse;
import com.mannschaft.app.family.dto.PresenceEventResponse;
import com.mannschaft.app.family.dto.PresenceGoingOutRequest;
import com.mannschaft.app.family.dto.PresenceHomeRequest;
import com.mannschaft.app.family.dto.PresenceStatsResponse;
import com.mannschaft.app.family.dto.PresenceStatusResponse;
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

/**
 * プレゼンスサービス。帰ったよ通知・お出かけ連絡の管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PresenceService {

    private static final int UNKNOWN_THRESHOLD_HOURS = 24;

    private final PresenceEventRepository presenceEventRepository;

    /**
     * 帰ったよ通知を送信する（チーム指定）。
     *
     * @param teamId  チームID
     * @param userId  ユーザーID
     * @param request リクエスト
     * @return プレゼンスイベント
     */
    @Transactional
    public ApiResponse<PresenceEventResponse> sendHome(Long teamId, Long userId, PresenceHomeRequest request) {
        // 直前の未帰宅GOING_OUTを自動クローズ
        closeOpenGoingOut(teamId, userId);

        PresenceEventEntity event = PresenceEventEntity.builder()
                .teamId(teamId)
                .userId(userId)
                .eventType(EventType.HOME)
                .message(request != null ? request.getMessage() : null)
                .build();

        PresenceEventEntity saved = presenceEventRepository.save(event);
        return ApiResponse.of(toResponse(saved));
    }

    /**
     * お出かけ連絡を送信する（チーム指定）。
     *
     * @param teamId  チームID
     * @param userId  ユーザーID
     * @param request リクエスト
     * @return プレゼンスイベント
     */
    @Transactional
    public ApiResponse<PresenceEventResponse> sendGoingOut(Long teamId, Long userId, PresenceGoingOutRequest request) {
        if (request.getExpectedReturnAt() != null && request.getExpectedReturnAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(FamilyErrorCode.FAMILY_001);
        }

        // 直前の未帰宅GOING_OUTを自動クローズ
        closeOpenGoingOut(teamId, userId);

        PresenceEventEntity event = PresenceEventEntity.builder()
                .teamId(teamId)
                .userId(userId)
                .eventType(EventType.GOING_OUT)
                .destination(request.getDestination())
                .expectedReturnAt(request.getExpectedReturnAt())
                .message(request.getMessage())
                .build();

        PresenceEventEntity saved = presenceEventRepository.save(event);
        return ApiResponse.of(toResponse(saved));
    }

    /**
     * 帰ったよ通知を一括送信する（全所属チーム）。
     *
     * @param userId ユーザーID
     * @return 一括送信結果
     */
    @Transactional
    public ApiResponse<PresenceBulkResponse> sendHomeBulk(Long userId) {
        // TODO: ユーザーの所属チーム一覧と通知設定を取得して一括送信
        List<PresenceBulkResponse.NotifiedTeam> notified = new ArrayList<>();
        List<PresenceBulkResponse.SkippedTeam> skipped = new ArrayList<>();
        return ApiResponse.of(new PresenceBulkResponse(notified, skipped));
    }

    /**
     * お出かけ連絡を一括送信する（全所属チーム）。
     *
     * @param userId  ユーザーID
     * @param request リクエスト
     * @return 一括送信結果
     */
    @Transactional
    public ApiResponse<PresenceBulkResponse> sendGoingOutBulk(Long userId, PresenceGoingOutRequest request) {
        if (request.getExpectedReturnAt() != null && request.getExpectedReturnAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(FamilyErrorCode.FAMILY_001);
        }
        // TODO: ユーザーの所属チーム一覧と通知設定を取得して一括送信
        List<PresenceBulkResponse.NotifiedTeam> notified = new ArrayList<>();
        List<PresenceBulkResponse.SkippedTeam> skipped = new ArrayList<>();
        return ApiResponse.of(new PresenceBulkResponse(notified, skipped));
    }

    /**
     * チームメンバーの最新プレゼンスステータスを取得する。
     *
     * @param teamId チームID
     * @return ステータス一覧
     */
    public ApiResponse<List<PresenceStatusResponse>> getStatus(Long teamId) {
        List<PresenceEventEntity> latestEvents = presenceEventRepository.findLatestByTeamId(teamId);
        LocalDateTime threshold = LocalDateTime.now().minusHours(UNKNOWN_THRESHOLD_HOURS);

        List<PresenceStatusResponse> responses = latestEvents.stream()
                .map(event -> {
                    String status;
                    if (event.getCreatedAt().isBefore(threshold)) {
                        status = "UNKNOWN";
                    } else {
                        status = event.getEventType().name();
                    }
                    return new PresenceStatusResponse(
                            new PresenceEventResponse.UserSummary(event.getUserId(), "User#" + event.getUserId()),
                            status,
                            EventType.GOING_OUT.equals(event.getEventType()) ? event.getDestination() : null,
                            EventType.GOING_OUT.equals(event.getEventType()) ? event.getExpectedReturnAt() : null,
                            event.getCreatedAt()
                    );
                })
                .toList();

        return ApiResponse.of(responses);
    }

    /**
     * プレゼンスイベント履歴を取得する（カーソルページネーション）。
     *
     * @param teamId チームID
     * @param userId ユーザーIDフィルタ
     * @param cursor カーソル
     * @param limit  取得件数
     * @return 履歴
     */
    public CursorPagedResponse<PresenceEventResponse> getHistory(Long teamId, Long userId, Long cursor, int limit) {
        List<PresenceEventEntity> events = presenceEventRepository.findHistory(
                teamId, userId, cursor, PageRequest.of(0, limit + 1));

        boolean hasNext = events.size() > limit;
        List<PresenceEventEntity> page = hasNext ? events.subList(0, limit) : events;

        List<PresenceEventResponse> responses = page.stream().map(this::toResponse).toList();
        String nextCursor = hasNext ? String.valueOf(page.get(page.size() - 1).getId()) : null;

        return CursorPagedResponse.of(responses,
                new CursorPagedResponse.CursorMeta(nextCursor, hasNext, limit));
    }

    /**
     * プレゼンス統計を取得する。
     *
     * @param teamId チームID
     * @param period 期間（7d / 30d / 90d）
     * @return 統計情報
     */
    public ApiResponse<PresenceStatsResponse> getStats(Long teamId, String period) {
        int days = switch (period) {
            case "7d" -> 7;
            case "90d" -> 90;
            default -> 30;
        };

        LocalDateTime after = LocalDateTime.now().minusDays(days);
        List<PresenceEventEntity> events = presenceEventRepository
                .findByTeamIdAndCreatedAtAfterOrderByCreatedAtDesc(teamId, after);

        int totalHome = 0;
        int totalGoingOut = 0;
        int overdueCount = 0;
        Map<Long, List<PresenceEventEntity>> byUser = events.stream()
                .collect(Collectors.groupingBy(PresenceEventEntity::getUserId));

        List<PresenceStatsResponse.MemberStats> memberStats = new ArrayList<>();
        for (Map.Entry<Long, List<PresenceEventEntity>> entry : byUser.entrySet()) {
            int home = 0;
            int goingOut = 0;
            int overdue = 0;
            for (PresenceEventEntity e : entry.getValue()) {
                if (EventType.HOME.equals(e.getEventType())) {
                    home++;
                } else {
                    goingOut++;
                    if (e.getOverdueLevel() > 0) {
                        overdue++;
                    }
                }
            }
            totalHome += home;
            totalGoingOut += goingOut;
            overdueCount += overdue;
            memberStats.add(new PresenceStatsResponse.MemberStats(entry.getKey(), home, goingOut, overdue));
        }

        return ApiResponse.of(new PresenceStatsResponse(
                period, events.size(), totalHome, totalGoingOut, overdueCount, memberStats));
    }

    /**
     * 直前の未帰宅GOING_OUTイベントをクローズする。
     */
    private void closeOpenGoingOut(Long teamId, Long userId) {
        presenceEventRepository.findFirstByTeamIdAndUserIdAndEventTypeAndReturnedAtIsNullOrderByCreatedAtDesc(
                        teamId, userId, EventType.GOING_OUT)
                .ifPresent(PresenceEventEntity::markReturned);
    }

    /**
     * エンティティをレスポンスDTOに変換する。
     */
    private PresenceEventResponse toResponse(PresenceEventEntity entity) {
        return new PresenceEventResponse(
                entity.getId(),
                entity.getEventType().name(),
                entity.getMessage(),
                entity.getDestination(),
                entity.getExpectedReturnAt(),
                new PresenceEventResponse.UserSummary(entity.getUserId(), "User#" + entity.getUserId()),
                entity.getCreatedAt()
        );
    }
}
