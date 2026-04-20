package com.mannschaft.app.event;

import com.mannschaft.app.event.dto.CheckinResponse;
import com.mannschaft.app.event.dto.EventDetailResponse;
import com.mannschaft.app.event.dto.EventResponse;
import com.mannschaft.app.event.dto.InviteTokenResponse;
import com.mannschaft.app.event.dto.RegistrationResponse;
import com.mannschaft.app.event.dto.TicketResponse;
import com.mannschaft.app.event.dto.TicketTypeResponse;
import com.mannschaft.app.event.dto.TimetableItemResponse;
import com.mannschaft.app.event.entity.EventCheckinEntity;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.entity.EventGuestInviteTokenEntity;
import com.mannschaft.app.event.entity.EventRegistrationEntity;
import com.mannschaft.app.event.entity.EventTicketEntity;
import com.mannschaft.app.event.entity.EventTicketTypeEntity;
import com.mannschaft.app.event.entity.EventTimetableItemEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EventMapper} の単体テスト。
 * MapStruct生成実装によるEntity→DTO変換を検証する。
 */
@DisplayName("EventMapper 単体テスト")
class EventMapperTest {

    private final EventMapper mapper = Mappers.getMapper(EventMapper.class);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 4, 1, 10, 0);

    // ========================================
    // toEventResponse
    // ========================================

    @Nested
    @DisplayName("toEventResponse")
    class ToEventResponse {

        @Test
        @DisplayName("正常系: EventEntity → EventResponse 変換")
        void eventEntityToEventResponse() {
            // given
            EventEntity entity = EventEntity.builder()
                    .scopeType(EventScopeType.TEAM)
                    .scopeId(10L)
                    .slug("test-event")
                    .status(EventStatus.PUBLISHED)
                    .visibility(EventVisibility.PUBLIC)
                    .isApprovalRequired(false)
                    .build();

            // when
            EventResponse result = mapper.toEventResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getScopeType()).isEqualTo("TEAM");
            assertThat(result.getStatus()).isEqualTo("PUBLISHED");
            assertThat(result.getSlug()).isEqualTo("test-event");
        }

        @Test
        @DisplayName("正常系: EventEntity リスト → EventResponse リスト変換")
        void eventEntityListToEventResponseList() {
            // given
            EventEntity entity1 = EventEntity.builder()
                    .scopeType(EventScopeType.ORGANIZATION)
                    .scopeId(5L)
                    .slug("event-1")
                    .status(EventStatus.DRAFT)
                    .visibility(EventVisibility.MEMBERS_ONLY)
                    .isApprovalRequired(true)
                    .build();
            EventEntity entity2 = EventEntity.builder()
                    .scopeType(EventScopeType.TEAM)
                    .scopeId(10L)
                    .slug("event-2")
                    .status(EventStatus.COMPLETED)
                    .visibility(EventVisibility.PUBLIC)
                    .isApprovalRequired(false)
                    .build();

            // when
            List<EventResponse> result = mapper.toEventResponseList(List.of(entity1, entity2));

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getScopeType()).isEqualTo("ORGANIZATION");
            assertThat(result.get(0).getStatus()).isEqualTo("DRAFT");
            assertThat(result.get(1).getScopeType()).isEqualTo("TEAM");
            assertThat(result.get(1).getStatus()).isEqualTo("COMPLETED");
        }
    }

    // ========================================
    // toEventDetailResponse
    // ========================================

    @Nested
    @DisplayName("toEventDetailResponse")
    class ToEventDetailResponse {

        @Test
        @DisplayName("正常系: EventEntity → EventDetailResponse 変換")
        void eventEntityToEventDetailResponse() {
            // given
            EventEntity entity = EventEntity.builder()
                    .scopeType(EventScopeType.TEAM)
                    .scopeId(20L)
                    .slug("detail-event")
                    .status(EventStatus.REGISTRATION_OPEN)
                    .visibility(EventVisibility.MEMBERS_ONLY)
                    .isApprovalRequired(true)
                    .venueName("会場名")
                    .registrationStartsAt(NOW)
                    .registrationEndsAt(NOW.plusDays(7))
                    .maxCapacity(100)
                    .build();

            // when
            EventDetailResponse result = mapper.toEventDetailResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getScopeType()).isEqualTo("TEAM");
            assertThat(result.getStatus()).isEqualTo("REGISTRATION_OPEN");
            assertThat(result.getSlug()).isEqualTo("detail-event");
            assertThat(result.getVenueName()).isEqualTo("会場名");
        }
    }

    // ========================================
    // toTicketTypeResponse
    // ========================================

    @Nested
    @DisplayName("toTicketTypeResponse")
    class ToTicketTypeResponse {

        @Test
        @DisplayName("正常系: EventTicketTypeEntity → TicketTypeResponse 変換")
        void ticketTypeEntityToTicketTypeResponse() {
            // given
            EventTicketTypeEntity entity = EventTicketTypeEntity.builder()
                    .eventId(1L)
                    .name("一般チケット")
                    .description("説明")
                    .price(BigDecimal.valueOf(3000))
                    .currency("JPY")
                    .maxQuantity(200)
                    .minRegistrationRole("MEMBER_PLUS")
                    .isActive(true)
                    .sortOrder(0)
                    .build();

            // when
            TicketTypeResponse result = mapper.toTicketTypeResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("一般チケット");
            assertThat(result.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(3000));
            assertThat(result.getCurrency()).isEqualTo("JPY");
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void ticketTypeEntityListToTicketTypeResponseList() {
            // given
            EventTicketTypeEntity entity = EventTicketTypeEntity.builder()
                    .eventId(1L)
                    .name("VIPチケット")
                    .price(BigDecimal.valueOf(10000))
                    .currency("JPY")
                    .isActive(true)
                    .sortOrder(1)
                    .build();

            // when
            List<TicketTypeResponse> result = mapper.toTicketTypeResponseList(List.of(entity));

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("VIPチケット");
        }
    }

    // ========================================
    // toRegistrationResponse
    // ========================================

    @Nested
    @DisplayName("toRegistrationResponse")
    class ToRegistrationResponse {

        @Test
        @DisplayName("正常系: EventRegistrationEntity → RegistrationResponse 変換")
        void registrationEntityToRegistrationResponse() {
            // given
            EventRegistrationEntity entity = EventRegistrationEntity.builder()
                    .eventId(1L)
                    .userId(100L)
                    .status(RegistrationStatus.APPROVED)
                    .build();

            // when
            RegistrationResponse result = mapper.toRegistrationResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("APPROVED");
            assertThat(result.getUserId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void registrationEntityListToRegistrationResponseList() {
            // given
            EventRegistrationEntity entity = EventRegistrationEntity.builder()
                    .eventId(1L)
                    .userId(200L)
                    .status(RegistrationStatus.PENDING)
                    .build();

            // when
            List<RegistrationResponse> result = mapper.toRegistrationResponseList(List.of(entity));

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("PENDING");
        }
    }

    // ========================================
    // toTicketResponse
    // ========================================

    @Nested
    @DisplayName("toTicketResponse")
    class ToTicketResponse {

        @Test
        @DisplayName("正常系: EventTicketEntity → TicketResponse 変換")
        void ticketEntityToTicketResponse() {
            // given
            EventTicketEntity entity = EventTicketEntity.builder()
                    .registrationId(10L)
                    .eventId(1L)
                    .ticketTypeId(5L)
                    .qrToken("qr-token-abc")
                    .ticketNumber("EVT001-0001")
                    .status(TicketStatus.VALID)
                    .build();

            // when
            TicketResponse result = mapper.toTicketResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("VALID");
            assertThat(result.getTicketNumber()).isEqualTo("EVT001-0001");
        }

        @Test
        @DisplayName("正常系: USED ステータスのチケット変換")
        void usedTicketEntityToTicketResponse() {
            // given
            EventTicketEntity entity = EventTicketEntity.builder()
                    .registrationId(20L)
                    .eventId(2L)
                    .ticketTypeId(3L)
                    .qrToken("used-qr-token")
                    .ticketNumber("EVT002-0002")
                    .status(TicketStatus.USED)
                    .build();

            // when
            List<TicketResponse> result = mapper.toTicketResponseList(List.of(entity));

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("USED");
        }
    }

    // ========================================
    // toCheckinResponse
    // ========================================

    @Nested
    @DisplayName("toCheckinResponse")
    class ToCheckinResponse {

        @Test
        @DisplayName("正常系: EventCheckinEntity → CheckinResponse 変換 (STAFF_SCAN)")
        void staffScanCheckinEntityToCheckinResponse() {
            // given
            EventCheckinEntity entity = EventCheckinEntity.builder()
                    .eventId(1L)
                    .ticketId(10L)
                    .checkinType(CheckinType.STAFF_SCAN)
                    .checkedInBy(999L)
                    .note("スタッフメモ")
                    .build();

            // when
            CheckinResponse result = mapper.toCheckinResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getCheckinType()).isEqualTo("STAFF_SCAN");
            assertThat(result.getCheckedInBy()).isEqualTo(999L);
        }

        @Test
        @DisplayName("正常系: SELF チェックイン変換")
        void selfCheckinEntityToCheckinResponse() {
            // given
            EventCheckinEntity entity = EventCheckinEntity.builder()
                    .eventId(1L)
                    .ticketId(20L)
                    .checkinType(CheckinType.SELF)
                    .build();

            // when
            List<CheckinResponse> result = mapper.toCheckinResponseList(List.of(entity));

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getCheckinType()).isEqualTo("SELF");
        }
    }

    // ========================================
    // toTimetableItemResponse
    // ========================================

    @Nested
    @DisplayName("toTimetableItemResponse")
    class ToTimetableItemResponse {

        @Test
        @DisplayName("正常系: EventTimetableItemEntity → TimetableItemResponse 変換")
        void timetableItemEntityToTimetableItemResponse() {
            // given
            EventTimetableItemEntity entity = EventTimetableItemEntity.builder()
                    .eventId(1L)
                    .title("オープニング")
                    .sortOrder(0)
                    .startAt(NOW)
                    .endAt(NOW.plusHours(1))
                    .build();

            // when
            TimetableItemResponse result = mapper.toTimetableItemResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getTitle()).isEqualTo("オープニング");
            assertThat(result.getSortOrder()).isEqualTo(0);
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void timetableItemEntityListToTimetableItemResponseList() {
            // given
            EventTimetableItemEntity e1 = EventTimetableItemEntity.builder()
                    .eventId(1L).title("セッション1").sortOrder(0).build();
            EventTimetableItemEntity e2 = EventTimetableItemEntity.builder()
                    .eventId(1L).title("セッション2").sortOrder(1).build();

            // when
            List<TimetableItemResponse> result = mapper.toTimetableItemResponseList(List.of(e1, e2));

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getTitle()).isEqualTo("セッション1");
            assertThat(result.get(1).getTitle()).isEqualTo("セッション2");
        }
    }

    // ========================================
    // toInviteTokenResponse
    // ========================================

    @Nested
    @DisplayName("toInviteTokenResponse")
    class ToInviteTokenResponse {

        @Test
        @DisplayName("正常系: EventGuestInviteTokenEntity → InviteTokenResponse 変換")
        void inviteTokenEntityToInviteTokenResponse() {
            // given
            EventGuestInviteTokenEntity entity = EventGuestInviteTokenEntity.builder()
                    .eventId(1L)
                    .token("invite-token-xyz")
                    .maxUses(10)
                    .isActive(true)
                    .expiresAt(NOW.plusDays(30))
                    .build();

            // when
            InviteTokenResponse result = mapper.toInviteTokenResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getToken()).isEqualTo("invite-token-xyz");
            assertThat(result.getMaxUses()).isEqualTo(10);
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void inviteTokenEntityListToInviteTokenResponseList() {
            // given
            EventGuestInviteTokenEntity entity = EventGuestInviteTokenEntity.builder()
                    .eventId(2L)
                    .token("token-abc")
                    .isActive(false)
                    .build();

            // when
            List<InviteTokenResponse> result = mapper.toInviteTokenResponseList(List.of(entity));

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getToken()).isEqualTo("token-abc");
        }
    }
}
