package com.mannschaft.app.event;

import com.mannschaft.app.common.BusinessException;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link EventRegistrationService} の追加単体テスト。
 * getRegistration / キャパシティ超過 / REJECTED状態のキャンセル / ゲスト登録追加ケース を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventRegistrationService 追加単体テスト")
class EventRegistrationServiceAdditionalTest {

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

    private static final Long EVENT_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long REGISTRATION_ID = 10L;
    private static final Long TICKET_TYPE_ID = 5L;

    private EventEntity createRegistrationOpenEvent(int maxCapacity) {
        return EventEntity.builder()
                .scopeType(EventScopeType.TEAM)
                .scopeId(10L)
                .slug("test-event")
                .status(EventStatus.REGISTRATION_OPEN)
                .visibility(com.mannschaft.app.event.entity.EventVisibility.MEMBERS_ONLY)
                .isApprovalRequired(false)
                .maxCapacity(maxCapacity)
                .build();
    }

    private EventTicketTypeEntity createTicketType(int maxQty) {
        return EventTicketTypeEntity.builder()
                .eventId(EVENT_ID)
                .name("一般チケット")
                .price(BigDecimal.ZERO)
                .currency("JPY")
                .maxQuantity(maxQty)
                .build();
    }

    // ========================================
    // getRegistration
    // ========================================

    @Nested
    @DisplayName("getRegistration")
    class GetRegistration {

        @Test
        @DisplayName("正常系: 参加登録詳細を取得できる")
        void 参加登録詳細を取得できる() {
            // Given
            EventRegistrationEntity entity = EventRegistrationEntity.builder()
                    .eventId(EVENT_ID)
                    .userId(USER_ID)
                    .ticketTypeId(TICKET_TYPE_ID)
                    .status(RegistrationStatus.APPROVED)
                    .quantity(1)
                    .build();
            RegistrationResponse response = new RegistrationResponse(
                    REGISTRATION_ID, EVENT_ID, USER_ID, TICKET_TYPE_ID,
                    null, null, null, "APPROVED", 1, null,
                    null, null, null, null, null,
                    LocalDateTime.now(), LocalDateTime.now()
            );

            given(registrationRepository.findById(REGISTRATION_ID)).willReturn(Optional.of(entity));
            given(eventMapper.toRegistrationResponse(entity)).willReturn(response);

            // When
            RegistrationResponse result = eventRegistrationService.getRegistration(REGISTRATION_ID);

            // Then
            assertThat(result.getStatus()).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("異常系: 参加登録不在で例外スロー")
        void 参加登録不在で例外スロー() {
            // Given
            given(registrationRepository.findById(REGISTRATION_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> eventRegistrationService.getRegistration(REGISTRATION_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // createRegistration - キャパシティ超過
    // ========================================

    @Nested
    @DisplayName("createRegistration キャパシティ超過")
    class CreateRegistrationCapacity {

        @Test
        @DisplayName("会員登録_キャパシティ満杯_例外スロー")
        void 会員登録_キャパシティ満杯_例外スロー() {
            // Given
            com.mannschaft.app.event.dto.CreateRegistrationRequest request =
                    new com.mannschaft.app.event.dto.CreateRegistrationRequest(TICKET_TYPE_ID, 1, null);

            // maxCapacity=5, registrationCount=0の状態でincrementして5にする
            EventEntity event = EventEntity.builder()
                    .scopeType(EventScopeType.TEAM).scopeId(10L).slug("full-event")
                    .status(EventStatus.REGISTRATION_OPEN)
                    .visibility(com.mannschaft.app.event.entity.EventVisibility.MEMBERS_ONLY)
                    .isApprovalRequired(false)
                    .maxCapacity(5)
                    .build();
            // 5回インクリメントしてfull状態にする
            for (int i = 0; i < 5; i++) {
                event.incrementRegistrationCount();
            }

            EventTicketTypeEntity ticketType = createTicketType(100);

            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(registrationRepository.existsByEventIdAndUserId(EVENT_ID, USER_ID)).willReturn(false);
            given(ticketTypeRepository.findById(TICKET_TYPE_ID)).willReturn(Optional.of(ticketType));

            // When & Then
            assertThatThrownBy(() -> eventRegistrationService.createRegistration(EVENT_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // cancelRegistration - REJECTED状態
    // ========================================

    @Nested
    @DisplayName("cancelRegistration REJECTED状態")
    class CancelRegistrationRejected {

        @Test
        @DisplayName("登録キャンセル_REJECTED_例外スロー")
        void 登録キャンセル_REJECTED_例外スロー() {
            // Given
            EventRegistrationEntity entity = EventRegistrationEntity.builder()
                    .eventId(EVENT_ID).userId(USER_ID).ticketTypeId(TICKET_TYPE_ID)
                    .status(RegistrationStatus.REJECTED).quantity(1).build();

            given(registrationRepository.findById(REGISTRATION_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> eventRegistrationService.cancelRegistration(REGISTRATION_ID, "理由"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // ゲスト登録 - トークン有効期限切れ
    // ========================================

    @Nested
    @DisplayName("createGuestRegistration トークン有効期限切れ")
    class CreateGuestRegistrationExpiredToken {

        @Test
        @DisplayName("ゲスト登録_使用上限超過のトークン_例外スロー")
        void ゲスト登録_使用上限超過のトークン_例外スロー() {
            // Given
            com.mannschaft.app.event.dto.GuestRegistrationRequest request =
                    new com.mannschaft.app.event.dto.GuestRegistrationRequest(
                            TICKET_TYPE_ID, "ゲスト", "guest@example.com", null, 1, null, "used-token"
                    );

            EventEntity event = createRegistrationOpenEvent(100);

            // maxUses=1で usedCount=1 にしてisUsable=falseにする
            EventGuestInviteTokenEntity token = EventGuestInviteTokenEntity.builder()
                    .eventId(EVENT_ID).token("used-token").maxUses(1)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .createdBy(USER_ID).build();
            token.incrementUsedCount(); // 1回使用済み → maxUses=1なのでisUsable=false

            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(inviteTokenRepository.findByToken("used-token")).willReturn(Optional.of(token));

            // When & Then
            assertThatThrownBy(() -> eventRegistrationService.createGuestRegistration(EVENT_ID, request))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
