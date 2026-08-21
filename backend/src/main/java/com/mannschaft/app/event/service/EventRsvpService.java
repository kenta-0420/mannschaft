package com.mannschaft.app.event.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
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
import com.mannschaft.app.event.event.EventAdvanceNoticeNotificationEvent;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.family.EventCareNotificationType;
import com.mannschaft.app.family.service.CareEventNotificationService;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;

import java.util.List;
import java.util.Locale;
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

    /** Issue #2715 CMP-055 ロットC-2: 受信者 locale 別に通知本文を組み立てるための依存。 */
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;
    /** Issue #2834 / CMP-056: 通知は業務コミット後に発火する（業務サービスから Runner を直接呼ばない）。 */
    private final ApplicationEventPublisher eventPublisher;

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

        String displayName = getUserDisplayName(targetUserId);

        // Issue #2834 / CMP-056: 通知は AFTER_COMMIT + REQUIRES_NEW + Async のリスナーへ委譲する。
        // 本メソッドは通知配送要求を組み立てて publish するだけに留め、createNotification を直接
        // 呼ばない（業務トランザクション内での DB 例外が本処理（rsvp の save）を巻き戻さないため）。
        EventEntity event = eventService.findEventOrThrow(eventId);
        publishAdvanceNoticeNotifications(event, teamId, operatorUserId, targetUserId, eventId,
                EventCareNotificationType.EVENT_LATE_ARRIVAL_NOTICE,
                "notification.event.rsvp.lateNotice.title", "遅刻連絡",
                "notification.event.rsvp.lateNotice.body",
                new Object[]{displayName, req.getExpectedArrivalMinutesLate()},
                displayName + " が " + req.getExpectedArrivalMinutesLate() + "分遅刻予定です");

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

        String displayName = getUserDisplayName(targetUserId);

        // Issue #2834 / CMP-056: 通知は AFTER_COMMIT + REQUIRES_NEW + Async のリスナーへ委譲する。
        EventEntity event = eventService.findEventOrThrow(eventId);
        publishAdvanceNoticeNotifications(event, teamId, operatorUserId, targetUserId, eventId,
                EventCareNotificationType.EVENT_ABSENCE_NOTICE,
                "notification.event.rsvp.absenceNotice.title", "欠席連絡",
                "notification.event.rsvp.absenceNotice.body",
                new Object[]{displayName, req.getAbsenceReason()},
                displayName + " が事前欠席連絡を送りました（理由: " + req.getAbsenceReason() + "）");

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
     * 事前遅刻・欠席連絡の通知配送要求を組み立てて publish する（Issue #2834 / CMP-056）。
     *
     * <p>主催者へは常に単発通知（{@link UserLocaleCache#getLocale}）、操作者がケア対象者の
     * アクティブな見守り者である場合は他の見守り者へも通知する（{@link UserLocaleCache#getLocales}
     * によるバルク解決で N+1 を防止する）。件名・本文は受信者ごとの locale で
     * {@link MessageSource} から組み立てる。組み立てた要求は 1 つの
     * {@link EventAdvanceNoticeNotificationEvent} にまとめて publish するだけに留め、
     * {@code createNotification} は直接呼ばない（実生成は {@code AFTER_COMMIT} リスナーへ委譲）。</p>
     *
     * @param event          イベントエンティティ（主催者取得用）
     * @param teamId         チームID（スコープID・actionUrl 構築用）
     * @param operatorUserId 操作者ユーザーID（本人または見守り者の userId）
     * @param targetUserId   ケア対象者（＝連絡対象）のユーザーID
     * @param eventId        イベントID
     * @param type           通知種別
     * @param titleKey       件名のメッセージキー
     * @param defaultTitle   件名のデフォルト文言（ja）
     * @param bodyKey        本文のメッセージキー
     * @param bodyArgs       本文のプレースホルダ引数
     * @param defaultBody    本文のデフォルト文言（ja、フォールバック時にも使用）
     */
    private void publishAdvanceNoticeNotifications(EventEntity event, Long teamId, Long operatorUserId,
                                                    Long targetUserId, Long eventId,
                                                    EventCareNotificationType type,
                                                    String titleKey, String defaultTitle,
                                                    String bodyKey, Object[] bodyArgs, String defaultBody) {
        List<NotificationDeliveryRequest> requests = new ArrayList<>();

        // 主催者へ通知
        Long organizerUserId = event.getCreatedBy();
        if (organizerUserId != null) {
            Locale organizerLocale = Locale.forLanguageTag(userLocaleCache.getLocale(organizerUserId));
            String title = messageSource.getMessage(titleKey, null, defaultTitle, organizerLocale);
            String body = messageSource.getMessage(bodyKey, bodyArgs, defaultBody, organizerLocale);
            requests.add(new NotificationDeliveryRequest(
                    organizerUserId,
                    type.name(),
                    NotificationPriority.NORMAL,
                    title, body,
                    "EVENT", event.getId(),
                    NotificationScopeType.TEAM, teamId,
                    "/teams/" + teamId + "/events/" + event.getId(), operatorUserId));
        }

        // 操作者がケア対象者の見守り者かどうかを確認し、そうであれば他の見守り者にも通知する（代理申告の共有）
        List<Long> allWatcherIds = careLinkService.getActiveWatchers(targetUserId, "RSVP");
        if (allWatcherIds.contains(operatorUserId)) {
            // 見守り者の locale をバルク解決（N+1 防止・AC-3）
            Map<Long, String> watcherLocales = userLocaleCache.getLocales(allWatcherIds);

            // 操作者自身を除いた他の見守り者へ通知を送る
            for (Long watcherId : allWatcherIds) {
                if (watcherId.equals(operatorUserId)) continue;

                Locale watcherLocale = Locale.forLanguageTag(watcherLocales.getOrDefault(watcherId, "ja"));
                String title = messageSource.getMessage(titleKey, null, defaultTitle, watcherLocale);
                String body = messageSource.getMessage(bodyKey, bodyArgs, defaultBody, watcherLocale);

                requests.add(new NotificationDeliveryRequest(
                        watcherId,
                        type.name(),
                        NotificationPriority.NORMAL,
                        title, body,
                        "EVENT", eventId,
                        NotificationScopeType.PERSONAL, watcherId,
                        "/teams/" + teamId + "/events/" + eventId, operatorUserId));
            }
        }

        if (!requests.isEmpty()) {
            eventPublisher.publishEvent(new EventAdvanceNoticeNotificationEvent(requests));
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
