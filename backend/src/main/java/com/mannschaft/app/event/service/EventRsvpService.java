package com.mannschaft.app.event.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.dto.AbsenceNoticeRequest;
import com.mannschaft.app.event.dto.AdvanceNoticeResponse;
import com.mannschaft.app.event.dto.EventRsvpRequest;
import com.mannschaft.app.event.dto.EventRsvpResponseDto;
import com.mannschaft.app.event.dto.EventRsvpSummaryResponse;
import com.mannschaft.app.event.dto.LateNoticeRequest;
import com.mannschaft.app.event.entity.EventAttendanceMode;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventRsvpResponseEntity;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.family.EventCareNotificationType;
import com.mannschaft.app.family.service.CareEventNotificationService;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * イベントRSVPサービス。出欠確認の送信・更新・集計・一覧取得を担当する。
 *
 * <p>F03.12 Phase8 §15 で事前遅刻連絡・事前欠席連絡・事前通知一覧のメソッドを追加。</p>
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
    private final CareLinkService careLinkService;
    private final NotificationService notificationService;
    private final NotificationDispatchService notificationDispatchService;

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

    // =========================================================
    // F03.12 Phase8 §15 事前遅刻・欠席連絡
    // =========================================================

    /**
     * 事前遅刻連絡を送信する。F03.12 §15。
     *
     * <p>本人または見守り者が代理で「N分遅刻予定」を申告する。
     * RSVPレコードに遅刻分数を記録し、主催者へプッシュ通知を送信する。
     * 操作者が見守り者である場合は、同じケア対象者の他の見守り者にも通知する。</p>
     *
     * @param eventId          イベントID
     * @param teamId           チームID（通知スコープ設定に使用）
     * @param operatorUserId   操作者ユーザーID（本人または見守り者）
     * @param req              遅刻連絡リクエスト
     * @return 事前通知レスポンス
     */
    @Transactional
    public AdvanceNoticeResponse submitLateNotice(Long eventId, Long teamId,
                                                   Long operatorUserId, LateNoticeRequest req) {
        eventService.findEventOrThrow(eventId);

        Long targetUserId = req.getUserId();
        EventRsvpResponseEntity rsvp = rsvpResponseRepository
                .findByEventIdAndUserId(eventId, targetUserId)
                .orElseThrow(() -> new BusinessException(EventErrorCode.RSVP_NOT_FOUND));

        // 遅刻分数をエンティティに記録する
        rsvp.recordLateNotice(req.getExpectedArrivalMinutesLate());
        rsvpResponseRepository.save(rsvp);

        // 主催者へ通知を送信する
        EventEntity event = eventService.findEventOrThrow(eventId);
        String displayName = getUserDisplayName(targetUserId);
        String body = displayName + " が " + req.getExpectedArrivalMinutesLate() + "分遅刻予定です";
        notifyOrganizer(event, teamId, EventCareNotificationType.EVENT_LATE_ARRIVAL_NOTICE,
                "遅刻連絡", body, operatorUserId);

        // 見守り者が代理送信した場合、同じケア対象者の他の見守り者にも通知する
        notifyOtherWatchers(targetUserId, operatorUserId, eventId, teamId,
                EventCareNotificationType.EVENT_LATE_ARRIVAL_NOTICE,
                "遅刻連絡", body);

        log.info("遅刻連絡送信: eventId={}, targetUserId={}, minutes={}, operatorUserId={}",
                eventId, targetUserId, req.getExpectedArrivalMinutesLate(), operatorUserId);

        return new AdvanceNoticeResponse(
                targetUserId,
                displayName,
                "LATE",
                req.getExpectedArrivalMinutesLate(),
                null,
                req.getComment(),
                rsvp.getCreatedAt()
        );
    }

    /**
     * 事前欠席連絡を送信する。F03.12 §15。
     *
     * <p>本人または見守り者が代理で「欠席」を申告する。
     * RSVPレコードに欠席理由を記録し、主催者へプッシュ通知を送信する。
     * 操作者が見守り者である場合は、同じケア対象者の他の見守り者にも通知する。</p>
     *
     * @param eventId          イベントID
     * @param teamId           チームID（通知スコープ設定に使用）
     * @param operatorUserId   操作者ユーザーID（本人または見守り者）
     * @param req              欠席連絡リクエスト
     * @return 事前通知レスポンス
     */
    @Transactional
    public AdvanceNoticeResponse submitAbsenceNotice(Long eventId, Long teamId,
                                                      Long operatorUserId, AbsenceNoticeRequest req) {
        eventService.findEventOrThrow(eventId);

        Long targetUserId = req.getUserId();
        EventRsvpResponseEntity rsvp = rsvpResponseRepository
                .findByEventIdAndUserId(eventId, targetUserId)
                .orElseThrow(() -> new BusinessException(EventErrorCode.RSVP_NOT_FOUND));

        // 欠席理由をエンティティに記録する
        rsvp.recordAbsenceNotice(req.getAbsenceReason());
        rsvpResponseRepository.save(rsvp);

        // 主催者へ通知を送信する
        EventEntity event = eventService.findEventOrThrow(eventId);
        String displayName = getUserDisplayName(targetUserId);
        String body = displayName + " が事前欠席連絡を送りました（理由: " + req.getAbsenceReason() + "）";
        notifyOrganizer(event, teamId, EventCareNotificationType.EVENT_ABSENCE_NOTICE,
                "欠席連絡", body, operatorUserId);

        // 見守り者が代理送信した場合、同じケア対象者の他の見守り者にも通知する
        notifyOtherWatchers(targetUserId, operatorUserId, eventId, teamId,
                EventCareNotificationType.EVENT_ABSENCE_NOTICE,
                "欠席連絡", body);

        log.info("欠席連絡送信: eventId={}, targetUserId={}, reason={}, operatorUserId={}",
                eventId, targetUserId, req.getAbsenceReason(), operatorUserId);

        return new AdvanceNoticeResponse(
                targetUserId,
                displayName,
                "ABSENCE",
                null,
                req.getAbsenceReason(),
                req.getComment(),
                rsvp.getCreatedAt()
        );
    }

    /**
     * イベントの事前通知一覧（遅刻・欠席）を取得する。F03.12 §15。
     *
     * <p>expectedArrivalMinutesLate が NULL でない、または advanceAbsenceReason が NULL でない
     * RSVP レコードを返す。N+1 防止のためユーザー情報は一括取得する。</p>
     *
     * @param eventId イベントID
     * @param teamId  チームID（権限チェック用、現状は未使用・将来の拡張のために保持）
     * @return 事前通知レスポンスのリスト
     */
    public List<AdvanceNoticeResponse> getAdvanceNotices(Long eventId, Long teamId) {
        eventService.findEventOrThrow(eventId);

        // 遅刻連絡または欠席連絡があるレコードを一括取得する
        List<EventRsvpResponseEntity> entities =
                rsvpResponseRepository
                        .findByEventIdAndExpectedArrivalMinutesLateIsNotNullOrEventIdAndAdvanceAbsenceReasonIsNotNull(
                                eventId, eventId);

        // N+1 防止: ユーザー名をまとめて取得する
        List<Long> userIds = entities.stream()
                .map(EventRsvpResponseEntity::getUserId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> userNameMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, UserEntity::getDisplayName));

        return entities.stream()
                .map(e -> toAdvanceNoticeResponse(e, userNameMap.getOrDefault(e.getUserId(), "")))
                .collect(Collectors.toList());
    }

    // =========================================================
    // プライベートヘルパー
    // =========================================================

    /**
     * イベントの主催者（createdBy）へ通知を送信する。
     *
     * @param event      イベントエンティティ
     * @param teamId     チームID（スコープID）
     * @param type       通知種別
     * @param title      通知タイトル
     * @param body       通知本文
     * @param actorId    操作者ID
     */
    private void notifyOrganizer(EventEntity event, Long teamId,
                                  EventCareNotificationType type,
                                  String title, String body, Long actorId) {
        Long organizerUserId = event.getCreatedBy();
        if (organizerUserId == null) return;

        NotificationEntity notification = notificationService.createNotification(
                organizerUserId,
                type.name(),
                NotificationPriority.NORMAL,
                title, body,
                "EVENT", event.getId(),
                NotificationScopeType.TEAM, teamId,
                "/events/" + event.getId(), actorId);
        notificationDispatchService.dispatch(notification);
    }

    /**
     * 操作者が見守り者である場合に、同じケア対象者の他の見守り者へも通知を送信する。
     *
     * <p>操作者（operatorUserId）がケア対象者（targetUserId）のアクティブな見守り者である場合、
     * 他の見守り者にも同じ通知を送る（代理申告の共有）。</p>
     *
     * @param targetUserId   ケア対象者のユーザーID
     * @param operatorUserId 操作者ユーザーID
     * @param eventId        イベントID
     * @param teamId         チームID（スコープID）
     * @param type           通知種別
     * @param title          通知タイトル
     * @param body           通知本文
     */
    private void notifyOtherWatchers(Long targetUserId, Long operatorUserId,
                                      Long eventId, Long teamId,
                                      EventCareNotificationType type,
                                      String title, String body) {
        // 操作者がケア対象者の見守り者かどうかを確認する
        List<Long> allWatcherIds = careLinkService.getActiveWatchers(targetUserId, "RSVP");
        if (!allWatcherIds.contains(operatorUserId)) return;

        // 操作者自身を除いた他の見守り者へ通知を送る
        for (Long watcherId : allWatcherIds) {
            if (watcherId.equals(operatorUserId)) continue;

            NotificationEntity notification = notificationService.createNotification(
                    watcherId,
                    type.name(),
                    NotificationPriority.NORMAL,
                    title, body,
                    "EVENT", eventId,
                    NotificationScopeType.PERSONAL, watcherId,
                    "/events/" + eventId, operatorUserId);
            notificationDispatchService.dispatch(notification);
        }
    }

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

    /**
     * RSVP エンティティから AdvanceNoticeResponse に変換する。
     * noticeType は expectedArrivalMinutesLate が非 null なら LATE、そうでなければ ABSENCE とする。
     *
     * @param entity    RSVP 回答エンティティ
     * @param userName  表示名
     * @return 事前通知レスポンスDTO
     */
    private AdvanceNoticeResponse toAdvanceNoticeResponse(EventRsvpResponseEntity entity, String userName) {
        boolean isLate = entity.getExpectedArrivalMinutesLate() != null;
        return new AdvanceNoticeResponse(
                entity.getUserId(),
                userName,
                isLate ? "LATE" : "ABSENCE",
                entity.getExpectedArrivalMinutesLate(),
                entity.getAdvanceAbsenceReason(),
                entity.getComment(),
                entity.getCreatedAt()
        );
    }
}
