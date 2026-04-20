package com.mannschaft.app.event;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.dto.EventDetailResponse;
import com.mannschaft.app.event.dto.EventStatsResponse;
import com.mannschaft.app.event.dto.UpdateEventRequest;
import com.mannschaft.app.event.entity.EventAttendanceMode;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRegistrationRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.service.EventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

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
 * {@link EventService} の追加単体テスト。
 * updateEvent / completeEvent(CANCELLED) / getStats(イベントあり) / findEventOrThrow を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventService 追加単体テスト")
class EventServiceAdditionalTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventRegistrationRepository registrationRepository;

    @Mock
    private EventCheckinRepository checkinRepository;

    @Mock
    private EventMapper eventMapper;

    @InjectMocks
    private EventService eventService;

    private static final Long EVENT_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final EventScopeType SCOPE_TYPE = EventScopeType.TEAM;

    private EventEntity createEventWithStatus(EventStatus status) {
        return EventEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .slug("test-event")
                .status(status)
                .isPublic(false)
                .isApprovalRequired(false)
                .createdBy(USER_ID)
                .build();
    }

    private EventDetailResponse createDetailResponse() {
        return new EventDetailResponse(
                EVENT_ID, "TEAM", SCOPE_ID, null, "test-event", null,
                null, null, null, null, null, null, null,
                "DRAFT", false, "MEMBER_PLUS", null, null, null, false,
                EventAttendanceMode.REGISTRATION, null,
                null, null, null, null, null, 0, 0, USER_ID, 0L,
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    // ========================================
    // updateEvent
    // ========================================

    @Nested
    @DisplayName("updateEvent")
    class UpdateEvent {

        @Test
        @DisplayName("正常系: スラグ変更なしで更新される")
        void updateEvent_スラグ変更なし_更新される() {
            // Given
            EventEntity entity = createEventWithStatus(EventStatus.DRAFT);
            EventDetailResponse response = createDetailResponse();
            UpdateEventRequest request = new UpdateEventRequest(
                    null, "新しいサブタイトル", null, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null);

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));
            given(eventRepository.save(any(EventEntity.class))).willReturn(entity);
            given(eventMapper.toEventDetailResponse(any())).willReturn(response);

            // When
            EventDetailResponse result = eventService.updateEvent(EVENT_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(eventRepository).save(any(EventEntity.class));
        }

        @Test
        @DisplayName("正常系: スラグ変更ありで重複チェックされる")
        void updateEvent_スラグ変更あり_重複チェック() {
            // Given
            EventEntity entity = createEventWithStatus(EventStatus.DRAFT);
            EventDetailResponse response = createDetailResponse();
            UpdateEventRequest request = new UpdateEventRequest(
                    "new-slug", null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null);

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));
            given(eventRepository.existsBySlug("new-slug")).willReturn(false);
            given(eventRepository.save(any(EventEntity.class))).willReturn(entity);
            given(eventMapper.toEventDetailResponse(any())).willReturn(response);

            // When
            EventDetailResponse result = eventService.updateEvent(EVENT_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(eventRepository).existsBySlug("new-slug");
        }

        @Test
        @DisplayName("異常系: スラグ重複で例外スロー")
        void updateEvent_スラグ重複_例外スロー() {
            // Given
            EventEntity entity = createEventWithStatus(EventStatus.DRAFT);
            UpdateEventRequest request = new UpdateEventRequest(
                    "duplicate-slug", null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null);

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));
            given(eventRepository.existsBySlug("duplicate-slug")).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> eventService.updateEvent(EVENT_ID, request))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("異常系: イベント不在で例外スロー")
        void updateEvent_不在_例外スロー() {
            // Given
            UpdateEventRequest request = new UpdateEventRequest(
                    null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null);
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> eventService.updateEvent(EVENT_ID, request))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // completeEvent 追加ケース
    // ========================================

    @Nested
    @DisplayName("completeEvent 追加ケース")
    class CompleteEventAdditional {

        @Test
        @DisplayName("イベント完了_CANCELLED_例外スロー")
        void イベント完了_CANCELLED_例外スロー() {
            // Given
            EventEntity entity = createEventWithStatus(EventStatus.CANCELLED);
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> eventService.completeEvent(EVENT_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("イベント完了_REGISTRATION_OPEN_正常完了")
        void イベント完了_REGISTRATION_OPEN_正常完了() {
            // Given
            EventEntity entity = createEventWithStatus(EventStatus.REGISTRATION_OPEN);
            EventDetailResponse response = createDetailResponse();

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));
            given(eventRepository.save(any(EventEntity.class))).willReturn(entity);
            given(eventMapper.toEventDetailResponse(any())).willReturn(response);

            // When
            EventDetailResponse result = eventService.completeEvent(EVENT_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("イベント完了_IN_PROGRESS_正常完了")
        void イベント完了_IN_PROGRESS_正常完了() {
            // Given
            EventEntity entity = createEventWithStatus(EventStatus.IN_PROGRESS);
            EventDetailResponse response = createDetailResponse();

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));
            given(eventRepository.save(any(EventEntity.class))).willReturn(entity);
            given(eventMapper.toEventDetailResponse(any())).willReturn(response);

            // When
            EventDetailResponse result = eventService.completeEvent(EVENT_ID);

            // Then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // cancelEvent 追加ケース
    // ========================================

    @Nested
    @DisplayName("cancelEvent 追加ケース")
    class CancelEventAdditional {

        @Test
        @DisplayName("イベントキャンセル_PUBLISHED_正常キャンセル")
        void イベントキャンセル_PUBLISHED_正常キャンセル() {
            // Given
            EventEntity entity = createEventWithStatus(EventStatus.PUBLISHED);
            EventDetailResponse response = createDetailResponse();

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));
            given(eventRepository.save(any(EventEntity.class))).willReturn(entity);
            given(eventMapper.toEventDetailResponse(any())).willReturn(response);

            // When
            EventDetailResponse result = eventService.cancelEvent(EVENT_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("イベントキャンセル_REGISTRATION_OPEN_正常キャンセル")
        void イベントキャンセル_REGISTRATION_OPEN_正常キャンセル() {
            // Given
            EventEntity entity = createEventWithStatus(EventStatus.REGISTRATION_OPEN);
            EventDetailResponse response = createDetailResponse();

            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(entity));
            given(eventRepository.save(any(EventEntity.class))).willReturn(entity);
            given(eventMapper.toEventDetailResponse(any())).willReturn(response);

            // When
            EventDetailResponse result = eventService.cancelEvent(EVENT_ID);

            // Then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // getStats (イベントあり)
    // ========================================

    @Nested
    @DisplayName("getStats イベントあり")
    class GetStatsWithEvents {

        @Test
        @DisplayName("統計取得_イベントあり_登録数とチェックイン数が集計される")
        void 統計取得_イベントあり_登録数とチェックイン数が集計される() {
            // Given
            EventEntity event1 = createEventWithStatus(EventStatus.COMPLETED);

            given(eventRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, EventStatus.DRAFT)).willReturn(0L);
            given(eventRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, EventStatus.PUBLISHED)).willReturn(0L);
            given(eventRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, EventStatus.REGISTRATION_OPEN)).willReturn(0L);
            given(eventRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, EventStatus.REGISTRATION_CLOSED)).willReturn(0L);
            given(eventRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, EventStatus.IN_PROGRESS)).willReturn(0L);
            given(eventRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, EventStatus.COMPLETED)).willReturn(1L);
            given(eventRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, EventStatus.CANCELLED)).willReturn(0L);

            Page<EventEntity> eventsPage = new PageImpl<>(List.of(event1));
            given(eventRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(eq(SCOPE_TYPE), eq(SCOPE_ID), any(Pageable.class)))
                    .willReturn(eventsPage);
            given(registrationRepository.countByEventIdAndStatus(any(), eq(RegistrationStatus.PENDING))).willReturn(3L);
            given(registrationRepository.countByEventIdAndStatus(any(), eq(RegistrationStatus.APPROVED))).willReturn(10L);
            given(checkinRepository.countByEventId(any())).willReturn(8L);

            // When
            EventStatsResponse result = eventService.getStats(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result.getTotalRegistrations()).isEqualTo(13L); // 3 + 10
            assertThat(result.getApprovedRegistrations()).isEqualTo(10L);
            assertThat(result.getTotalCheckins()).isEqualTo(8L);
        }
    }
}
