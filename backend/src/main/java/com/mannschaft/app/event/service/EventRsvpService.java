package com.mannschaft.app.event.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.dto.EventRsvpRequest;
import com.mannschaft.app.event.dto.EventRsvpResponseDto;
import com.mannschaft.app.event.dto.EventRsvpSummaryResponse;
import com.mannschaft.app.event.entity.EventAttendanceMode;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventRsvpResponseEntity;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.family.service.CareEventNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * イベントRSVPサービス。出欠確認の送信・更新・集計・一覧取得を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventRsvpService {

    private final EventRsvpResponseRepository rsvpResponseRepository;
    private final UserRepository userRepository;
    private final EventService eventService;
    private final CareEventNotificationService careEventNotificationService;

    /**
     * RSVP回答を送信する（初回）。
     * 既にレコードが存在する場合は 409 already_rsvped を返す。
     *
     * @param eventId イベントID
     * @param userId  ユーザーID
     * @param req     RSVPリクエスト
     * @return RSVP回答レスポンスDTO
     */
    @Transactional
    public EventRsvpResponseDto submitRsvp(Long eventId, Long userId, EventRsvpRequest req) {
        EventEntity event = eventService.findEventOrThrow(eventId);
        validateRsvpMode(event);

        Optional<EventRsvpResponseEntity> existing =
                rsvpResponseRepository.findByEventIdAndUserId(eventId, userId);
        if (existing.isPresent()) {
            throw new BusinessException(EventErrorCode.ALREADY_RSVPED);
        }

        EventRsvpResponseEntity entity = EventRsvpResponseEntity.builder()
                .eventId(eventId)
                .userId(userId)
                .response(req.getResponse())
                .comment(req.getComment())
                .build();
        entity.updateResponse(req.getResponse(), req.getComment());

        EventRsvpResponseEntity saved = rsvpResponseRepository.save(entity);
        log.info("RSVP送信: eventId={}, userId={}, response={}", eventId, userId, req.getResponse());

        // F03.12 ケア対象者見守り通知: ATTENDING の場合に見守り者へ通知
        if ("ATTENDING".equals(req.getResponse())) {
            careEventNotificationService.notifyRsvpConfirmed(userId, eventId);
        }

        String userName = getUserDisplayName(userId);
        return toDto(saved, userName);
    }

    /**
     * RSVP回答を更新する。
     *
     * @param eventId イベントID
     * @param userId  ユーザーID
     * @param req     RSVPリクエスト
     * @return RSVP回答レスポンスDTO
     */
    @Transactional
    public EventRsvpResponseDto updateRsvp(Long eventId, Long userId, EventRsvpRequest req) {
        EventEntity event = eventService.findEventOrThrow(eventId);
        validateRsvpMode(event);

        EventRsvpResponseEntity entity = rsvpResponseRepository
                .findByEventIdAndUserId(eventId, userId)
                .orElseThrow(() -> new BusinessException(EventErrorCode.RSVP_NOT_FOUND));

        entity.updateResponse(req.getResponse(), req.getComment());
        EventRsvpResponseEntity saved = rsvpResponseRepository.save(entity);
        log.info("RSVP更新: eventId={}, userId={}, response={}", eventId, userId, req.getResponse());

        String userName = getUserDisplayName(userId);
        return toDto(saved, userName);
    }

    /**
     * RSVP回答一覧を取得する（管理者向け）。
     *
     * @param eventId イベントID
     * @return RSVP回答レスポンスDTOのリスト
     */
    public List<EventRsvpResponseDto> getRsvpList(Long eventId) {
        eventService.findEventOrThrow(eventId);
        List<EventRsvpResponseEntity> entities = rsvpResponseRepository.findByEventId(eventId);

        // ユーザー名をまとめて取得
        List<Long> userIds = entities.stream()
                .map(EventRsvpResponseEntity::getUserId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> userNameMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, UserEntity::getDisplayName));

        return entities.stream()
                .map(e -> toDto(e, userNameMap.getOrDefault(e.getUserId(), "")))
                .collect(Collectors.toList());
    }

    /**
     * RSVP集計を取得する。
     *
     * @param eventId イベントID
     * @return RSVP集計レスポンス
     */
    public EventRsvpSummaryResponse getRsvpSummary(Long eventId) {
        eventService.findEventOrThrow(eventId);
        long attending    = rsvpResponseRepository.countByEventIdAndResponse(eventId, "ATTENDING");
        long notAttending = rsvpResponseRepository.countByEventIdAndResponse(eventId, "NOT_ATTENDING");
        long maybe        = rsvpResponseRepository.countByEventIdAndResponse(eventId, "MAYBE");
        long undecided    = rsvpResponseRepository.countByEventIdAndResponse(eventId, "UNDECIDED");
        long total        = attending + notAttending + maybe + undecided;
        return new EventRsvpSummaryResponse(attending, notAttending, maybe, undecided, total);
    }

    /**
     * イベントメンバー全員のUNDECIDEDレコードを自動生成する。
     * attendance_mode=RSVP の openRegistration 時に呼ぶ。
     *
     * @param eventId       イベントID
     * @param memberUserIds メンバーのユーザーIDリスト
     */
    @Transactional
    public void generateRsvpRecords(Long eventId, List<Long> memberUserIds) {
        eventService.findEventOrThrow(eventId);
        for (Long userId : memberUserIds) {
            boolean exists = rsvpResponseRepository
                    .findByEventIdAndUserId(eventId, userId)
                    .isPresent();
            if (!exists) {
                EventRsvpResponseEntity entity = EventRsvpResponseEntity.builder()
                        .eventId(eventId)
                        .userId(userId)
                        .build();
                rsvpResponseRepository.save(entity);
            }
        }
        log.info("RSVPレコード自動生成: eventId={}, members={}", eventId, memberUserIds.size());
    }

    // --- private helper ---

    private void validateRsvpMode(EventEntity event) {
        if (event.getAttendanceMode() != EventAttendanceMode.RSVP) {
            throw new BusinessException(EventErrorCode.RSVP_MODE_REQUIRED);
        }
    }

    private String getUserDisplayName(Long userId) {
        return userRepository.findById(userId)
                .map(UserEntity::getDisplayName)
                .orElse("");
    }

    private EventRsvpResponseDto toDto(EventRsvpResponseEntity entity, String userName) {
        return new EventRsvpResponseDto(
                entity.getId(),
                entity.getEventId(),
                entity.getUserId(),
                userName,
                entity.getResponse(),
                entity.getComment(),
                entity.getRespondedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
