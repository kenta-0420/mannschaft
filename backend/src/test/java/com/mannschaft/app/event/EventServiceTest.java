package com.mannschaft.app.event;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.event.dto.CreateEventRequest;
import com.mannschaft.app.event.dto.UpdateEventRequest;
import com.mannschaft.app.event.dto.EventDetailResponse;
import com.mannschaft.app.event.dto.EventDetailResponse.EventAuditDto;
import com.mannschaft.app.event.dto.EventDetailResponse.EventContentDto;
import com.mannschaft.app.event.dto.EventDetailResponse.EventMetaDto;
import com.mannschaft.app.event.dto.EventDetailResponse.EventRegistrationDto;
import com.mannschaft.app.event.dto.EventDetailResponse.EventScopeDto;
import com.mannschaft.app.event.dto.EventDetailResponse.EventVenueDto;
import com.mannschaft.app.event.dto.EventResponse;
import com.mannschaft.app.event.dto.EventStatsResponse;
import com.mannschaft.app.event.entity.EventAttendanceMode;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRegistrationRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.service.EventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link EventService} の単体テスト。
 * イベントのCRUD・ステータス遷移・統計を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventService 単体テスト")
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventRegistrationRepository registrationRepository;

    @Mock
    private EventCheckinRepository checkinRepository;

    @Mock
    private EventMapper eventMapper;

    @Mock
    private DomainEventPublisher domainEventPublisher;

    @InjectMocks
    private EventService eventService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long EVENT_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final EventScopeType SCOPE_TYPE = EventScopeType.TEAM;

    private EventEntity createDraftEvent() {
        return EventEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .slug("test-event")
                .subtitle("テストイベント")
                .summary("テスト用イベントの説明")
                .status(EventStatus.DRAFT)
                .visibility(EventVisibility.MEMBERS_ONLY)
                .isApprovalRequired(false)
                .createdBy(USER_ID)
                .build();
    }

    private EventEntity createEventWithStatus(EventStatus status) {
        return EventEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .slug("test-event")
                .status(status)
                .visibility(EventVisibility.MEMBERS_ONLY)
                .isApprovalRequired(false)
                .createdBy(USER_ID)
                .build();
    }

    private EventDetailResponse createEventDetailResponse() {
        LocalDateTime now = LocalDateTime.now();
        return EventDetailResponse.builder()
                .id(EVENT_ID)
                .scope(new EventScopeDto("TEAM", SCOPE_ID, null, null))
                .content(new EventContentDto("test-event", "テストイベント", "テスト用イベントの説明", null))
                .venue(new EventVenueDto(null, null, null, null, null))
                .registration(new EventRegistrationDto(null, null, null, false,
                        EventAttendanceMode.REGISTRATION, null, null, 0, 0))
                .meta(new EventMetaDto("DRAFT", "MEMBERS_ONLY", null, null, null))
                .audit(new EventAuditDto(USER_ID, now, now, 0L))
                .build();
    }

    // ========================================
    // listEvents
    // ========================================

    @Nested
    @DisplayName("listEvents")
    class ListEvents {

        @Test
        @DisplayName("イベント一覧取得_ステータスフィルタなし_ページ返却")
        void イベント一覧取得_ステータスフィルタなし_ページ返却() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            EventEntity entity = createDraftEvent();
            Page<EventEntity> page = new PageImpl<>(List.of(entity), pageable, 1);
            EventResponse response = new EventResponse(
                    EVENT_ID, "TEAM", SCOPE_ID, "test-event", "テストイベント",
                    null, "DRAFT", "MEMBERS_ONLY", null, null, null, 0, 0,
                    LocalDateTime.now(), LocalDateTime.now()
            );

            given(eventRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(SCOPE_TYPE, SCOPE_ID, pageable))
                    .willReturn(page);
            given(eventMapper.toEventResponse(entity)).willReturn(response);

            // When
            Page<EventResponse> result = eventService.listEvents(SCOPE_TYPE, SCOPE_ID, null, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getSlug()).isEqualTo("test-event");
        }

        @Test
        @DisplayName("イベント一覧取得_ステータスフィルタあり_フィルタ適用")
        void イベント一覧取得_ステータスフィルタあり_フィルタ適用() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<EventEntity> page = new PageImpl<>(List.of(), pageable, 0);

            given(eventRepository.findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    SCOPE_TYPE, SCOPE_ID, EventStatus.DRAFT, pageable))
                    .willReturn(page);

            // When
            Page<EventResponse> result = eventService.listEvents(SCOPE_TYPE, SCOPE_ID, "DRAFT", pageable);

            // Then
            assertThat(result.getContent()).isEmpty();
        }
    }

    // ========================================
    // getEvent
    // ========================================

    @Nested
    @DisplayName("getEvent")
    class GetEvent {

        @Test
        @DisplayName("イベント取得_正常_詳細レスポンス返却")
        void イベント取得_正常_詳細レスポンス返却() {
            // Given
            EventEntity entity = createDraftEvent();
            EventDetailResponse response = createEventDetailResponse();

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));
            given(eventMapper.toEventDetailResponse(entity)).willReturn(response);

            // When
            EventDetailResponse result = eventService.getEvent(EVENT_ID);

            // Then
            assertThat(result.getContent().slug()).isEqualTo("test-event");
        }

        @Test
        @DisplayName("イベント取得_存在しない_例外スロー")
        void イベント取得_存在しない_例外スロー() {
            // Given
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> eventService.getEvent(EVENT_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // getEventBySlug
    // ========================================

    @Nested
    @DisplayName("getEventBySlug")
    class GetEventBySlug {

        @Test
        @DisplayName("スラグ検索_正常_詳細レスポンス返却")
        void スラグ検索_正常_詳細レスポンス返却() {
            // Given
            EventEntity entity = createDraftEvent();
            EventDetailResponse response = createEventDetailResponse();

            given(eventRepository.findBySlug("test-event")).willReturn(Optional.of(entity));
            given(eventMapper.toEventDetailResponse(entity)).willReturn(response);

            // When
            EventDetailResponse result = eventService.getEventBySlug("test-event");

            // Then
            assertThat(result.getContent().slug()).isEqualTo("test-event");
        }

        @Test
        @DisplayName("スラグ検索_存在しない_例外スロー")
        void スラグ検索_存在しない_例外スロー() {
            // Given
            given(eventRepository.findBySlug("non-existent")).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> eventService.getEventBySlug("non-existent"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // createEvent
    // ========================================

    @Nested
    @DisplayName("createEvent")
    class CreateEvent {

        @Test
        @DisplayName("イベント作成_正常_詳細レスポンス返却")
        void イベント作成_正常_詳細レスポンス返却() {
            // Given
            CreateEventRequest request = new CreateEventRequest(
                    null, "test-event", "テストイベント", "説明", null,
                    null, null, null, null, null, "MEMBERS_ONLY",
                    null, null, null, false, EventAttendanceMode.REGISTRATION, null, null, null, null
            );
            EventEntity savedEntity = createDraftEvent();
            EventDetailResponse response = createEventDetailResponse();

            given(eventRepository.existsBySlug("test-event")).willReturn(false);
            given(eventRepository.save(any(EventEntity.class))).willReturn(savedEntity);
            given(eventMapper.toEventDetailResponse(savedEntity)).willReturn(response);

            // When
            EventDetailResponse result = eventService.createEvent(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(result.getContent().slug()).isEqualTo("test-event");
            verify(eventRepository).save(any(EventEntity.class));
        }

        @Test
        @DisplayName("イベント作成_スラグ重複_例外スロー")
        void イベント作成_スラグ重複_例外スロー() {
            // Given
            CreateEventRequest request = new CreateEventRequest(
                    null, "duplicate-slug", null, null, null,
                    null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null
            );
            given(eventRepository.existsBySlug("duplicate-slug")).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> eventService.createEvent(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // updateEvent
    // ========================================

    @Nested
    @DisplayName("updateEvent")
    class UpdateEvent {

        /**
         * 既存エンティティ（id 採番済み）を生成する。
         *
         * <p>{@link com.mannschaft.app.common.BaseEntity#id} は setter を持たないため
         * {@link ReflectionTestUtils} で採番済み状態を再現する（DB から findById で取得した
         * managed entity を模す）。
         */
        private EventEntity existingEvent() {
            EventEntity entity = createDraftEvent();
            ReflectionTestUtils.setField(entity, "id", EVENT_ID);
            return entity;
        }

        private UpdateEventRequest updateRequest(String slug, String visibility) {
            return new UpdateEventRequest(
                    slug, "更新後サブタイトル", "更新後サマリ", null,
                    null, null, null, null, null, visibility,
                    null, null, null, null, null, null, null, null, null
            );
        }

        @Test
        @DisplayName("イベント更新_既存エンティティをUPDATE_id不変かつ新規行を作らない")
        void イベント更新_既存エンティティをUPDATE_id不変かつ新規行を作らない() {
            // Given: findById で取得した id 採番済みの managed entity
            EventEntity existing = existingEvent();
            EventDetailResponse response = createEventDetailResponse();
            UpdateEventRequest request = updateRequest(null, null); // slug 据置（重複チェックに入らない）

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(existing));
            given(eventRepository.save(any(EventEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(eventMapper.toEventDetailResponse(any(EventEntity.class))).willReturn(response);

            // When
            eventService.updateEvent(EVENT_ID, request);

            // Then: save に渡るのは findById で取得した「まさにその」managed entity
            // （toBuilder().build() で作り直した別インスタンスではない）。
            // id が保持されているので save は UPDATE になり、新規 INSERT（id=null）は起きない。
            ArgumentCaptor<EventEntity> captor = ArgumentCaptor.forClass(EventEntity.class);
            verify(eventRepository).save(captor.capture());
            EventEntity saved = captor.getValue();
            assertThat(saved).isSameAs(existing);
            assertThat(saved.getId()).isEqualTo(EVENT_ID); // id 欠落（INSERT 化）が起きていない
            // 部分更新が反映されている
            assertThat(saved.getSubtitle()).isEqualTo("更新後サブタイトル");
            assertThat(saved.getSummary()).isEqualTo("更新後サマリ");
            // 未指定フィールドは現値維持
            assertThat(saved.getSlug()).isEqualTo("test-event");
        }

        @Test
        @DisplayName("イベント更新_slug据置_重複チェックを呼ばず500にならない")
        void イベント更新_slug据置_重複チェックを呼ばず500にならない() {
            // Given
            EventEntity existing = existingEvent();
            EventDetailResponse response = createEventDetailResponse();
            // slug を現値と同じにする → existsBySlug を呼んではならない（呼べば自分自身に当たり 500 の温床）
            UpdateEventRequest request = updateRequest("test-event", null);

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(existing));
            given(eventRepository.save(any(EventEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(eventMapper.toEventDetailResponse(any(EventEntity.class))).willReturn(response);

            // When
            EventDetailResponse result = eventService.updateEvent(EVENT_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(eventRepository, org.mockito.Mockito.never()).existsBySlug(any());
            verify(eventRepository).save(existing);
        }

        @Test
        @DisplayName("イベント更新_可視性MEMBERS_AND_ABOVE_新ラダー値で500にならず永続化")
        void イベント更新_可視性MEMBERS_AND_ABOVE_新ラダー値で500にならず永続化() {
            // Given: 旧 enum には無かった新ラダー値名。valueOf が IllegalArgumentException を投げると 500。
            EventEntity existing = existingEvent();
            EventDetailResponse response = createEventDetailResponse();
            UpdateEventRequest request = updateRequest(null, "MEMBERS_AND_ABOVE");

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(existing));
            given(eventRepository.save(any(EventEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(eventMapper.toEventDetailResponse(any(EventEntity.class))).willReturn(response);

            // When（例外が出ないこと自体が回帰防止）
            eventService.updateEvent(EVENT_ID, request);

            // Then: 解決済み enum が managed entity に反映される
            assertThat(existing.getVisibility()).isEqualTo(EventVisibility.MEMBERS_AND_ABOVE);
            verify(eventRepository).save(existing);
        }

        @Test
        @DisplayName("イベント更新_存在しない_例外スロー")
        void イベント更新_存在しない_例外スロー() {
            // Given
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> eventService.updateEvent(EVENT_ID, updateRequest(null, null)))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("イベント更新_slug変更で重複_例外スロー")
        void イベント更新_slug変更で重複_例外スロー() {
            // Given
            EventEntity existing = existingEvent();
            UpdateEventRequest request = updateRequest("taken-slug", null);

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(existing));
            given(eventRepository.existsBySlug("taken-slug")).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> eventService.updateEvent(EVENT_ID, request))
                    .isInstanceOf(BusinessException.class);
            verify(eventRepository, org.mockito.Mockito.never()).save(any());
        }
    }

    // ========================================
    // createEvent（可視性 新ラダー回帰）
    // ========================================

    @Nested
    @DisplayName("createEvent — 可視性新ラダー")
    class CreateEventVisibility {

        @Test
        @DisplayName("イベント作成_可視性MEMBERS_AND_ABOVE_新ラダー値で500にならず永続化")
        void イベント作成_可視性MEMBERS_AND_ABOVE_新ラダー値で500にならず永続化() {
            // Given
            CreateEventRequest request = new CreateEventRequest(
                    null, "new-ladder-event", "テストイベント", "説明", null,
                    null, null, null, null, null, "MEMBERS_AND_ABOVE",
                    null, null, null, false, EventAttendanceMode.REGISTRATION, null, null, null, null
            );
            EventDetailResponse response = createEventDetailResponse();

            given(eventRepository.existsBySlug("new-ladder-event")).willReturn(false);
            given(eventRepository.save(any(EventEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(eventMapper.toEventDetailResponse(any(EventEntity.class))).willReturn(response);

            // When（例外が出ないこと自体が回帰防止）
            eventService.createEvent(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then: 保存される entity に新ラダー可視性が反映される
            ArgumentCaptor<EventEntity> captor = ArgumentCaptor.forClass(EventEntity.class);
            verify(eventRepository).save(captor.capture());
            assertThat(captor.getValue().getVisibility()).isEqualTo(EventVisibility.MEMBERS_AND_ABOVE);
        }
    }

    // ========================================
    // publishEvent
    // ========================================

    @Nested
    @DisplayName("publishEvent")
    class PublishEvent {

        @Test
        @DisplayName("イベント公開_DRAFT_正常公開")
        void イベント公開_DRAFT_正常公開() {
            // Given
            EventEntity entity = createDraftEvent();
            EventDetailResponse response = createEventDetailResponse();

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));
            given(eventRepository.save(any(EventEntity.class))).willReturn(entity);
            given(eventMapper.toEventDetailResponse(entity)).willReturn(response);

            // When
            EventDetailResponse result = eventService.publishEvent(EVENT_ID);

            // Then
            assertThat(result).isNotNull();
            verify(eventRepository).save(any(EventEntity.class));
        }

        @Test
        @DisplayName("イベント公開_PUBLISHED_例外スロー")
        void イベント公開_PUBLISHED_例外スロー() {
            // Given
            EventEntity entity = createEventWithStatus(EventStatus.PUBLISHED);
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> eventService.publishEvent(EVENT_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // openRegistration
    // ========================================

    @Nested
    @DisplayName("openRegistration")
    class OpenRegistration {

        @Test
        @DisplayName("登録開始_PUBLISHED_正常開始")
        void 登録開始_PUBLISHED_正常開始() {
            // Given
            EventEntity entity = createEventWithStatus(EventStatus.PUBLISHED);
            EventDetailResponse response = createEventDetailResponse();

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));
            given(eventRepository.save(any(EventEntity.class))).willReturn(entity);
            given(eventMapper.toEventDetailResponse(entity)).willReturn(response);

            // When
            EventDetailResponse result = eventService.openRegistration(EVENT_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("登録開始_DRAFT_例外スロー")
        void 登録開始_DRAFT_例外スロー() {
            // Given
            EventEntity entity = createDraftEvent();
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> eventService.openRegistration(EVENT_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // closeRegistration
    // ========================================

    @Nested
    @DisplayName("closeRegistration")
    class CloseRegistration {

        @Test
        @DisplayName("登録締切_REGISTRATION_OPEN_正常締切")
        void 登録締切_REGISTRATION_OPEN_正常締切() {
            // Given
            EventEntity entity = createEventWithStatus(EventStatus.REGISTRATION_OPEN);
            EventDetailResponse response = createEventDetailResponse();

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));
            given(eventRepository.save(any(EventEntity.class))).willReturn(entity);
            given(eventMapper.toEventDetailResponse(entity)).willReturn(response);

            // When
            EventDetailResponse result = eventService.closeRegistration(EVENT_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("登録締切_PUBLISHED_例外スロー")
        void 登録締切_PUBLISHED_例外スロー() {
            // Given
            EventEntity entity = createEventWithStatus(EventStatus.PUBLISHED);
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> eventService.closeRegistration(EVENT_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // cancelEvent
    // ========================================

    @Nested
    @DisplayName("cancelEvent")
    class CancelEvent {

        @Test
        @DisplayName("イベントキャンセル_DRAFT_正常キャンセル")
        void イベントキャンセル_DRAFT_正常キャンセル() {
            // Given
            EventEntity entity = createDraftEvent();
            EventDetailResponse response = createEventDetailResponse();

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));
            given(eventRepository.save(any(EventEntity.class))).willReturn(entity);
            given(eventMapper.toEventDetailResponse(entity)).willReturn(response);

            // When
            EventDetailResponse result = eventService.cancelEvent(EVENT_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("イベントキャンセル_COMPLETED_例外スロー")
        void イベントキャンセル_COMPLETED_例外スロー() {
            // Given
            EventEntity entity = createEventWithStatus(EventStatus.COMPLETED);
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> eventService.cancelEvent(EVENT_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("イベントキャンセル_CANCELLED_例外スロー")
        void イベントキャンセル_CANCELLED_例外スロー() {
            // Given
            EventEntity entity = createEventWithStatus(EventStatus.CANCELLED);
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> eventService.cancelEvent(EVENT_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // completeEvent
    // ========================================

    @Nested
    @DisplayName("completeEvent")
    class CompleteEvent {

        @Test
        @DisplayName("イベント完了_PUBLISHED_正常完了")
        void イベント完了_PUBLISHED_正常完了() {
            // Given
            EventEntity entity = createEventWithStatus(EventStatus.PUBLISHED);
            EventDetailResponse response = createEventDetailResponse();

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));
            given(eventRepository.save(any(EventEntity.class))).willReturn(entity);
            given(eventMapper.toEventDetailResponse(entity)).willReturn(response);

            // When
            EventDetailResponse result = eventService.completeEvent(EVENT_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("イベント完了_DRAFT_例外スロー")
        void イベント完了_DRAFT_例外スロー() {
            // Given
            EventEntity entity = createDraftEvent();
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> eventService.completeEvent(EVENT_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("イベント完了_COMPLETED_例外スロー")
        void イベント完了_COMPLETED_例外スロー() {
            // Given
            EventEntity entity = createEventWithStatus(EventStatus.COMPLETED);
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> eventService.completeEvent(EVENT_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // deleteEvent
    // ========================================

    @Nested
    @DisplayName("deleteEvent")
    class DeleteEvent {

        @Test
        @DisplayName("イベント削除_正常_論理削除実行")
        void イベント削除_正常_論理削除実行() {
            // Given
            EventEntity entity = createDraftEvent();
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));
            given(eventRepository.save(any(EventEntity.class))).willReturn(entity);

            // When
            eventService.deleteEvent(EVENT_ID);

            // Then
            verify(eventRepository).save(any(EventEntity.class));
        }

        @Test
        @DisplayName("イベント削除_存在しない_例外スロー")
        void イベント削除_存在しない_例外スロー() {
            // Given
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> eventService.deleteEvent(EVENT_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // getStats
    // ========================================

    @Nested
    @DisplayName("getStats")
    class GetStats {

        @Test
        @DisplayName("統計取得_正常_統計レスポンス返却")
        void 統計取得_正常_統計レスポンス返却() {
            // Given
            given(eventRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, EventStatus.DRAFT)).willReturn(2L);
            given(eventRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, EventStatus.PUBLISHED)).willReturn(1L);
            given(eventRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, EventStatus.REGISTRATION_OPEN)).willReturn(1L);
            given(eventRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, EventStatus.REGISTRATION_CLOSED)).willReturn(0L);
            given(eventRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, EventStatus.IN_PROGRESS)).willReturn(0L);
            given(eventRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, EventStatus.COMPLETED)).willReturn(3L);
            given(eventRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, EventStatus.CANCELLED)).willReturn(1L);

            Page<EventEntity> emptyPage = new PageImpl<>(List.of());
            given(eventRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(eq(SCOPE_TYPE), eq(SCOPE_ID), any(Pageable.class)))
                    .willReturn(emptyPage);

            // When
            EventStatsResponse result = eventService.getStats(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result.getDraftEvents()).isEqualTo(2L);
            assertThat(result.getCompletedEvents()).isEqualTo(3L);
            assertThat(result.getCancelledEvents()).isEqualTo(1L);
            assertThat(result.getTotalEvents()).isEqualTo(8L);
        }
    }
}
