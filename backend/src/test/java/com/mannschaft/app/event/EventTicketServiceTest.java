package com.mannschaft.app.event;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.dto.TicketResponse;
import com.mannschaft.app.event.entity.EventRegistrationEntity;
import com.mannschaft.app.event.entity.EventTicketEntity;
import com.mannschaft.app.event.entity.EventTicketTypeEntity;
import com.mannschaft.app.event.repository.EventTicketRepository;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link EventTicketService} の単体テスト。
 * チケットの発行・照会・キャンセルを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventTicketService 単体テスト")
class EventTicketServiceTest {

    @Mock
    private EventTicketRepository ticketRepository;

    @Mock
    private EventMapper eventMapper;

    @InjectMocks
    private EventTicketService eventTicketService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long EVENT_ID = 1L;
    private static final Long TICKET_ID = 10L;
    private static final Long REGISTRATION_ID = 5L;
    private static final Long TICKET_TYPE_ID = 3L;
    private static final String QR_TOKEN = "test-qr-token";

    private EventTicketEntity createValidTicket() {
        return EventTicketEntity.builder()
                .registrationId(REGISTRATION_ID)
                .eventId(EVENT_ID)
                .ticketTypeId(TICKET_TYPE_ID)
                .qrToken(QR_TOKEN)
                .ticketNumber("EVT1-0001")
                .status(TicketStatus.VALID)
                .build();
    }

    private EventTicketEntity createCancelledTicket() {
        return EventTicketEntity.builder()
                .registrationId(REGISTRATION_ID)
                .eventId(EVENT_ID)
                .ticketTypeId(TICKET_TYPE_ID)
                .qrToken(QR_TOKEN)
                .ticketNumber("EVT1-0001")
                .status(TicketStatus.CANCELLED)
                .build();
    }

    private TicketResponse createTicketResponse() {
        return new TicketResponse(
                TICKET_ID, REGISTRATION_ID, EVENT_ID, TICKET_TYPE_ID,
                QR_TOKEN, "EVT1-0001", "VALID", null, null,
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    // ========================================
    // listTickets
    // ========================================

    @Nested
    @DisplayName("listTickets")
    class ListTickets {

        @Test
        @DisplayName("チケット一覧取得_正常_ページ返却")
        void チケット一覧取得_正常_ページ返却() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            EventTicketEntity entity = createValidTicket();
            Page<EventTicketEntity> page = new PageImpl<>(List.of(entity), pageable, 1);
            TicketResponse response = createTicketResponse();

            given(ticketRepository.findByEventIdOrderByCreatedAtDesc(EVENT_ID, pageable))
                    .willReturn(page);
            given(eventMapper.toTicketResponse(entity)).willReturn(response);

            // When
            Page<TicketResponse> result = eventTicketService.listTickets(EVENT_ID, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getTicketNumber()).isEqualTo("EVT1-0001");
        }
    }

    // ========================================
    // getTicket
    // ========================================

    @Nested
    @DisplayName("getTicket")
    class GetTicket {

        @Test
        @DisplayName("チケット取得_正常_レスポンス返却")
        void チケット取得_正常_レスポンス返却() {
            // Given
            EventTicketEntity entity = createValidTicket();
            TicketResponse response = createTicketResponse();

            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));
            given(eventMapper.toTicketResponse(entity)).willReturn(response);

            // When
            TicketResponse result = eventTicketService.getTicket(TICKET_ID);

            // Then
            assertThat(result.getQrToken()).isEqualTo(QR_TOKEN);
        }

        @Test
        @DisplayName("チケット取得_存在しない_例外スロー")
        void チケット取得_存在しない_例外スロー() {
            // Given
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> eventTicketService.getTicket(TICKET_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // getTicketByQrToken
    // ========================================

    @Nested
    @DisplayName("getTicketByQrToken")
    class GetTicketByQrToken {

        @Test
        @DisplayName("QRトークン検索_正常_レスポンス返却")
        void QRトークン検索_正常_レスポンス返却() {
            // Given
            EventTicketEntity entity = createValidTicket();
            TicketResponse response = createTicketResponse();

            given(ticketRepository.findByQrToken(QR_TOKEN)).willReturn(Optional.of(entity));
            given(eventMapper.toTicketResponse(entity)).willReturn(response);

            // When
            TicketResponse result = eventTicketService.getTicketByQrToken(QR_TOKEN);

            // Then
            assertThat(result.getQrToken()).isEqualTo(QR_TOKEN);
        }

        @Test
        @DisplayName("QRトークン検索_存在しない_例外スロー")
        void QRトークン検索_存在しない_例外スロー() {
            // Given
            given(ticketRepository.findByQrToken(QR_TOKEN)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> eventTicketService.getTicketByQrToken(QR_TOKEN))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // cancelTicket
    // ========================================

    @Nested
    @DisplayName("cancelTicket")
    class CancelTicket {

        @Test
        @DisplayName("チケットキャンセル_VALID_正常キャンセル")
        void チケットキャンセル_VALID_正常キャンセル() {
            // Given
            EventTicketEntity entity = createValidTicket();
            TicketResponse response = createTicketResponse();

            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));
            given(ticketRepository.save(any(EventTicketEntity.class))).willReturn(entity);
            given(eventMapper.toTicketResponse(entity)).willReturn(response);

            // When
            TicketResponse result = eventTicketService.cancelTicket(TICKET_ID);

            // Then
            assertThat(result).isNotNull();
            verify(ticketRepository).save(any(EventTicketEntity.class));
        }

        @Test
        @DisplayName("チケットキャンセル_CANCELLED_例外スロー")
        void チケットキャンセル_CANCELLED_例外スロー() {
            // Given
            EventTicketEntity entity = createCancelledTicket();
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> eventTicketService.cancelTicket(TICKET_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("チケットキャンセル_USED_例外スロー")
        void チケットキャンセル_USED_例外スロー() {
            // Given
            EventTicketEntity entity = EventTicketEntity.builder()
                    .registrationId(REGISTRATION_ID).eventId(EVENT_ID).ticketTypeId(TICKET_TYPE_ID)
                    .qrToken(QR_TOKEN).ticketNumber("EVT1-0001").status(TicketStatus.USED).build();

            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> eventTicketService.cancelTicket(TICKET_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // issueTickets
    // ========================================

    @Nested
    @DisplayName("issueTickets")
    class IssueTickets {

        @Test
        @DisplayName("チケット発行_複数枚_正常発行")
        void チケット発行_複数枚_正常発行() {
            // Given
            EventRegistrationEntity registration = EventRegistrationEntity.builder()
                    .eventId(EVENT_ID).userId(100L).ticketTypeId(TICKET_TYPE_ID)
                    .status(RegistrationStatus.APPROVED).quantity(3).build();
            EventTicketTypeEntity ticketType = EventTicketTypeEntity.builder()
                    .eventId(EVENT_ID).name("一般").price(BigDecimal.ZERO).currency("JPY").build();

            given(ticketRepository.countByEventIdAndStatus(eq(EVENT_ID), any(TicketStatus.class)))
                    .willReturn(0L);
            given(ticketRepository.save(any(EventTicketEntity.class))).willAnswer(inv -> inv.getArgument(0));

            // When
            eventTicketService.issueTickets(registration, ticketType, 3);

            // Then
            verify(ticketRepository, times(3)).save(any(EventTicketEntity.class));
        }
    }

    // ========================================
    // cancelTicketsByRegistration
    // ========================================

    @Nested
    @DisplayName("cancelTicketsByRegistration")
    class CancelTicketsByRegistration {

        @Test
        @DisplayName("登録チケット一括キャンセル_VALIDのみキャンセル")
        void 登録チケット一括キャンセル_VALIDのみキャンセル() {
            // Given
            EventTicketEntity validTicket = createValidTicket();
            EventTicketEntity usedTicket = EventTicketEntity.builder()
                    .registrationId(REGISTRATION_ID).eventId(EVENT_ID).ticketTypeId(TICKET_TYPE_ID)
                    .qrToken("used-token").ticketNumber("EVT1-0002").status(TicketStatus.USED).build();

            given(ticketRepository.findByRegistrationId(REGISTRATION_ID))
                    .willReturn(List.of(validTicket, usedTicket));
            given(ticketRepository.save(any(EventTicketEntity.class))).willAnswer(inv -> inv.getArgument(0));

            // When
            eventTicketService.cancelTicketsByRegistration(REGISTRATION_ID);

            // Then
            verify(ticketRepository, times(1)).save(any(EventTicketEntity.class));
        }

        @Test
        @DisplayName("登録チケット一括キャンセル_該当なし_saveなし")
        void 登録チケット一括キャンセル_該当なし_saveなし() {
            // Given
            given(ticketRepository.findByRegistrationId(REGISTRATION_ID)).willReturn(List.of());

            // When
            eventTicketService.cancelTicketsByRegistration(REGISTRATION_ID);

            // Then
            verify(ticketRepository, times(0)).save(any(EventTicketEntity.class));
        }
    }

    // ========================================
    // findTicketByQrTokenOrThrow
    // ========================================

    @Nested
    @DisplayName("findTicketByQrTokenOrThrow")
    class FindTicketByQrTokenOrThrow {

        @Test
        @DisplayName("QRトークンでエンティティ取得_正常_エンティティ返却")
        void QRトークンでエンティティ取得_正常_エンティティ返却() {
            // Given
            EventTicketEntity entity = createValidTicket();
            given(ticketRepository.findByQrToken(QR_TOKEN)).willReturn(Optional.of(entity));

            // When
            EventTicketEntity result = eventTicketService.findTicketByQrTokenOrThrow(QR_TOKEN);

            // Then
            assertThat(result.getQrToken()).isEqualTo(QR_TOKEN);
        }

        @Test
        @DisplayName("QRトークンでエンティティ取得_存在しない_例外スロー")
        void QRトークンでエンティティ取得_存在しない_例外スロー() {
            // Given
            given(ticketRepository.findByQrToken(QR_TOKEN)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> eventTicketService.findTicketByQrTokenOrThrow(QR_TOKEN))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
