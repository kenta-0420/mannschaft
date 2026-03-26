package com.mannschaft.app.queue;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.queue.dto.AdminTicketRequest;
import com.mannschaft.app.queue.dto.CreateTicketRequest;
import com.mannschaft.app.queue.dto.TicketResponse;
import com.mannschaft.app.queue.entity.QueueCounterEntity;
import com.mannschaft.app.queue.entity.QueueSettingsEntity;
import com.mannschaft.app.queue.entity.QueueTicketEntity;
import com.mannschaft.app.queue.repository.QueueSettingsRepository;
import com.mannschaft.app.queue.repository.QueueTicketRepository;
import com.mannschaft.app.queue.service.QueueCounterService;
import com.mannschaft.app.queue.service.QueueTicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link QueueTicketService} の単体テスト。
 * チケットの発行・状態遷移・一覧取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QueueTicketService 単体テスト")
class QueueTicketServiceTest {

    @Mock
    private QueueTicketRepository ticketRepository;

    @Mock
    private QueueSettingsRepository settingsRepository;

    @Mock
    private QueueCounterService counterService;

    @Mock
    private QueueMapper queueMapper;

    @InjectMocks
    private QueueTicketService queueTicketService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TICKET_ID = 1L;
    private static final Long COUNTER_ID = 10L;
    private static final Long CATEGORY_ID = 5L;
    private static final Long USER_ID = 100L;
    private static final Long SCOPE_ID = 50L;
    private static final QueueScopeType SCOPE_TYPE = QueueScopeType.TEAM;

    private QueueCounterEntity createActiveCounter() {
        return QueueCounterEntity.builder()
                .categoryId(CATEGORY_ID)
                .name("窓口1")
                .avgServiceMinutes((short) 10)
                .maxQueueSize((short) 50)
                .isActive(true)
                .isAccepting(true)
                .build();
    }

    private QueueTicketEntity createWaitingTicket() {
        return QueueTicketEntity.builder()
                .categoryId(CATEGORY_ID)
                .counterId(COUNTER_ID)
                .ticketNumber("Q001")
                .userId(USER_ID)
                .partySize((short) 1)
                .source(TicketSource.ONLINE)
                .status(TicketStatus.WAITING)
                .position(1)
                .issuedDate(LocalDate.now())
                .build();
    }

    private QueueTicketEntity createTicketWithStatus(TicketStatus status) {
        return QueueTicketEntity.builder()
                .categoryId(CATEGORY_ID)
                .counterId(COUNTER_ID)
                .ticketNumber("Q001")
                .userId(USER_ID)
                .partySize((short) 1)
                .source(TicketSource.ONLINE)
                .status(status)
                .position(1)
                .issuedDate(LocalDate.now())
                .build();
    }

    private TicketResponse createTicketResponse() {
        return new TicketResponse(
                TICKET_ID, CATEGORY_ID, COUNTER_ID, "Q001", USER_ID,
                null, (short) 1, "ONLINE", "WAITING", 1,
                (short) 0, null, null, null, null, null, null,
                false, null, null, null, LocalDate.now(), LocalDateTime.now()
        );
    }

    // ========================================
    // issueTicket
    // ========================================

    @Nested
    @DisplayName("issueTicket")
    class IssueTicket {

        @Test
        @DisplayName("チケット発行_正常_レスポンス返却")
        void チケット発行_正常_レスポンス返却() {
            // Given
            CreateTicketRequest request = new CreateTicketRequest(null, null, (short) 1, "ONLINE", null);
            QueueCounterEntity counter = createActiveCounter();
            QueueTicketEntity savedTicket = createWaitingTicket();
            TicketResponse response = createTicketResponse();

            given(counterService.findEntityOrThrow(COUNTER_ID)).willReturn(counter);
            given(ticketRepository.countActiveTicketsByUserIdAndIssuedDate(eq(USER_ID), any(LocalDate.class)))
                    .willReturn(0L);
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(ticketRepository.countByCounterIdAndIssuedDateAndStatus(eq(COUNTER_ID), any(LocalDate.class), eq(TicketStatus.WAITING)))
                    .willReturn(0L);
            given(ticketRepository.findMaxPositionByCounterIdAndIssuedDate(eq(COUNTER_ID), any(LocalDate.class)))
                    .willReturn(0);
            given(ticketRepository.countByCounterIdAndIssuedDate(eq(COUNTER_ID), any(LocalDate.class)))
                    .willReturn(0L);
            given(ticketRepository.save(any(QueueTicketEntity.class))).willReturn(savedTicket);
            given(queueMapper.toTicketResponse(savedTicket)).willReturn(response);

            // When
            TicketResponse result = queueTicketService.issueTicket(COUNTER_ID, request, USER_ID, SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result.getTicketNumber()).isEqualTo("Q001");
            verify(ticketRepository).save(any(QueueTicketEntity.class));
        }

        @Test
        @DisplayName("チケット発行_カウンター非アクティブ_例外スロー")
        void チケット発行_カウンター非アクティブ_例外スロー() {
            // Given
            CreateTicketRequest request = new CreateTicketRequest(null, null, null, null, null);
            QueueCounterEntity counter = QueueCounterEntity.builder()
                    .categoryId(CATEGORY_ID).name("窓口1")
                    .isActive(false).isAccepting(true)
                    .avgServiceMinutes((short) 10).maxQueueSize((short) 50)
                    .build();

            given(counterService.findEntityOrThrow(COUNTER_ID)).willReturn(counter);

            // When & Then
            assertThatThrownBy(() -> queueTicketService.issueTicket(COUNTER_ID, request, USER_ID, SCOPE_TYPE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("チケット発行_受付停止中_例外スロー")
        void チケット発行_受付停止中_例外スロー() {
            // Given
            CreateTicketRequest request = new CreateTicketRequest(null, null, null, null, null);
            QueueCounterEntity counter = QueueCounterEntity.builder()
                    .categoryId(CATEGORY_ID).name("窓口1")
                    .isActive(true).isAccepting(false)
                    .avgServiceMinutes((short) 10).maxQueueSize((short) 50)
                    .build();

            given(counterService.findEntityOrThrow(COUNTER_ID)).willReturn(counter);

            // When & Then
            assertThatThrownBy(() -> queueTicketService.issueTicket(COUNTER_ID, request, USER_ID, SCOPE_TYPE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("チケット発行_キュー上限到達_例外スロー")
        void チケット発行_キュー上限到達_例外スロー() {
            // Given
            CreateTicketRequest request = new CreateTicketRequest(null, null, null, null, null);
            QueueCounterEntity counter = createActiveCounter();

            given(counterService.findEntityOrThrow(COUNTER_ID)).willReturn(counter);
            given(ticketRepository.countActiveTicketsByUserIdAndIssuedDate(eq(USER_ID), any(LocalDate.class)))
                    .willReturn(0L);
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(ticketRepository.countByCounterIdAndIssuedDateAndStatus(eq(COUNTER_ID), any(LocalDate.class), eq(TicketStatus.WAITING)))
                    .willReturn(50L);

            // When & Then
            assertThatThrownBy(() -> queueTicketService.issueTicket(COUNTER_ID, request, USER_ID, SCOPE_TYPE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("チケット発行_アクティブチケット上限_例外スロー")
        void チケット発行_アクティブチケット上限_例外スロー() {
            // Given
            CreateTicketRequest request = new CreateTicketRequest(null, null, null, null, null);
            QueueCounterEntity counter = createActiveCounter();

            given(counterService.findEntityOrThrow(COUNTER_ID)).willReturn(counter);
            given(ticketRepository.countActiveTicketsByUserIdAndIssuedDate(eq(USER_ID), any(LocalDate.class)))
                    .willReturn(1L);
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> queueTicketService.issueTicket(COUNTER_ID, request, USER_ID, SCOPE_TYPE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("チケット発行_ゲスト_ゲスト受付不可_例外スロー")
        void チケット発行_ゲスト_ゲスト受付不可_例外スロー() {
            // Given
            CreateTicketRequest request = new CreateTicketRequest("ゲスト太郎", null, null, null, null);
            QueueCounterEntity counter = createActiveCounter();
            QueueSettingsEntity settings = QueueSettingsEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).allowGuestQueue(false).build();

            given(counterService.findEntityOrThrow(COUNTER_ID)).willReturn(counter);
            given(settingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(settings));

            // When & Then
            assertThatThrownBy(() -> queueTicketService.issueTicket(COUNTER_ID, request, null, SCOPE_TYPE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // listWaitingTickets
    // ========================================

    @Nested
    @DisplayName("listWaitingTickets")
    class ListWaitingTickets {

        @Test
        @DisplayName("待ちチケット一覧_正常_リスト返却")
        void 待ちチケット一覧_正常_リスト返却() {
            // Given
            QueueCounterEntity counter = createActiveCounter();
            QueueTicketEntity ticket = createWaitingTicket();
            TicketResponse response = createTicketResponse();

            given(counterService.findEntityOrThrow(COUNTER_ID)).willReturn(counter);
            given(ticketRepository.findByCounterIdAndIssuedDateAndStatusOrderByPositionAsc(
                    eq(COUNTER_ID), any(LocalDate.class), eq(TicketStatus.WAITING)))
                    .willReturn(List.of(ticket));
            given(queueMapper.toTicketResponseList(List.of(ticket))).willReturn(List.of(response));

            // When
            List<TicketResponse> result = queueTicketService.listWaitingTickets(COUNTER_ID);

            // Then
            assertThat(result).hasSize(1);
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
            QueueTicketEntity entity = createWaitingTicket();
            TicketResponse response = createTicketResponse();

            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));
            given(queueMapper.toTicketResponse(entity)).willReturn(response);

            // When
            TicketResponse result = queueTicketService.getTicket(TICKET_ID);

            // Then
            assertThat(result.getTicketNumber()).isEqualTo("Q001");
        }

        @Test
        @DisplayName("チケット取得_存在しない_例外スロー")
        void チケット取得_存在しない_例外スロー() {
            // Given
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> queueTicketService.getTicket(TICKET_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // cancelMyTicket
    // ========================================

    @Nested
    @DisplayName("cancelMyTicket")
    class CancelMyTicket {

        @Test
        @DisplayName("ユーザーキャンセル_WAITING_正常キャンセル")
        void ユーザーキャンセル_WAITING_正常キャンセル() {
            // Given
            QueueTicketEntity entity = createWaitingTicket();
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));
            given(ticketRepository.save(any(QueueTicketEntity.class))).willReturn(entity);

            // When
            queueTicketService.cancelMyTicket(TICKET_ID, USER_ID);

            // Then
            verify(ticketRepository).save(any(QueueTicketEntity.class));
        }

        @Test
        @DisplayName("ユーザーキャンセル_CALLED_正常キャンセル")
        void ユーザーキャンセル_CALLED_正常キャンセル() {
            // Given
            QueueTicketEntity entity = createTicketWithStatus(TicketStatus.CALLED);
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));
            given(ticketRepository.save(any(QueueTicketEntity.class))).willReturn(entity);

            // When
            queueTicketService.cancelMyTicket(TICKET_ID, USER_ID);

            // Then
            verify(ticketRepository).save(any(QueueTicketEntity.class));
        }

        @Test
        @DisplayName("ユーザーキャンセル_SERVING_例外スロー")
        void ユーザーキャンセル_SERVING_例外スロー() {
            // Given
            QueueTicketEntity entity = createTicketWithStatus(TicketStatus.SERVING);
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> queueTicketService.cancelMyTicket(TICKET_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("ユーザーキャンセル_COMPLETED_例外スロー")
        void ユーザーキャンセル_COMPLETED_例外スロー() {
            // Given
            QueueTicketEntity entity = createTicketWithStatus(TicketStatus.COMPLETED);
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> queueTicketService.cancelMyTicket(TICKET_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // adminAction
    // ========================================

    @Nested
    @DisplayName("adminAction")
    class AdminAction {

        @Test
        @DisplayName("管理者操作_CALL_正常呼び出し")
        void 管理者操作_CALL_正常呼び出し() {
            // Given
            AdminTicketRequest request = new AdminTicketRequest("CALL", null, null, null);
            QueueTicketEntity entity = createWaitingTicket();
            TicketResponse response = createTicketResponse();

            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));
            given(ticketRepository.save(any(QueueTicketEntity.class))).willReturn(entity);
            given(queueMapper.toTicketResponse(entity)).willReturn(response);

            // When
            TicketResponse result = queueTicketService.adminAction(TICKET_ID, request, USER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(ticketRepository).save(any(QueueTicketEntity.class));
        }

        @Test
        @DisplayName("管理者操作_START_SERVING_正常対応開始")
        void 管理者操作_START_SERVING_正常対応開始() {
            // Given
            AdminTicketRequest request = new AdminTicketRequest("START_SERVING", null, null, null);
            QueueTicketEntity entity = createTicketWithStatus(TicketStatus.CALLED);
            TicketResponse response = createTicketResponse();

            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));
            given(ticketRepository.save(any(QueueTicketEntity.class))).willReturn(entity);
            given(queueMapper.toTicketResponse(entity)).willReturn(response);

            // When
            TicketResponse result = queueTicketService.adminAction(TICKET_ID, request, USER_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("管理者操作_COMPLETE_正常完了")
        void 管理者操作_COMPLETE_正常完了() {
            // Given
            AdminTicketRequest request = new AdminTicketRequest("COMPLETE", (short) 15, null, null);
            QueueTicketEntity entity = createTicketWithStatus(TicketStatus.SERVING);
            TicketResponse response = createTicketResponse();

            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));
            given(ticketRepository.save(any(QueueTicketEntity.class))).willReturn(entity);
            given(queueMapper.toTicketResponse(entity)).willReturn(response);

            // When
            TicketResponse result = queueTicketService.adminAction(TICKET_ID, request, USER_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("管理者操作_CANCEL_正常キャンセル")
        void 管理者操作_CANCEL_正常キャンセル() {
            // Given
            AdminTicketRequest request = new AdminTicketRequest("CANCEL", null, null, null);
            QueueTicketEntity entity = createWaitingTicket();
            TicketResponse response = createTicketResponse();

            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));
            given(ticketRepository.save(any(QueueTicketEntity.class))).willReturn(entity);
            given(queueMapper.toTicketResponse(entity)).willReturn(response);

            // When
            TicketResponse result = queueTicketService.adminAction(TICKET_ID, request, USER_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("管理者操作_CANCEL_COMPLETED状態_例外スロー")
        void 管理者操作_CANCEL_COMPLETED状態_例外スロー() {
            // Given
            AdminTicketRequest request = new AdminTicketRequest("CANCEL", null, null, null);
            QueueTicketEntity entity = createTicketWithStatus(TicketStatus.COMPLETED);

            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> queueTicketService.adminAction(TICKET_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("管理者操作_NO_SHOW_正常不在処理")
        void 管理者操作_NO_SHOW_正常不在処理() {
            // Given
            AdminTicketRequest request = new AdminTicketRequest("NO_SHOW", null, null, null);
            QueueTicketEntity entity = createTicketWithStatus(TicketStatus.CALLED);
            TicketResponse response = createTicketResponse();

            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));
            given(ticketRepository.save(any(QueueTicketEntity.class))).willReturn(entity);
            given(queueMapper.toTicketResponse(entity)).willReturn(response);

            // When
            TicketResponse result = queueTicketService.adminAction(TICKET_ID, request, USER_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("管理者操作_HOLD_正常保留")
        void 管理者操作_HOLD_正常保留() {
            // Given
            AdminTicketRequest request = new AdminTicketRequest("HOLD", null, null, (short) 10);
            QueueTicketEntity entity = createTicketWithStatus(TicketStatus.CALLED);
            TicketResponse response = createTicketResponse();

            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));
            given(ticketRepository.save(any(QueueTicketEntity.class))).willReturn(entity);
            given(queueMapper.toTicketResponse(entity)).willReturn(response);

            // When
            TicketResponse result = queueTicketService.adminAction(TICKET_ID, request, USER_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("管理者操作_RECALL_NO_SHOWから再呼出し")
        void 管理者操作_RECALL_NO_SHOWから再呼出し() {
            // Given
            AdminTicketRequest request = new AdminTicketRequest("RECALL", null, null, null);
            QueueTicketEntity entity = createTicketWithStatus(TicketStatus.NO_SHOW);
            TicketResponse response = createTicketResponse();

            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));
            given(ticketRepository.save(any(QueueTicketEntity.class))).willReturn(entity);
            given(queueMapper.toTicketResponse(entity)).willReturn(response);

            // When
            TicketResponse result = queueTicketService.adminAction(TICKET_ID, request, USER_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("管理者操作_RECALL_WAITING状態_例外スロー")
        void 管理者操作_RECALL_WAITING状態_例外スロー() {
            // Given
            AdminTicketRequest request = new AdminTicketRequest("RECALL", null, null, null);
            QueueTicketEntity entity = createWaitingTicket();

            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> queueTicketService.adminAction(TICKET_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("管理者操作_不正アクション_例外スロー")
        void 管理者操作_不正アクション_例外スロー() {
            // Given
            AdminTicketRequest request = new AdminTicketRequest("INVALID_ACTION", null, null, null);
            QueueTicketEntity entity = createWaitingTicket();

            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> queueTicketService.adminAction(TICKET_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // callNext
    // ========================================

    @Nested
    @DisplayName("callNext")
    class CallNext {

        @Test
        @DisplayName("次呼び出し_待ちあり_チケット返却")
        void 次呼び出し_待ちあり_チケット返却() {
            // Given
            QueueCounterEntity counter = createActiveCounter();
            QueueTicketEntity entity = createWaitingTicket();
            TicketResponse response = createTicketResponse();

            given(counterService.findEntityOrThrow(COUNTER_ID)).willReturn(counter);
            given(ticketRepository.findByCounterIdAndIssuedDateAndStatusOrderByPositionAsc(
                    eq(COUNTER_ID), any(LocalDate.class), eq(TicketStatus.WAITING)))
                    .willReturn(List.of(entity));
            given(ticketRepository.save(any(QueueTicketEntity.class))).willReturn(entity);
            given(queueMapper.toTicketResponse(entity)).willReturn(response);

            // When
            TicketResponse result = queueTicketService.callNext(COUNTER_ID, USER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(ticketRepository).save(any(QueueTicketEntity.class));
        }

        @Test
        @DisplayName("次呼び出し_待ちなし_null返却")
        void 次呼び出し_待ちなし_null返却() {
            // Given
            QueueCounterEntity counter = createActiveCounter();

            given(counterService.findEntityOrThrow(COUNTER_ID)).willReturn(counter);
            given(ticketRepository.findByCounterIdAndIssuedDateAndStatusOrderByPositionAsc(
                    eq(COUNTER_ID), any(LocalDate.class), eq(TicketStatus.WAITING)))
                    .willReturn(List.of());

            // When
            TicketResponse result = queueTicketService.callNext(COUNTER_ID, USER_ID);

            // Then
            assertThat(result).isNull();
        }
    }

    // ========================================
    // listMyTickets
    // ========================================

    @Nested
    @DisplayName("listMyTickets")
    class ListMyTickets {

        @Test
        @DisplayName("自分のチケット一覧_正常_リスト返却")
        void 自分のチケット一覧_正常_リスト返却() {
            // Given
            QueueTicketEntity ticket = createWaitingTicket();
            TicketResponse response = createTicketResponse();

            given(ticketRepository.findByUserIdAndIssuedDateOrderByCreatedAtDesc(eq(USER_ID), any(LocalDate.class)))
                    .willReturn(List.of(ticket));
            given(queueMapper.toTicketResponseList(List.of(ticket))).willReturn(List.of(response));

            // When
            List<TicketResponse> result = queueTicketService.listMyTickets(USER_ID);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // listCategoryTickets
    // ========================================

    @Nested
    @DisplayName("listCategoryTickets")
    class ListCategoryTickets {

        @Test
        @DisplayName("カテゴリチケット一覧_正常_リスト返却")
        void カテゴリチケット一覧_正常_リスト返却() {
            // Given
            given(ticketRepository.findByCategoryIdAndIssuedDateAndStatusOrderByPositionAsc(
                    eq(CATEGORY_ID), any(LocalDate.class), eq(TicketStatus.WAITING)))
                    .willReturn(List.of());
            given(queueMapper.toTicketResponseList(List.of())).willReturn(List.of());

            // When
            List<TicketResponse> result = queueTicketService.listCategoryTickets(CATEGORY_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }
}
