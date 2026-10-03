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
import com.mannschaft.app.event.event.EventAdvanceNoticeNotificationEvent;
import com.mannschaft.app.event.event.EventCareNotificationTriggerEvent;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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

    /** Issue #2834 / CMP-056: 通知は業務コミット後に発火する（業務サービスから Runner を直接呼ばない）。
     * 見守り者解決（{@code CareLinkService}）・ロケール解決・件名/本文組み立ては
     * {@code EventAdvanceNoticeNotificationListener}（AFTER_COMMIT）側の責務（Codex検分[P2]是正）。 */
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

        // F03.12 ケア対象者見守り通知: ATTENDING の場合に見守り者へ通知（Issue #2990 L5）。
        // 業務TX内では publish だけに留め、実配送は EventCareNotificationTriggerListener が
        // AFTER_COMMIT で行う。通知失敗で RSVP 回答そのものが巻き戻らないようにする。
        if ("ATTENDING".equals(req.getResponse())) {
            eventPublisher.publishEvent(new EventCareNotificationTriggerEvent(
                    eventId,
                    EventCareNotificationTriggerEvent.Kind.RSVP_CONFIRMED,
                    List.of(userId)));
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
        // 本メソッドは ID・数値のみを publish するだけに留め、createNotification は直接呼ばず、
        // 文面組み立て（DB読み取り・MessageFormat）も業務トランザクションの内側では行わない
        // （Codex検分[P2]是正: 組み立てもリスナー側=AFTER_COMMIT後に完全に移した）。
        publishAdvanceNoticeNotification(eventId, teamId, operatorUserId, targetUserId,
                EventAdvanceNoticeNotificationEvent.Kind.LATE,
                req.getExpectedArrivalMinutesLate(), null);

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
        publishAdvanceNoticeNotification(eventId, teamId, operatorUserId, targetUserId,
                EventAdvanceNoticeNotificationEvent.Kind.ABSENCE,
                null, req.getAbsenceReason());

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
     * 事前遅刻・欠席連絡の通知イベントを publish する（Issue #2834 / CMP-056）。
     *
     * <p><b>Codex 独立検分 [P2]（2026-08-21）是正</b>: 以前は主催者・見守り者の解決やロケール解決・
     * 件名/本文組み立てまで本メソッド（＝業務トランザクションの内側）で行い、組み立て済みの
     * {@link NotificationDeliveryRequest} 一覧をイベントに積んでいた。組み立て中の DB 例外や
     * {@code MessageFormat} 例外が {@code rsvp} の save を巻き戻す退行を生んでいたため、
     * 現在は ID と数値のみを {@link EventAdvanceNoticeNotificationEvent} に積んで publish するだけに
     * 留め、組み立ては {@code EventAdvanceNoticeNotificationListener}（AFTER_COMMIT）へ完全に移した。</p>
     *
     * @param eventId                    イベントID
     * @param teamId                     チームID
     * @param operatorUserId             操作者ユーザーID（本人または見守り者の userId）
     * @param targetUserId               ケア対象者（＝連絡対象）のユーザーID
     * @param kind                       事前連絡種別（遅刻／欠席）
     * @param expectedArrivalMinutesLate 遅刻予定分数（欠席時は {@code null}）
     * @param absenceReason              欠席理由（遅刻時は {@code null}）
     */
    private void publishAdvanceNoticeNotification(Long eventId, Long teamId, Long operatorUserId,
                                                   Long targetUserId,
                                                   EventAdvanceNoticeNotificationEvent.Kind kind,
                                                   Integer expectedArrivalMinutesLate, String absenceReason) {
        eventPublisher.publishEvent(new EventAdvanceNoticeNotificationEvent(
                eventId, teamId, operatorUserId, targetUserId, kind,
                expectedArrivalMinutesLate, absenceReason));
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
