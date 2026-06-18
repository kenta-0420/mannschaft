package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
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
import com.mannschaft.app.event.event.EventCreatedEvent;
import com.mannschaft.app.event.event.EventStatusChangedEvent;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRegistrationRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.event.RegistrationStatus;
import com.mannschaft.app.common.util.SlugGenerator;
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
    private final DomainEventPublisher domainEventPublisher;

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
        // slug が未指定（null / 空文字）の場合は subtitle から自動生成する（TeamService.createUniqueSlug と同パターン）
        String slug = resolveSlugForCreate(request.getSlug(), request.getSubtitle());

        EventEntity entity = EventEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .scheduleId(request.getScheduleId())
                .slug(slug)
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

        // イベント専用チャットチャンネル自動生成のためにドメインイベントを発行する
        String title = request.getSubtitle() != null ? request.getSubtitle() : request.getSlug();
        domainEventPublisher.publish(new EventCreatedEvent(saved.getId(), scopeType, scopeId, title));

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

        // visibility 文字列は enum へ解決してから渡す（null なら現値維持）。
        // 新ラダー値名（MEMBERS_AND_ABOVE 等）は EventVisibility に追加済みのため valueOf で受理される。
        EventVisibility newVisibility = request.getVisibility() != null
                ? EventVisibility.valueOf(request.getVisibility())
                : null;

        // 根治: toBuilder().build() で作り直すと継承フィールド id が欠落し INSERT になる
        //       （slug 一意制約違反で 500）。managed entity を直接ミューテートして
        //       JPA dirty checking で UPDATE させる（EventEntity.applyUpdate の Javadoc 参照）。
        entity.applyUpdate(
                request.getSlug(),
                request.getSubtitle(),
                request.getSummary(),
                request.getCoverImageKey(),
                request.getVenueName(),
                request.getVenueAddress(),
                request.getVenueLatitude(),
                request.getVenueLongitude(),
                request.getVenueAccessInfo(),
                newVisibility,
                request.getRegistrationStartsAt(),
                request.getRegistrationEndsAt(),
                request.getMaxCapacity(),
                request.getIsApprovalRequired(),
                request.getAttendanceMode(),
                request.getPreSurveyId(),
                request.getOgpTitle(),
                request.getOgpDescription(),
                request.getOgpImageKey()
        );

        EventEntity saved = eventRepository.save(entity);
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

        // イベント専用チャットチャンネルアーカイブのためにドメインイベントを発行する
        domainEventPublisher.publish(new EventStatusChangedEvent(eventId, EventStatus.CANCELLED));

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

        // イベント専用チャットチャンネルアーカイブのためにドメインイベントを発行する
        domainEventPublisher.publish(new EventStatusChangedEvent(eventId, EventStatus.COMPLETED));

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
     * 作成時の slug を解決する（{@link com.mannschaft.app.team.service.TeamService#createUniqueSlug} と同パターン）。
     *
     * <p>ユーザーが slug を指定した場合は一意性を検証して採用する。
     * 未指定（null / 空文字）の場合は subtitle から {@link SlugGenerator#generate} で自動生成し、
     * 重複時は数値サフィックス (-1, -2, ...) を付与して一意化する。
     * subtitle も空の最終フォールバックは {@code "event"} を使う。</p>
     *
     * @param requestedSlug ユーザー入力 slug（null / 空文字可）
     * @param subtitle      イベントサブタイトル（自動生成フォールバック用）
     * @return 採用する一意な slug
     * @throws BusinessException slug が既に使用中の場合（SLUG_ALREADY_EXISTS）
     */
    private String resolveSlugForCreate(String requestedSlug, String subtitle) {
        if (requestedSlug != null && !requestedSlug.isBlank()) {
            // ユーザー指定slugの一意性チェック
            if (eventRepository.existsBySlug(requestedSlug)) {
                throw new BusinessException(EventErrorCode.SLUG_ALREADY_EXISTS);
            }
            return requestedSlug;
        }
        // 未指定の場合はsubtitleから自動生成
        String source = (subtitle != null && !subtitle.isBlank()) ? subtitle : "event";
        String base = SlugGenerator.generate(source);
        if (!eventRepository.existsBySlug(base)) {
            return base;
        }
        for (int i = 1; i <= 100; i++) {
            String candidate = SlugGenerator.withSuffix(base, i);
            if (!eventRepository.existsBySlug(candidate)) {
                return candidate;
            }
        }
        // 100回試行してもユニークにならない場合はタイムスタンプサフィックス
        return SlugGenerator.withSuffix(base, (int) (System.currentTimeMillis() % 10000));
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
            response = response.withRsvpSummary(new EventRsvpSummaryResponse(attending, notAttending, maybe, undecided, total));
        }
        return response;
    }
}
