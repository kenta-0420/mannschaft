package com.mannschaft.app.event;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.dto.CheckinRequest;
import com.mannschaft.app.event.dto.CheckinResponse;
import com.mannschaft.app.event.dto.SelfCheckinRequest;
import com.mannschaft.app.event.entity.EventCheckinEntity;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventTicketEntity;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventTicketRepository;
import com.mannschaft.app.event.service.EventCheckinService;
import com.mannschaft.app.event.service.EventTicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link EventCheckinService} の単体テスト。
 * QRスキャン・セルフチェックインを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventCheckinService 単体テスト")
class EventCheckinServiceTest {

    @Mock
    private EventCheckinRepository checkinRepository;

    @Mock
    private EventTicketRepository ticketRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventTicketService ticketService;

    @Mock
    private EventMapper eventMapper;

    @InjectMocks
    private EventCheckinService eventCheckinService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long EVENT_ID = 1L;
    private static final Long TICKET_ID = 10L;
    private static final Long STAFF_USER_ID = 100L;
    private static final String QR_TOKEN = "valid-qr-token";

    private EventTicketEntity createValidTicket() {
        return EventTicketEntity.builder()
                .registrationId(5L)
                .eventId(EVENT_ID)
                .ticketTypeId(3L)
                .qrToken(QR_TOKEN)
                .ticketNumber("EVT1-0001")
                .status(TicketStatus.VALID)
                .build();
    }

    private EventTicketEntity createUsedTicket() {
        return EventTicketEntity.builder()
                .registrationId(5L)
                .eventId(EVENT_ID)
                .ticketTypeId(3L)
                .qrToken(QR_TOKEN)
                .ticketNumber("EVT1-0001")
                .status(TicketStatus.USED)
                .build();
    }

    private EventCheckinEntity createCheckinEntity() {
        return EventCheckinEntity.builder()
                .eventId(EVENT_ID)
                .ticketId(TICKET_ID)
                .checkinType(CheckinType.STAFF_SCAN)
                .checkedInBy(STAFF_USER_ID)
                .build();
    }

    private CheckinResponse createCheckinResponse() {
        return new CheckinResponse(
                1L, EVENT_ID, TICKET_ID, "STAFF_SCAN", STAFF_USER_ID,
                LocalDateTime.now(), null, LocalDateTime.now()
        );
    }

    /**
     * EventCheckinEntity は @PrePersist で createdAt を設定するため、
     * save モック内で onCreate() をリフレクションで呼び出す。
     */
    private EventCheckinEntity invokeOnCreate(EventCheckinEntity entity) {
        try {
            Method onCreate = EventCheckinEntity.class.getDeclaredMethod("onCreate");
            onCreate.setAccessible(true);
            onCreate.invoke(entity);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return entity;
    }

    // ========================================
    // listCheckins
    // ========================================

    @Nested
    @DisplayName("listCheckins")
    class ListCheckins {

        @Test
        @DisplayName("チェックイン一覧取得_正常_ページ返却")
        void チェックイン一覧取得_正常_ページ返却() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            EventCheckinEntity entity = createCheckinEntity();
            Page<EventCheckinEntity> page = new PageImpl<>(List.of(entity), pageable, 1);
            CheckinResponse response = createCheckinResponse();

            given(checkinRepository.findByEventIdOrderByCheckedInAtDesc(EVENT_ID, pageable))
                    .willReturn(page);
            given(eventMapper.toCheckinResponse(entity)).willReturn(response);

            // When
            Page<CheckinResponse> result = eventCheckinService.listCheckins(EVENT_ID, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }
    }

    // ========================================
    // staffCheckin
    // ========================================

    @Nested
    @DisplayName("staffCheckin")
    class StaffCheckin {

        @Test
        @DisplayName("スタッフチェックイン_正常_レスポンス返却")
        void スタッフチェックイン_正常_レスポンス返却() {
            // Given
            CheckinRequest request = new CheckinRequest(QR_TOKEN, "備考");
            EventTicketEntity ticket = createValidTicket();
            EventEntity event = EventEntity.builder()
                    .scopeType(EventScopeType.TEAM).scopeId(10L).slug("ev")
                    .status(EventStatus.REGISTRATION_OPEN).isPublic(false)
                    .isApprovalRequired(false).build();
            CheckinResponse response = createCheckinResponse();

            given(ticketService.findTicketByQrTokenOrThrow(QR_TOKEN)).willReturn(ticket);
            given(checkinRepository.existsByTicketId(any())).willReturn(false);
            given(ticketRepository.save(any(EventTicketEntity.class))).willReturn(ticket);
            given(checkinRepository.save(any(EventCheckinEntity.class))).willAnswer(inv -> {
                EventCheckinEntity saved = inv.getArgument(0);
                invokeOnCreate(saved);
                return saved;
            });
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(event));
            given(eventRepository.save(any(EventEntity.class))).willReturn(event);
            given(eventMapper.toCheckinResponse(any(EventCheckinEntity.class))).willReturn(response);

            // When
            CheckinResponse result = eventCheckinService.staffCheckin(STAFF_USER_ID, request);

            // Then
            assertThat(result.getCheckinType()).isEqualTo("STAFF_SCAN");
            verify(ticketRepository).save(any(EventTicketEntity.class));
            verify(checkinRepository).save(any(EventCheckinEntity.class));
        }

        @Test
        @DisplayName("スタッフチェックイン_チケット使用済み_例外スロー")
        void スタッフチェックイン_チケット使用済み_例外スロー() {
            // Given
            CheckinRequest request = new CheckinRequest(QR_TOKEN, null);
            EventTicketEntity ticket = createUsedTicket();

            given(ticketService.findTicketByQrTokenOrThrow(QR_TOKEN)).willReturn(ticket);

            // When & Then
            assertThatThrownBy(() -> eventCheckinService.staffCheckin(STAFF_USER_ID, request))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("スタッフチェックイン_既にチェックイン済み_例外スロー")
        void スタッフチェックイン_既にチェックイン済み_例外スロー() {
            // Given
            CheckinRequest request = new CheckinRequest(QR_TOKEN, null);
            EventTicketEntity ticket = createValidTicket();

            given(ticketService.findTicketByQrTokenOrThrow(QR_TOKEN)).willReturn(ticket);
            given(checkinRepository.existsByTicketId(any())).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> eventCheckinService.staffCheckin(STAFF_USER_ID, request))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // selfCheckin
    // ========================================

    @Nested
    @DisplayName("selfCheckin")
    class SelfCheckin {

        @Test
        @DisplayName("セルフチェックイン_正常_レスポンス返却")
        void セルフチェックイン_正常_レスポンス返却() {
            // Given
            SelfCheckinRequest request = new SelfCheckinRequest(QR_TOKEN);
            EventTicketEntity ticket = createValidTicket();
            EventEntity event = EventEntity.builder()
                    .scopeType(EventScopeType.TEAM).scopeId(10L).slug("ev")
                    .status(EventStatus.REGISTRATION_OPEN).isPublic(false)
                    .isApprovalRequired(false).build();
            CheckinResponse response = new CheckinResponse(
                    2L, EVENT_ID, TICKET_ID, "SELF", null,
                    LocalDateTime.now(), null, LocalDateTime.now()
            );

            given(ticketService.findTicketByQrTokenOrThrow(QR_TOKEN)).willReturn(ticket);
            given(checkinRepository.existsByTicketId(any())).willReturn(false);
            given(ticketRepository.save(any(EventTicketEntity.class))).willReturn(ticket);
            given(checkinRepository.save(any(EventCheckinEntity.class))).willAnswer(inv -> {
                EventCheckinEntity saved = inv.getArgument(0);
                invokeOnCreate(saved);
                return saved;
            });
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(event));
            given(eventRepository.save(any(EventEntity.class))).willReturn(event);
            given(eventMapper.toCheckinResponse(any(EventCheckinEntity.class))).willReturn(response);

            // When
            CheckinResponse result = eventCheckinService.selfCheckin(request);

            // Then
            assertThat(result.getCheckinType()).isEqualTo("SELF");
        }
    }

    // ========================================
    // getCheckinCount
    // ========================================

    @Nested
    @DisplayName("getCheckinCount")
    class GetCheckinCount {

        @Test
        @DisplayName("チェックイン数取得_正常_カウント返却")
        void チェックイン数取得_正常_カウント返却() {
            // Given
            given(checkinRepository.countByEventId(EVENT_ID)).willReturn(5L);

            // When
            long result = eventCheckinService.getCheckinCount(EVENT_ID);

            // Then
            assertThat(result).isEqualTo(5L);
        }
    }
}
