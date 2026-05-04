package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.EventMapper;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.EventStatus;
import com.mannschaft.app.event.dto.CreateEventRequest;
import com.mannschaft.app.event.dto.EventDetailResponse;
import com.mannschaft.app.event.dto.EventResponse;
import com.mannschaft.app.event.dto.EventRsvpSummaryResponse;
import com.mannschaft.app.event.dto.EventStatsResponse;
import com.mannschaft.app.event.dto.UpdateEventRequest;
import com.mannschaft.app.event.entity.EventAttendanceMode;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRegistrationRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.event.RegistrationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * イベントサービス。イベントのCRUD・ステータス遷移・統計を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository eventRepository;
    private final EventRegistrationRepository registrationRepository;
    private final EventCheckinRepository checkinRepository;
    private final EventRsvpResponseRepository rsvpResponseRepository;
    private final EventMapper eventMapper;

    /**
     * F00 Phase B 試験的置換 — 共通可視性ファサード。
     *
     * <p>設計書 §12.6.1 のリスク評価で「1 メソッドのみの試験的置換」と定められており、
     * 既存の {@code getEvent / listEvents / publish ...} 等は本フィールドを参照しない。
     * 切替対象は {@link #canView(Long, Long)} のみ。</p>
     */
    private final ContentVisibilityChecker contentVisibilityChecker;

    /**
     * スコープ別イベント一覧をページング取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータスフィルタ（null の場合は全件）
     * @param pageable  ページング情報
     * @return イベントレスポンスのページ
     */
    public Page<EventResponse> listEvents(EventScopeType scopeType, Long scopeId, String status, Pageable pageable) {
        Page<EventEntity> page;
        if (status != null) {
            EventStatus eventStatus = EventStatus.valueOf(status);
            page = eventRepository.findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    scopeType, scopeId, eventStatus, pageable);
        } else {
            page = eventRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    scopeType, scopeId, pageable);
        }
        return page.map(eventMapper::toEventResponse);
    }

    /**
     * イベント詳細を取得する。
     *
     * @param eventId イベントID
     * @return イベント詳細レスポンス
     */
    public EventDetailResponse getEvent(Long eventId) {
        EventEntity entity = findEventOrThrow(eventId);
        return toDetailResponseWithRsvp(entity);
    }

    /**
     * スラグでイベント詳細を取得する（公開ページ用）。
     *
     * @param slug スラグ
     * @return イベント詳細レスポンス
     */
    public EventDetailResponse getEventBySlug(String slug) {
        EventEntity entity = eventRepository.findBySlug(slug)
                .orElseThrow(() -> new BusinessException(EventErrorCode.EVENT_NOT_FOUND));
        return toDetailResponseWithRsvp(entity);
    }

    /**
     * イベントを作成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    作成者ユーザーID
     * @param request   作成リクエスト
     * @return 作成されたイベント詳細レスポンス
     */
    @Transactional
    public EventDetailResponse createEvent(EventScopeType scopeType, Long scopeId, Long userId,
                                           CreateEventRequest request) {
        if (eventRepository.existsBySlug(request.getSlug())) {
            throw new BusinessException(EventErrorCode.SLUG_ALREADY_EXISTS);
        }

        EventEntity entity = EventEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .scheduleId(request.getScheduleId())
                .slug(request.getSlug())
                .subtitle(request.getSubtitle())
                .summary(request.getSummary())
                .coverImageKey(request.getCoverImageKey())
                .venueName(request.getVenueName())
                .venueAddress(request.getVenueAddress())
                .venueLatitude(request.getVenueLatitude())
                .venueLongitude(request.getVenueLongitude())
                .venueAccessInfo(request.getVenueAccessInfo())
                .visibility(request.getVisibility() != null
                        ? EventVisibility.valueOf(request.getVisibility())
                        : EventVisibility.MEMBERS_ONLY)
                .registrationStartsAt(request.getRegistrationStartsAt())
                .registrationEndsAt(request.getRegistrationEndsAt())
                .maxCapacity(request.getMaxCapacity())
                .isApprovalRequired(request.getIsApprovalRequired() != null
                        ? request.getIsApprovalRequired() : false)
                .attendanceMode(request.getAttendanceMode() != null
                        ? request.getAttendanceMode() : EventAttendanceMode.REGISTRATION)
                .preSurveyId(request.getPreSurveyId())
                .ogpTitle(request.getOgpTitle())
                .ogpDescription(request.getOgpDescription())
                .ogpImageKey(request.getOgpImageKey())
                .createdBy(userId)
                .build();

        EventEntity saved = eventRepository.save(entity);
        log.info("イベント作成: scopeType={}, scopeId={}, eventId={}", scopeType, scopeId, saved.getId());
        return toDetailResponseWithRsvp(saved);
    }

    /**
     * イベントを更新する。
     *
     * @param eventId イベントID
     * @param request 更新リクエスト
     * @return 更新されたイベント詳細レスポンス
     */
    @Transactional
    public EventDetailResponse updateEvent(Long eventId, UpdateEventRequest request) {
        EventEntity entity = findEventOrThrow(eventId);

        if (request.getSlug() != null && !request.getSlug().equals(entity.getSlug())) {
            if (eventRepository.existsBySlug(request.getSlug())) {
                throw new BusinessException(EventErrorCode.SLUG_ALREADY_EXISTS);
            }
        }

        EventEntity updated = entity.toBuilder()
                .slug(request.getSlug() != null ? request.getSlug() : entity.getSlug())
                .subtitle(request.getSubtitle() != null ? request.getSubtitle() : entity.getSubtitle())
                .summary(request.getSummary() != null ? request.getSummary() : entity.getSummary())
                .coverImageKey(request.getCoverImageKey() != null ? request.getCoverImageKey() : entity.getCoverImageKey())
                .venueName(request.getVenueName() != null ? request.getVenueName() : entity.getVenueName())
                .venueAddress(request.getVenueAddress() != null ? request.getVenueAddress() : entity.getVenueAddress())
                .venueLatitude(request.getVenueLatitude() != null ? request.getVenueLatitude() : entity.getVenueLatitude())
                .venueLongitude(request.getVenueLongitude() != null ? request.getVenueLongitude() : entity.getVenueLongitude())
                .venueAccessInfo(request.getVenueAccessInfo() != null ? request.getVenueAccessInfo() : entity.getVenueAccessInfo())
                .visibility(request.getVisibility() != null
                        ? EventVisibility.valueOf(request.getVisibility())
                        : entity.getVisibility())
                .registrationStartsAt(request.getRegistrationStartsAt() != null
                        ? request.getRegistrationStartsAt() : entity.getRegistrationStartsAt())
                .registrationEndsAt(request.getRegistrationEndsAt() != null
                        ? request.getRegistrationEndsAt() : entity.getRegistrationEndsAt())
                .maxCapacity(request.getMaxCapacity() != null ? request.getMaxCapacity() : entity.getMaxCapacity())
                .isApprovalRequired(request.getIsApprovalRequired() != null
                        ? request.getIsApprovalRequired() : entity.getIsApprovalRequired())
                .attendanceMode(request.getAttendanceMode() != null
                        ? request.getAttendanceMode() : entity.getAttendanceMode())
                .preSurveyId(request.getPreSurveyId() != null ? request.getPreSurveyId() : entity.getPreSurveyId())
                .ogpTitle(request.getOgpTitle() != null ? request.getOgpTitle() : entity.getOgpTitle())
                .ogpDescription(request.getOgpDescription() != null ? request.getOgpDescription() : entity.getOgpDescription())
                .ogpImageKey(request.getOgpImageKey() != null ? request.getOgpImageKey() : entity.getOgpImageKey())
                .build();

        EventEntity saved = eventRepository.save(updated);
        log.info("イベント更新: eventId={}", eventId);
        return toDetailResponseWithRsvp(saved);
    }

    /**
     * イベントを公開する。
     *
     * @param eventId イベントID
     * @return 更新されたイベント詳細レスポンス
     */
    @Transactional
    public EventDetailResponse publishEvent(Long eventId) {
        EventEntity entity = findEventOrThrow(eventId);
        if (entity.getStatus() != EventStatus.DRAFT) {
            throw new BusinessException(EventErrorCode.INVALID_STATUS_TRANSITION);
        }
        entity.publish();
        EventEntity saved = eventRepository.save(entity);
        log.info("イベント公開: eventId={}", eventId);
        return toDetailResponseWithRsvp(saved);
    }

    /**
     * 参加登録を開始する。
     *
     * @param eventId イベントID
     * @return 更新されたイベント詳細レスポンス
     */
    @Transactional
    public EventDetailResponse openRegistration(Long eventId) {
        EventEntity entity = findEventOrThrow(eventId);
        if (entity.getStatus() != EventStatus.PUBLISHED) {
            throw new BusinessException(EventErrorCode.INVALID_STATUS_TRANSITION);
        }
        entity.openRegistration();
        EventEntity saved = eventRepository.save(entity);
        log.info("参加登録開始: eventId={}", eventId);
        return toDetailResponseWithRsvp(saved);
    }

    /**
     * 参加登録を締め切る。
     *
     * @param eventId イベントID
     * @return 更新されたイベント詳細レスポンス
     */
    @Transactional
    public EventDetailResponse closeRegistration(Long eventId) {
        EventEntity entity = findEventOrThrow(eventId);
        if (entity.getStatus() != EventStatus.REGISTRATION_OPEN) {
            throw new BusinessException(EventErrorCode.INVALID_STATUS_TRANSITION);
        }
        entity.closeRegistration();
        EventEntity saved = eventRepository.save(entity);
        log.info("参加登録締切: eventId={}", eventId);
        return toDetailResponseWithRsvp(saved);
    }

    /**
     * イベントをキャンセルする。
     *
     * @param eventId イベントID
     * @return 更新されたイベント詳細レスポンス
     */
    @Transactional
    public EventDetailResponse cancelEvent(Long eventId) {
        EventEntity entity = findEventOrThrow(eventId);
        if (entity.getStatus() == EventStatus.COMPLETED || entity.getStatus() == EventStatus.CANCELLED) {
            throw new BusinessException(EventErrorCode.INVALID_STATUS_TRANSITION);
        }
        entity.cancel();
        EventEntity saved = eventRepository.save(entity);
        log.info("イベントキャンセル: eventId={}", eventId);
        return toDetailResponseWithRsvp(saved);
    }

    /**
     * イベントを完了にする。
     *
     * @param eventId イベントID
     * @return 更新されたイベント詳細レスポンス
     */
    @Transactional
    public EventDetailResponse completeEvent(Long eventId) {
        EventEntity entity = findEventOrThrow(eventId);
        if (entity.getStatus() == EventStatus.DRAFT || entity.getStatus() == EventStatus.COMPLETED
                || entity.getStatus() == EventStatus.CANCELLED) {
            throw new BusinessException(EventErrorCode.INVALID_STATUS_TRANSITION);
        }
        entity.complete();
        EventEntity saved = eventRepository.save(entity);
        log.info("イベント完了: eventId={}", eventId);
        return toDetailResponseWithRsvp(saved);
    }

    /**
     * イベントを論理削除する。
     *
     * @param eventId イベントID
     */
    @Transactional
    public void deleteEvent(Long eventId) {
        EventEntity entity = findEventOrThrow(eventId);
        entity.softDelete();
        eventRepository.save(entity);
        log.info("イベント削除: eventId={}", eventId);
    }

    /**
     * イベント統計を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return イベント統計レスポンス
     */
    public EventStatsResponse getStats(EventScopeType scopeType, Long scopeId) {
        long draft = eventRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, EventStatus.DRAFT);
        long published = eventRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, EventStatus.PUBLISHED)
                + eventRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, EventStatus.REGISTRATION_OPEN)
                + eventRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, EventStatus.REGISTRATION_CLOSED)
                + eventRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, EventStatus.IN_PROGRESS);
        long completed = eventRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, EventStatus.COMPLETED);
        long cancelled = eventRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, EventStatus.CANCELLED);
        long total = draft + published + completed + cancelled;

        // スコープ配下全イベントの登録・チェックイン数を集計
        Page<EventEntity> allEvents = eventRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                scopeType, scopeId, org.springframework.data.domain.PageRequest.of(0, 10000));
        long totalRegistrations = 0;
        long approvedRegistrations = 0;
        long totalCheckins = 0;
        for (EventEntity event : allEvents.getContent()) {
            totalRegistrations += registrationRepository.countByEventIdAndStatus(
                    event.getId(), RegistrationStatus.PENDING)
                    + registrationRepository.countByEventIdAndStatus(
                    event.getId(), RegistrationStatus.APPROVED);
            approvedRegistrations += registrationRepository.countByEventIdAndStatus(
                    event.getId(), RegistrationStatus.APPROVED);
            totalCheckins += checkinRepository.countByEventId(event.getId());
        }

        return new EventStatsResponse(total, draft, published, completed, cancelled,
                totalRegistrations, approvedRegistrations, totalCheckins);
    }

    /**
     * F00 Phase B — 指定ユーザーが対象イベントを閲覧可能かを共通基盤経由で判定する。
     *
     * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §12.3 / §12.6.1。
     * 既存 API のリグレッションを避けるため、本メソッドは新規追加であり既存コール
     * （{@code getEvent} / {@code listEvents} 等）の呼び出し経路は変更しない。</p>
     *
     * <p>判定は {@link ContentVisibilityChecker#canView} に委譲し、
     * {@link EventVisibilityResolver} が events.status / events.visibility / メンバーシップを
     * 1 リクエスト内最小 SQL で解決する。</p>
     *
     * @param eventId      対象 event_id
     * @param viewerUserId 閲覧者 user_id（{@code null} 可: 匿名）
     * @return 閲覧可能なら true
     */
    public boolean canView(Long eventId, Long viewerUserId) {
        return contentVisibilityChecker.canView(ReferenceType.EVENT, eventId, viewerUserId);
    }

    /**
     * イベントエンティティを取得する（内部用）。
     *
     * @param eventId イベントID
     * @return イベントエンティティ
     */
    public EventEntity findEventOrThrow(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(EventErrorCode.EVENT_NOT_FOUND));
    }

    /**
     * EventEntity → EventDetailResponse 変換（RSVP集計付き）。
     * attendance_mode=RSVP のときのみ rsvpSummary をセットする。
     *
     * @param entity イベントエンティティ
     * @return イベント詳細レスポンス
     */
    private EventDetailResponse toDetailResponseWithRsvp(EventEntity entity) {
        EventDetailResponse response = eventMapper.toEventDetailResponse(entity);
        if (entity.getAttendanceMode() == EventAttendanceMode.RSVP) {
            Long eventId = entity.getId();
            long attending    = rsvpResponseRepository.countByEventIdAndResponse(eventId, "ATTENDING");
            long notAttending = rsvpResponseRepository.countByEventIdAndResponse(eventId, "NOT_ATTENDING");
            long maybe        = rsvpResponseRepository.countByEventIdAndResponse(eventId, "MAYBE");
            long undecided    = rsvpResponseRepository.countByEventIdAndResponse(eventId, "UNDECIDED");
            long total        = attending + notAttending + maybe + undecided;
            response.setRsvpSummary(new EventRsvpSummaryResponse(attending, notAttending, maybe, undecided, total));
        }
        return response;
    }
}
