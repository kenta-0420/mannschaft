package com.mannschaft.app.event;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.dto.CreateRegistrationRequest;
import com.mannschaft.app.event.dto.GuestRegistrationRequest;
import com.mannschaft.app.event.dto.RegistrationResponse;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventGuestInviteTokenEntity;
import com.mannschaft.app.event.entity.EventRegistrationEntity;
import com.mannschaft.app.event.entity.EventTicketTypeEntity;
import com.mannschaft.app.event.repository.EventGuestInviteTokenRepository;
import com.mannschaft.app.event.repository.EventRegistrationRepository;
import com.mannschaft.app.event.repository.EventTicketTypeRepository;
import com.mannschaft.app.event.service.EventRegistrationService;
import com.mannschaft.app.event.service.EventService;
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

import java.math.BigDecimal;
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
 * {@link EventRegistrationService} の単体テスト。
 * 参加登録のCRUD・承認・却下を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventRegistrationService 単体テスト")
class EventRegistrationServiceTest {

    @Mock
    private EventRegistrationRepository registrationRepository;

    @Mock
    private EventTicketTypeRepository ticketTypeRepository;

    @Mock
    private EventGuestInviteTokenRepository inviteTokenRepository;

    @Mock
    private EventService eventService;

    @Mock
    private EventTicketService ticketService;

    @Mock
    private EventMapper eventMapper;

    @InjectMocks
    private EventRegistrationService eventRegistrationService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long EVENT_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long REGISTRATION_ID = 10L;
    private static final Long TICKET_TYPE_ID = 5L;

    private EventEntity createRegistrationOpenEvent() {
        return EventEntity.builder()
                .scopeType(EventScopeType.TEAM)
                .scopeId(10L)
                .slug("test-event")
                .status(EventStatus.REGISTRATION_OPEN)
                .isPublic(false)
                .isApprovalRequired(false)
                .maxCapacity(100)
                .build();
    }

    private EventEntity createApprovalRequiredEvent() {
        return EventEntity.builder()
                .scopeType(EventScopeType.TEAM)
                .scopeId(10L)
                .slug("approval-event")
                .status(EventStatus.REGISTRATION_OPEN)
                .isPublic(false)
                .isApprovalRequired(true)
                .maxCapacity(100)
                .build();
    }

    private EventTicketTypeEntity createTicketType() {
        return EventTicketTypeEntity.builder()
                .eventId(EVENT_ID)
                .name("一般チケット")
                .price(BigDecimal.ZERO)
                .currency("JPY")
                .maxQuantity(50)
                .build();
    }

    private EventRegistrationEntity createPendingRegistration() {
        return EventRegistrationEntity.builder()
                .eventId(EVENT_ID)
                .userId(USER_ID)
                .ticketTypeId(TICKET_TYPE_ID)
                .status(RegistrationStatus.PENDING)
                .quantity(1)
                .build();
    }

    private EventRegistrationEntity createApprovedRegistration() {
        return EventRegistrationEntity.builder()
                .eventId(EVENT_ID)
                .userId(USER_ID)
                .ticketTypeId(TICKET_TYPE_ID)
                .status(RegistrationStatus.APPROVED)
                .quantity(1)
                .build();
    }

    private RegistrationResponse createRegistrationResponse(String status) {
        return new RegistrationResponse(
                REGISTRATION_ID, EVENT_ID, USER_ID, TICKET_TYPE_ID,
                null, null, null, status, 1, null,
                null, null, null, null, null,
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    // ========================================
    // listRegistrations
    // ========================================

    @Nested
    @DisplayName("listRegistrations")
    class ListRegistrations {

        @Test
        @DisplayName("登録一覧取得_フィルタなし_ページ返却")
        void 登録一覧取得_フィルタなし_ページ返却() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            EventRegistrationEntity entity = createPendingRegistration();
            Page<EventRegistrationEntity> page = new PageImpl<>(List.of(entity), pageable, 1);
            RegistrationResponse response = createRegistrationResponse("PENDING");

            given(registrationRepository.findByEventIdOrderByCreatedAtDesc(EVENT_ID, pageable))
                    .willReturn(page);
            given(eventMapper.toRegistrationResponse(entity)).willReturn(response);

            // When
            Page<RegistrationResponse> result = eventRegistrationService.listRegistrations(EVENT_ID, null, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("登録一覧取得_ステータスフィルタあり_フィルタ適用")
        void 登録一覧取得_ステータスフィルタあり_フィルタ適用() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<EventRegistrationEntity> page = new PageImpl<>(List.of(), pageable, 0);

            given(registrationRepository.findByEventIdAndStatusOrderByCreatedAtDesc(
                    EVENT_ID, RegistrationStatus.PENDING, pageable))
                    .willReturn(page);

            // When
            Page<RegistrationResponse> result = eventRegistrationService.listRegistrations(EVENT_ID, "PENDING", pageable);

            // Then
            assertThat(result.getContent()).isEmpty();
        }
    }

    // ========================================
    // createRegistration
    // ========================================

    @Nested
    @DisplayName("createRegistration")
    class CreateRegistration {

        @Test
        @DisplayName("会員登録_承認不要_即承認")
        void 会員登録_承認不要_即承認() {
            // Given
            CreateRegistrationRequest request = new CreateRegistrationRequest(TICKET_TYPE_ID, 1, null);
            EventEntity event = createRegistrationOpenEvent();
            EventTicketTypeEntity ticketType = createTicketType();
            EventRegistrationEntity savedEntity = createApprovedRegistration();
            RegistrationResponse response = createRegistrationResponse("APPROVED");

            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(registrationRepository.existsByEventIdAndUserId(EVENT_ID, USER_ID)).willReturn(false);
            given(ticketTypeRepository.findById(TICKET_TYPE_ID)).willReturn(Optional.of(ticketType));
            given(registrationRepository.save(any(EventRegistrationEntity.class))).willReturn(savedEntity);
            given(ticketTypeRepository.save(any(EventTicketTypeEntity.class))).willReturn(ticketType);
            given(eventMapper.toRegistrationResponse(savedEntity)).willReturn(response);

            // When
            RegistrationResponse result = eventRegistrationService.createRegistration(EVENT_ID, USER_ID, request);

            // Then
            assertThat(result.getStatus()).isEqualTo("APPROVED");
            verify(ticketService).issueTickets(any(), any(), eq(1));
        }

        @Test
        @DisplayName("会員登録_承認必要_PENDING")
        void 会員登録_承認必要_PENDING() {
            // Given
            CreateRegistrationRequest request = new CreateRegistrationRequest(TICKET_TYPE_ID, 1, null);
            EventEntity event = createApprovalRequiredEvent();
            EventTicketTypeEntity ticketType = createTicketType();
            EventRegistrationEntity savedEntity = createPendingRegistration();
            RegistrationResponse response = createRegistrationResponse("PENDING");

            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(registrationRepository.existsByEventIdAndUserId(EVENT_ID, USER_ID)).willReturn(false);
            given(ticketTypeRepository.findById(TICKET_TYPE_ID)).willReturn(Optional.of(ticketType));
            given(registrationRepository.save(any(EventRegistrationEntity.class))).willReturn(savedEntity);
            given(eventMapper.toRegistrationResponse(savedEntity)).willReturn(response);

            // When
            RegistrationResponse result = eventRegistrationService.createRegistration(EVENT_ID, USER_ID, request);

            // Then
            assertThat(result.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("会員登録_二重登録_例外スロー")
        void 会員登録_二重登録_例外スロー() {
            // Given
            CreateRegistrationRequest request = new CreateRegistrationRequest(TICKET_TYPE_ID, 1, null);
            EventEntity event = createRegistrationOpenEvent();

            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(registrationRepository.existsByEventIdAndUserId(EVENT_ID, USER_ID)).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> eventRegistrationService.createRegistration(EVENT_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("会員登録_登録受付終了_例外スロー")
        void 会員登録_登録受付終了_例外スロー() {
            // Given
            CreateRegistrationRequest request = new CreateRegistrationRequest(TICKET_TYPE_ID, 1, null);
            EventEntity event = EventEntity.builder()
                    .scopeType(EventScopeType.TEAM).scopeId(10L).slug("closed")
                    .status(EventStatus.REGISTRATION_CLOSED)
                    .isPublic(false).isApprovalRequired(false).build();

            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);

            // When & Then
            assertThatThrownBy(() -> eventRegistrationService.createRegistration(EVENT_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("会員登録_チケット種別完売_例外スロー")
        void 会員登録_チケット種別完売_例外スロー() {
            // Given
            CreateRegistrationRequest request = new CreateRegistrationRequest(TICKET_TYPE_ID, 1, null);
            EventEntity event = createRegistrationOpenEvent();
            EventTicketTypeEntity soldOutType = EventTicketTypeEntity.builder()
                    .eventId(EVENT_ID).name("完売チケット").maxQuantity(10).build();
            // Manually set issuedCount to maxQuantity by incrementing
            for (int i = 0; i < 10; i++) {
                soldOutType.incrementIssuedCount(1);
            }

            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(registrationRepository.existsByEventIdAndUserId(EVENT_ID, USER_ID)).willReturn(false);
            given(ticketTypeRepository.findById(TICKET_TYPE_ID)).willReturn(Optional.of(soldOutType));

            // When & Then
            assertThatThrownBy(() -> eventRegistrationService.createRegistration(EVENT_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // createGuestRegistration
    // ========================================

    @Nested
    @DisplayName("createGuestRegistration")
    class CreateGuestRegistration {

        @Test
        @DisplayName("ゲスト登録_正常_即承認")
        void ゲスト登録_正常_即承認() {
            // Given
            GuestRegistrationRequest request = new GuestRegistrationRequest(
                    TICKET_TYPE_ID, "ゲスト太郎", "guest@example.com", null, 1, null, "valid-token"
            );
            EventEntity event = createRegistrationOpenEvent();
            EventGuestInviteTokenEntity token = EventGuestInviteTokenEntity.builder()
                    .eventId(EVENT_ID).token("valid-token").maxUses(10)
                    .expiresAt(LocalDateTime.now().plusDays(7)).createdBy(USER_ID).build();
            EventTicketTypeEntity ticketType = createTicketType();
            EventRegistrationEntity savedEntity = EventRegistrationEntity.builder()
                    .eventId(EVENT_ID).ticketTypeId(TICKET_TYPE_ID)
                    .guestName("ゲスト太郎").guestEmail("guest@example.com")
                    .status(RegistrationStatus.APPROVED).quantity(1).build();
            RegistrationResponse response = createRegistrationResponse("APPROVED");

            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(inviteTokenRepository.findByToken("valid-token")).willReturn(Optional.of(token));
            given(ticketTypeRepository.findById(TICKET_TYPE_ID)).willReturn(Optional.of(ticketType));
            given(registrationRepository.save(any(EventRegistrationEntity.class))).willReturn(savedEntity);
            given(inviteTokenRepository.save(any(EventGuestInviteTokenEntity.class))).willReturn(token);
            given(ticketTypeRepository.save(any(EventTicketTypeEntity.class))).willReturn(ticketType);
            given(eventMapper.toRegistrationResponse(savedEntity)).willReturn(response);

            // When
            RegistrationResponse result = eventRegistrationService.createGuestRegistration(EVENT_ID, request);

            // Then
            assertThat(result.getStatus()).isEqualTo("APPROVED");
            verify(ticketService).issueTickets(any(), any(), eq(1));
        }

        @Test
        @DisplayName("ゲスト登録_無効トークン_例外スロー")
        void ゲスト登録_無効トークン_例外スロー() {
            // Given
            GuestRegistrationRequest request = new GuestRegistrationRequest(
                    TICKET_TYPE_ID, "ゲスト太郎", "guest@example.com", null, 1, null, "invalid-token"
            );
            EventEntity event = createRegistrationOpenEvent();

            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(inviteTokenRepository.findByToken("invalid-token")).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> eventRegistrationService.createGuestRegistration(EVENT_ID, request))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("ゲスト登録_別イベントのトークン_例外スロー")
        void ゲスト登録_別イベントのトークン_例外スロー() {
            // Given
            GuestRegistrationRequest request = new GuestRegistrationRequest(
                    TICKET_TYPE_ID, "ゲスト太郎", "guest@example.com", null, 1, null, "other-event-token"
            );
            EventEntity event = createRegistrationOpenEvent();
            EventGuestInviteTokenEntity token = EventGuestInviteTokenEntity.builder()
                    .eventId(999L).token("other-event-token").maxUses(10)
                    .expiresAt(LocalDateTime.now().plusDays(7)).createdBy(USER_ID).build();

            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(inviteTokenRepository.findByToken("other-event-token")).willReturn(Optional.of(token));

            // When & Then
            assertThatThrownBy(() -> eventRegistrationService.createGuestRegistration(EVENT_ID, request))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // approveRegistration
    // ========================================

    @Nested
    @DisplayName("approveRegistration")
    class ApproveRegistration {

        @Test
        @DisplayName("登録承認_PENDING_正常承認")
        void 登録承認_PENDING_正常承認() {
            // Given
            EventRegistrationEntity entity = createPendingRegistration();
            EventTicketTypeEntity ticketType = createTicketType();
            EventEntity event = createRegistrationOpenEvent();
            RegistrationResponse response = createRegistrationResponse("APPROVED");

            given(registrationRepository.findById(REGISTRATION_ID)).willReturn(Optional.of(entity));
            given(ticketTypeRepository.findById(TICKET_TYPE_ID)).willReturn(Optional.of(ticketType));
            given(registrationRepository.save(any(EventRegistrationEntity.class))).willReturn(entity);
            given(ticketTypeRepository.save(any(EventTicketTypeEntity.class))).willReturn(ticketType);
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(eventMapper.toRegistrationResponse(entity)).willReturn(response);

            // When
            RegistrationResponse result = eventRegistrationService.approveRegistration(REGISTRATION_ID, USER_ID);

            // Then
            assertThat(result.getStatus()).isEqualTo("APPROVED");
            verify(ticketService).issueTickets(any(), any(), eq(1));
        }

        @Test
        @DisplayName("登録承認_APPROVED_例外スロー")
        void 登録承認_APPROVED_例外スロー() {
            // Given
            EventRegistrationEntity entity = createApprovedRegistration();
            given(registrationRepository.findById(REGISTRATION_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> eventRegistrationService.approveRegistration(REGISTRATION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // rejectRegistration
    // ========================================

    @Nested
    @DisplayName("rejectRegistration")
    class RejectRegistration {

        @Test
        @DisplayName("登録却下_PENDING_正常却下")
        void 登録却下_PENDING_正常却下() {
            // Given
            EventRegistrationEntity entity = createPendingRegistration();
            RegistrationResponse response = createRegistrationResponse("REJECTED");

            given(registrationRepository.findById(REGISTRATION_ID)).willReturn(Optional.of(entity));
            given(registrationRepository.save(any(EventRegistrationEntity.class))).willReturn(entity);
            given(eventMapper.toRegistrationResponse(entity)).willReturn(response);

            // When
            RegistrationResponse result = eventRegistrationService.rejectRegistration(REGISTRATION_ID, USER_ID);

            // Then
            assertThat(result.getStatus()).isEqualTo("REJECTED");
        }

        @Test
        @DisplayName("登録却下_APPROVED_例外スロー")
        void 登録却下_APPROVED_例外スロー() {
            // Given
            EventRegistrationEntity entity = createApprovedRegistration();
            given(registrationRepository.findById(REGISTRATION_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> eventRegistrationService.rejectRegistration(REGISTRATION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // cancelRegistration
    // ========================================

    @Nested
    @DisplayName("cancelRegistration")
    class CancelRegistration {

        @Test
        @DisplayName("登録キャンセル_APPROVED_正常キャンセル")
        void 登録キャンセル_APPROVED_正常キャンセル() {
            // Given
            EventRegistrationEntity entity = createApprovedRegistration();
            RegistrationResponse response = createRegistrationResponse("CANCELLED");

            given(registrationRepository.findById(REGISTRATION_ID)).willReturn(Optional.of(entity));
            given(registrationRepository.save(any(EventRegistrationEntity.class))).willReturn(entity);
            given(eventMapper.toRegistrationResponse(entity)).willReturn(response);

            // When
            RegistrationResponse result = eventRegistrationService.cancelRegistration(REGISTRATION_ID, "都合が悪くなった");

            // Then
            assertThat(result.getStatus()).isEqualTo("CANCELLED");
            verify(ticketService).cancelTicketsByRegistration(REGISTRATION_ID);
        }

        @Test
        @DisplayName("登録キャンセル_CANCELLED_例外スロー")
        void 登録キャンセル_CANCELLED_例外スロー() {
            // Given
            EventRegistrationEntity entity = EventRegistrationEntity.builder()
                    .eventId(EVENT_ID).userId(USER_ID).ticketTypeId(TICKET_TYPE_ID)
                    .status(RegistrationStatus.CANCELLED).quantity(1).build();

            given(registrationRepository.findById(REGISTRATION_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> eventRegistrationService.cancelRegistration(REGISTRATION_ID, "理由"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // waitlistRegistration
    // ========================================

    @Nested
    @DisplayName("waitlistRegistration")
    class WaitlistRegistration {

        @Test
        @DisplayName("キャンセル待ち_PENDING_正常変更")
        void キャンセル待ち_PENDING_正常変更() {
            // Given
            EventRegistrationEntity entity = createPendingRegistration();
            RegistrationResponse response = createRegistrationResponse("WAITLISTED");

            given(registrationRepository.findById(REGISTRATION_ID)).willReturn(Optional.of(entity));
            given(registrationRepository.save(any(EventRegistrationEntity.class))).willReturn(entity);
            given(eventMapper.toRegistrationResponse(entity)).willReturn(response);

            // When
            RegistrationResponse result = eventRegistrationService.waitlistRegistration(REGISTRATION_ID);

            // Then
            assertThat(result.getStatus()).isEqualTo("WAITLISTED");
        }

        @Test
        @DisplayName("キャンセル待ち_APPROVED_例外スロー")
        void キャンセル待ち_APPROVED_例外スロー() {
            // Given
            EventRegistrationEntity entity = createApprovedRegistration();
            given(registrationRepository.findById(REGISTRATION_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> eventRegistrationService.waitlistRegistration(REGISTRATION_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
