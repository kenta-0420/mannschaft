package com.mannschaft.app.event;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.dto.CreateTicketTypeRequest;
import com.mannschaft.app.event.dto.TicketTypeResponse;
import com.mannschaft.app.event.dto.UpdateTicketTypeRequest;
import com.mannschaft.app.event.entity.EventTicketTypeEntity;
import com.mannschaft.app.event.repository.EventTicketTypeRepository;
import com.mannschaft.app.event.service.EventTicketTypeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link EventTicketTypeService} の単体テスト。
 * チケット種別のCRUDを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventTicketTypeService 単体テスト")
class EventTicketTypeServiceTest {

    @Mock
    private EventTicketTypeRepository ticketTypeRepository;

    @Mock
    private EventMapper eventMapper;

    @InjectMocks
    private EventTicketTypeService eventTicketTypeService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long EVENT_ID = 1L;
    private static final Long TICKET_TYPE_ID = 10L;

    private EventTicketTypeEntity createTicketTypeEntity() {
        return EventTicketTypeEntity.builder()
                .eventId(EVENT_ID)
                .name("一般チケット")
                .description("一般参加者向け")
                .price(BigDecimal.valueOf(1000))
                .currency("JPY")
                .maxQuantity(100)
                .build();
    }

    private TicketTypeResponse createTicketTypeResponse() {
        return new TicketTypeResponse(
                TICKET_TYPE_ID, EVENT_ID, "一般チケット", "一般参加者向け",
                BigDecimal.valueOf(1000), "JPY", 100, 0,
                "MEMBER_PLUS", true, 0,
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    // ========================================
    // listTicketTypes
    // ========================================

    @Nested
    @DisplayName("listTicketTypes")
    class ListTicketTypes {

        @Test
        @DisplayName("チケット種別一覧取得_正常_リスト返却")
        void チケット種別一覧取得_正常_リスト返却() {
            // Given
            EventTicketTypeEntity entity = createTicketTypeEntity();
            TicketTypeResponse response = createTicketTypeResponse();

            given(ticketTypeRepository.findByEventIdOrderBySortOrder(EVENT_ID))
                    .willReturn(List.of(entity));
            given(eventMapper.toTicketTypeResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<TicketTypeResponse> result = eventTicketTypeService.listTicketTypes(EVENT_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("一般チケット");
        }
    }

    // ========================================
    // createTicketType
    // ========================================

    @Nested
    @DisplayName("createTicketType")
    class CreateTicketType {

        @Test
        @DisplayName("チケット種別作成_正常_レスポンス返却")
        void チケット種別作成_正常_レスポンス返却() {
            // Given
            CreateTicketTypeRequest request = new CreateTicketTypeRequest(
                    "一般チケット", "一般参加者向け", BigDecimal.valueOf(1000),
                    "JPY", 100, null, null
            );
            EventTicketTypeEntity savedEntity = createTicketTypeEntity();
            TicketTypeResponse response = createTicketTypeResponse();

            given(ticketTypeRepository.countByEventId(EVENT_ID)).willReturn(0L);
            given(ticketTypeRepository.save(any(EventTicketTypeEntity.class))).willReturn(savedEntity);
            given(eventMapper.toTicketTypeResponse(savedEntity)).willReturn(response);

            // When
            TicketTypeResponse result = eventTicketTypeService.createTicketType(EVENT_ID, request);

            // Then
            assertThat(result.getName()).isEqualTo("一般チケット");
            verify(ticketTypeRepository).save(any(EventTicketTypeEntity.class));
        }

        @Test
        @DisplayName("チケット種別作成_上限到達_例外スロー")
        void チケット種別作成_上限到達_例外スロー() {
            // Given
            CreateTicketTypeRequest request = new CreateTicketTypeRequest(
                    "超過チケット", null, null, null, null, null, null
            );
            given(ticketTypeRepository.countByEventId(EVENT_ID)).willReturn(20L);

            // When & Then
            assertThatThrownBy(() -> eventTicketTypeService.createTicketType(EVENT_ID, request))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("チケット種別作成_デフォルト値適用_レスポンス返却")
        void チケット種別作成_デフォルト値適用_レスポンス返却() {
            // Given
            CreateTicketTypeRequest request = new CreateTicketTypeRequest(
                    "無料チケット", null, null, null, null, null, null
            );
            EventTicketTypeEntity savedEntity = EventTicketTypeEntity.builder()
                    .eventId(EVENT_ID).name("無料チケット").build();
            TicketTypeResponse response = new TicketTypeResponse(
                    TICKET_TYPE_ID, EVENT_ID, "無料チケット", null,
                    BigDecimal.ZERO, "JPY", null, 0,
                    "MEMBER_PLUS", true, 0,
                    LocalDateTime.now(), LocalDateTime.now()
            );

            given(ticketTypeRepository.countByEventId(EVENT_ID)).willReturn(0L);
            given(ticketTypeRepository.save(any(EventTicketTypeEntity.class))).willReturn(savedEntity);
            given(eventMapper.toTicketTypeResponse(savedEntity)).willReturn(response);

            // When
            TicketTypeResponse result = eventTicketTypeService.createTicketType(EVENT_ID, request);

            // Then
            assertThat(result.getName()).isEqualTo("無料チケット");
        }
    }

    // ========================================
    // updateTicketType
    // ========================================

    @Nested
    @DisplayName("updateTicketType")
    class UpdateTicketType {

        @Test
        @DisplayName("チケット種別更新_正常_findByIdと同一インスタンスがsaveされid保持")
        void チケット種別更新_正常_findByIdと同一インスタンスがsaveされid保持() {
            // Given
            UpdateTicketTypeRequest request = new UpdateTicketTypeRequest(
                    "VIPチケット", "VIP参加者向け", BigDecimal.valueOf(5000),
                    "JPY", 50, null, true, 1
            );
            EventTicketTypeEntity entity = createTicketTypeEntity();
            TicketTypeResponse response = new TicketTypeResponse(
                    TICKET_TYPE_ID, EVENT_ID, "VIPチケット", "VIP参加者向け",
                    BigDecimal.valueOf(5000), "JPY", 50, 0,
                    "MEMBER_PLUS", true, 1,
                    LocalDateTime.now(), LocalDateTime.now()
            );

            given(ticketTypeRepository.findById(TICKET_TYPE_ID)).willReturn(Optional.of(entity));
            given(ticketTypeRepository.save(entity)).willReturn(entity);
            given(eventMapper.toTicketTypeResponse(entity)).willReturn(response);

            // When
            TicketTypeResponse result = eventTicketTypeService.updateTicketType(TICKET_TYPE_ID, request);

            // Then
            assertThat(result.getName()).isEqualTo("VIPチケット");
            // save に渡されるのが findById の同一インスタンスであることを確認（id=null INSERT 化バグ回帰防止）
            ArgumentCaptor<EventTicketTypeEntity> captor = ArgumentCaptor.forClass(EventTicketTypeEntity.class);
            verify(ticketTypeRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(entity);
        }

        @Test
        @DisplayName("チケット種別更新_フィールド変更_applyUpdateが正しく適用される")
        void チケット種別更新_フィールド変更_applyUpdateが正しく適用される() {
            // Given
            UpdateTicketTypeRequest request = new UpdateTicketTypeRequest(
                    "VIPチケット", null, BigDecimal.valueOf(5000),
                    null, null, null, false, null
            );
            EventTicketTypeEntity entity = createTicketTypeEntity();

            given(ticketTypeRepository.findById(TICKET_TYPE_ID)).willReturn(Optional.of(entity));
            given(ticketTypeRepository.save(entity)).willReturn(entity);
            given(eventMapper.toTicketTypeResponse(entity)).willReturn(createTicketTypeResponse());

            // When
            eventTicketTypeService.updateTicketType(TICKET_TYPE_ID, request);

            // Then: ミューテート後のフィールドが正しく更新されていることを確認
            assertThat(entity.getName()).isEqualTo("VIPチケット");
            assertThat(entity.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(5000));
            assertThat(entity.getIsActive()).isFalse();
            // null 指定フィールドは既存値を維持
            assertThat(entity.getDescription()).isEqualTo("一般参加者向け");
            assertThat(entity.getCurrency()).isEqualTo("JPY");
        }

        @Test
        @DisplayName("チケット種別更新_存在しない_例外スロー")
        void チケット種別更新_存在しない_例外スロー() {
            // Given
            UpdateTicketTypeRequest request = new UpdateTicketTypeRequest(
                    "VIPチケット", null, null, null, null, null, null, null
            );
            given(ticketTypeRepository.findById(TICKET_TYPE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> eventTicketTypeService.updateTicketType(TICKET_TYPE_ID, request))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // getTicketType
    // ========================================

    @Nested
    @DisplayName("getTicketType")
    class GetTicketType {

        @Test
        @DisplayName("チケット種別取得_正常_レスポンス返却")
        void チケット種別取得_正常_レスポンス返却() {
            // Given
            EventTicketTypeEntity entity = createTicketTypeEntity();
            TicketTypeResponse response = createTicketTypeResponse();

            given(ticketTypeRepository.findById(TICKET_TYPE_ID)).willReturn(Optional.of(entity));
            given(eventMapper.toTicketTypeResponse(entity)).willReturn(response);

            // When
            TicketTypeResponse result = eventTicketTypeService.getTicketType(TICKET_TYPE_ID);

            // Then
            assertThat(result.getName()).isEqualTo("一般チケット");
        }

        @Test
        @DisplayName("チケット種別取得_存在しない_例外スロー")
        void チケット種別取得_存在しない_例外スロー() {
            // Given
            given(ticketTypeRepository.findById(TICKET_TYPE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> eventTicketTypeService.getTicketType(TICKET_TYPE_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
