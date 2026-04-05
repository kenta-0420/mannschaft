package com.mannschaft.app.queue;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.queue.controller.QueueCategoryController;
import com.mannschaft.app.queue.controller.QueueCounterController;
import com.mannschaft.app.queue.controller.QueueQrCodeController;
import com.mannschaft.app.queue.controller.QueueSettingsController;
import com.mannschaft.app.queue.controller.QueueStatusController;
import com.mannschaft.app.queue.controller.QueueTicketController;
import com.mannschaft.app.queue.dto.AdminTicketRequest;
import com.mannschaft.app.queue.dto.CategoryResponse;
import com.mannschaft.app.queue.dto.CounterResponse;
import com.mannschaft.app.queue.dto.CreateCategoryRequest;
import com.mannschaft.app.queue.dto.CreateCounterRequest;
import com.mannschaft.app.queue.dto.CreateQrCodeRequest;
import com.mannschaft.app.queue.dto.CreateTicketRequest;
import com.mannschaft.app.queue.dto.QrCodeResponse;
import com.mannschaft.app.queue.dto.QueueSettingsRequest;
import com.mannschaft.app.queue.dto.QueueStatusResponse;
import com.mannschaft.app.queue.dto.SettingsResponse;
import com.mannschaft.app.queue.dto.TicketResponse;
import com.mannschaft.app.queue.dto.UpdateCategoryRequest;
import com.mannschaft.app.queue.dto.UpdateCounterRequest;
import com.mannschaft.app.queue.service.QueueCategoryService;
import com.mannschaft.app.queue.service.QueueCounterService;
import com.mannschaft.app.queue.service.QueueQrCodeService;
import com.mannschaft.app.queue.service.QueueSettingsService;
import com.mannschaft.app.queue.service.QueueStatsService;
import com.mannschaft.app.queue.service.QueueTicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * Queue モジュール コントローラーの単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Queueコントローラー 単体テスト")
class QueueControllerTest {

    private static final Long TEAM_ID = 10L;
    private static final Long CATEGORY_ID = 1L;
    private static final Long COUNTER_ID = 2L;
    private static final Long TICKET_ID = 3L;
    private static final Long QR_CODE_ID = 4L;
    private static final Long USER_ID = 5L;

    // ========================================
    // TicketResponse ヘルパー
    // ========================================

    private TicketResponse createTicketResponse() {
        return new TicketResponse(
                TICKET_ID, CATEGORY_ID, COUNTER_ID, "A001",
                USER_ID, null, (short) 1, "WALK_IN", "WAITING",
                1, (short) 15, null, null, null, null, null, null, false,
                null, null, null, LocalDate.now(), LocalDateTime.now()
        );
    }

    private CategoryResponse createCategoryResponse() {
        return new CategoryResponse(
                CATEGORY_ID, "TEAM", TEAM_ID, "一般受付",
                "FIFO", "A", (short) 50, (short) 1, LocalDateTime.now()
        );
    }

    private CounterResponse createCounterResponse() {
        return new CounterResponse(
                COUNTER_ID, CATEGORY_ID, "窓口1", "説明", "AUTO",
                (short) 10, false, (short) 20, true, true,
                null, null, (short) 1, USER_ID, LocalDateTime.now()
        );
    }

    private QrCodeResponse createQrCodeResponse() {
        return new QrCodeResponse(QR_CODE_ID, CATEGORY_ID, COUNTER_ID, "token-abc", true, LocalDateTime.now());
    }

    private SettingsResponse createSettingsResponse() {
        return new SettingsResponse(
                1L, "TEAM", TEAM_ID,
                (short) 10, false, (short) 3, (short) 30,
                (short) 2, true, (short) 5, (short) 15, true, false
        );
    }

    // ========================================
    // QueueTicketController
    // ========================================

    @Nested
    @DisplayName("QueueTicketController")
    class QueueTicketControllerTests {

        @Mock
        private QueueTicketService ticketService;

        @InjectMocks
        private QueueTicketController ticketController;

        @Test
        @DisplayName("チケット発行_正常_201返却")
        void チケット発行_正常_201返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                CreateTicketRequest request = new CreateTicketRequest(null, null, (short) 1, "WALK_IN", null);
                given(ticketService.issueTicket(eq(COUNTER_ID), eq(request), eq(USER_ID),
                        eq(QueueScopeType.TEAM), eq(TEAM_ID)))
                        .willReturn(createTicketResponse());

                ResponseEntity<ApiResponse<TicketResponse>> result =
                        ticketController.issueTicket(TEAM_ID, COUNTER_ID, request);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(result.getBody().getData()).isNotNull();
            }
        }

        @Test
        @DisplayName("待ちチケット一覧取得_正常_200返却")
        void 待ちチケット一覧取得_正常_200返却() {
            given(ticketService.listWaitingTickets(COUNTER_ID))
                    .willReturn(List.of(createTicketResponse()));

            ResponseEntity<ApiResponse<List<TicketResponse>>> result =
                    ticketController.listWaitingTickets(TEAM_ID, COUNTER_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("全チケット一覧取得_正常_200返却")
        void 全チケット一覧取得_正常_200返却() {
            given(ticketService.listAllTickets(COUNTER_ID))
                    .willReturn(List.of(createTicketResponse()));

            ResponseEntity<ApiResponse<List<TicketResponse>>> result =
                    ticketController.listAllTickets(TEAM_ID, COUNTER_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("チケット詳細取得_正常_200返却")
        void チケット詳細取得_正常_200返却() {
            given(ticketService.getTicket(TICKET_ID)).willReturn(createTicketResponse());

            ResponseEntity<ApiResponse<TicketResponse>> result =
                    ticketController.getTicket(TEAM_ID, TICKET_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getId()).isEqualTo(TICKET_ID);
        }

        @Test
        @DisplayName("自分のチケット一覧取得_正常_200返却")
        void 自分のチケット一覧取得_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(ticketService.listMyTickets(USER_ID))
                        .willReturn(List.of(createTicketResponse()));

                ResponseEntity<ApiResponse<List<TicketResponse>>> result =
                        ticketController.listMyTickets(TEAM_ID);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData()).hasSize(1);
            }
        }

        @Test
        @DisplayName("チケットキャンセル_正常_204返却")
        void チケットキャンセル_正常_204返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                ResponseEntity<Void> result =
                        ticketController.cancelMyTicket(TEAM_ID, TICKET_ID);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
                verify(ticketService).cancelMyTicket(TICKET_ID, USER_ID);
            }
        }

        @Test
        @DisplayName("チケット操作_正常_200返却")
        void チケット操作_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                AdminTicketRequest request = new AdminTicketRequest("CALL", null, null, null);
                given(ticketService.adminAction(TICKET_ID, request, USER_ID))
                        .willReturn(createTicketResponse());

                ResponseEntity<ApiResponse<TicketResponse>> result =
                        ticketController.adminAction(TEAM_ID, TICKET_ID, request);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        }

        @Test
        @DisplayName("次のチケット呼び出し_正常_200返却")
        void 次のチケット呼び出し_正常_200返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(ticketService.callNext(COUNTER_ID, USER_ID))
                        .willReturn(createTicketResponse());

                ResponseEntity<ApiResponse<TicketResponse>> result =
                        ticketController.callNext(TEAM_ID, COUNTER_ID);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        }

        @Test
        @DisplayName("カテゴリチケット一覧取得_正常_200返却")
        void カテゴリチケット一覧取得_正常_200返却() {
            given(ticketService.listCategoryTickets(CATEGORY_ID))
                    .willReturn(List.of(createTicketResponse()));

            ResponseEntity<ApiResponse<List<TicketResponse>>> result =
                    ticketController.listCategoryTickets(TEAM_ID, CATEGORY_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("ゲストチケット発行_正常_201返却")
        void ゲストチケット発行_正常_201返却() {
            CreateTicketRequest request = new CreateTicketRequest("ゲスト", null, (short) 1, "WALK_IN", null);
            given(ticketService.issueTicket(eq(COUNTER_ID), eq(request), eq(null),
                    eq(QueueScopeType.TEAM), eq(TEAM_ID)))
                    .willReturn(createTicketResponse());

            ResponseEntity<ApiResponse<TicketResponse>> result =
                    ticketController.issueGuestTicket(TEAM_ID, COUNTER_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("QRコード経由チケット発行_正常_201返却")
        void QRコード経由チケット発行_正常_201返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                CreateTicketRequest request = new CreateTicketRequest(null, null, (short) 1, "QR", null);
                given(ticketService.issueTicket(eq(COUNTER_ID), eq(request), eq(USER_ID),
                        eq(QueueScopeType.TEAM), eq(TEAM_ID)))
                        .willReturn(createTicketResponse());

                ResponseEntity<ApiResponse<TicketResponse>> result =
                        ticketController.issueQrTicket(TEAM_ID, COUNTER_ID, request, "qr-token");

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }
        }
    }

    // ========================================
    // QueueCategoryController
    // ========================================

    @Nested
    @DisplayName("QueueCategoryController")
    class QueueCategoryControllerTests {

        @Mock
        private QueueCategoryService categoryService;

        @InjectMocks
        private QueueCategoryController categoryController;

        @Test
        @DisplayName("カテゴリ一覧取得_正常_200返却")
        void カテゴリ一覧取得_正常_200返却() {
            given(categoryService.listCategories(QueueScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of(createCategoryResponse()));

            ResponseEntity<ApiResponse<List<CategoryResponse>>> result =
                    categoryController.listCategories(TEAM_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("カテゴリ詳細取得_正常_200返却")
        void カテゴリ詳細取得_正常_200返却() {
            given(categoryService.getCategory(CATEGORY_ID, QueueScopeType.TEAM, TEAM_ID))
                    .willReturn(createCategoryResponse());

            ResponseEntity<ApiResponse<CategoryResponse>> result =
                    categoryController.getCategory(TEAM_ID, CATEGORY_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getId()).isEqualTo(CATEGORY_ID);
        }

        @Test
        @DisplayName("カテゴリ作成_正常_201返却")
        void カテゴリ作成_正常_201返却() {
            CreateCategoryRequest request = new CreateCategoryRequest("一般受付", "FIFO", "A", (short) 50, (short) 1);
            given(categoryService.createCategory(request, QueueScopeType.TEAM, TEAM_ID))
                    .willReturn(createCategoryResponse());

            ResponseEntity<ApiResponse<CategoryResponse>> result =
                    categoryController.createCategory(TEAM_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(result.getBody().getData()).isNotNull();
        }

        @Test
        @DisplayName("カテゴリ更新_正常_200返却")
        void カテゴリ更新_正常_200返却() {
            UpdateCategoryRequest request = new UpdateCategoryRequest("更新受付", null, null, null, null);
            given(categoryService.updateCategory(CATEGORY_ID, request, QueueScopeType.TEAM, TEAM_ID))
                    .willReturn(createCategoryResponse());

            ResponseEntity<ApiResponse<CategoryResponse>> result =
                    categoryController.updateCategory(TEAM_ID, CATEGORY_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ========================================
    // QueueCounterController
    // ========================================

    @Nested
    @DisplayName("QueueCounterController")
    class QueueCounterControllerTests {

        @Mock
        private QueueCounterService counterService;

        @InjectMocks
        private QueueCounterController counterController;

        @Test
        @DisplayName("カウンター一覧取得_正常_200返却")
        void カウンター一覧取得_正常_200返却() {
            given(counterService.listCounters(CATEGORY_ID))
                    .willReturn(List.of(createCounterResponse()));

            ResponseEntity<ApiResponse<List<CounterResponse>>> result =
                    counterController.listCounters(TEAM_ID, CATEGORY_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("カウンター詳細取得_正常_200返却")
        void カウンター詳細取得_正常_200返却() {
            given(counterService.getCounter(COUNTER_ID))
                    .willReturn(createCounterResponse());

            ResponseEntity<ApiResponse<CounterResponse>> result =
                    counterController.getCounter(TEAM_ID, COUNTER_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getId()).isEqualTo(COUNTER_ID);
        }

        @Test
        @DisplayName("カウンター作成_正常_201返却")
        void カウンター作成_正常_201返却() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                CreateCounterRequest request = new CreateCounterRequest(
                        CATEGORY_ID, "窓口1", null, "AUTO", (short) 10, false,
                        (short) 20, null, null, (short) 1
                );
                given(counterService.createCounter(request, USER_ID))
                        .willReturn(createCounterResponse());

                ResponseEntity<ApiResponse<CounterResponse>> result =
                        counterController.createCounter(TEAM_ID, request);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }
        }

        @Test
        @DisplayName("カウンター更新_正常_200返却")
        void カウンター更新_正常_200返却() {
            UpdateCounterRequest request = new UpdateCounterRequest(null, null, null, null, null, null, null, null, null, null, null);
            given(counterService.updateCounter(COUNTER_ID, request))
                    .willReturn(createCounterResponse());

            ResponseEntity<ApiResponse<CounterResponse>> result =
                    counterController.updateCounter(TEAM_ID, COUNTER_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ========================================
    // QueueQrCodeController
    // ========================================

    @Nested
    @DisplayName("QueueQrCodeController")
    class QueueQrCodeControllerTests {

        @Mock
        private QueueQrCodeService qrCodeService;

        @InjectMocks
        private QueueQrCodeController qrCodeController;

        @Test
        @DisplayName("QRコード発行_正常_201返却")
        void QRコード発行_正常_201返却() {
            CreateQrCodeRequest request = new CreateQrCodeRequest(CATEGORY_ID, null);
            given(qrCodeService.createQrCode(request)).willReturn(createQrCodeResponse());

            ResponseEntity<ApiResponse<QrCodeResponse>> result =
                    qrCodeController.createQrCode(TEAM_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(result.getBody().getData()).isNotNull();
        }

        @Test
        @DisplayName("QRトークン検証_正常_200返却")
        void QRトークン検証_正常_200返却() {
            given(qrCodeService.getByToken("token-abc")).willReturn(createQrCodeResponse());

            ResponseEntity<ApiResponse<QrCodeResponse>> result =
                    qrCodeController.getByToken(TEAM_ID, "token-abc");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("QRコード一覧取得_正常_200返却")
        void QRコード一覧取得_正常_200返却() {
            given(qrCodeService.listQrCodes(CATEGORY_ID, null))
                    .willReturn(List.of(createQrCodeResponse()));

            ResponseEntity<ApiResponse<List<QrCodeResponse>>> result =
                    qrCodeController.listQrCodes(TEAM_ID, CATEGORY_ID, null);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("QRコード無効化_正常_204返却")
        void QRコード無効化_正常_204返却() {
            ResponseEntity<Void> result = qrCodeController.deactivateQrCode(TEAM_ID, QR_CODE_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(qrCodeService).deactivateQrCode(QR_CODE_ID);
        }
    }

    // ========================================
    // QueueSettingsController
    // ========================================

    @Nested
    @DisplayName("QueueSettingsController")
    class QueueSettingsControllerTests {

        @Mock
        private QueueSettingsService settingsService;

        @InjectMocks
        private QueueSettingsController settingsController;

        @Test
        @DisplayName("設定取得_正常_200返却")
        void 設定取得_正常_200返却() {
            given(settingsService.getSettings(QueueScopeType.TEAM, TEAM_ID))
                    .willReturn(createSettingsResponse());

            ResponseEntity<ApiResponse<SettingsResponse>> result =
                    settingsController.getSettings(TEAM_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).isNotNull();
        }

        @Test
        @DisplayName("設定更新_正常_200返却")
        void 設定更新_正常_200返却() {
            QueueSettingsRequest request = new QueueSettingsRequest(
                    (short) 10, false, (short) 3, (short) 30,
                    (short) 2, true, (short) 5, (short) 15, true, false
            );
            given(settingsService.updateSettings(QueueScopeType.TEAM, TEAM_ID, request))
                    .willReturn(createSettingsResponse());

            ResponseEntity<ApiResponse<SettingsResponse>> result =
                    settingsController.updateSettings(TEAM_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ========================================
    // QueueStatusController
    // ========================================

    @Nested
    @DisplayName("QueueStatusController")
    class QueueStatusControllerTests {

        @Mock
        private QueueStatsService statsService;

        @Mock
        private QueueCategoryService categoryService;

        @InjectMocks
        private QueueStatusController statusController;

        @Test
        @DisplayName("リアルタイムキューステータス取得_正常_200返却")
        void リアルタイムキューステータス取得_正常_200返却() {
            // Given
            CategoryResponse cat = createCategoryResponse();
            given(categoryService.listCategories(QueueScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of(cat));
            QueueStatusResponse statusResponse = new QueueStatusResponse(
                    COUNTER_ID, "窓口1", true, 3, 30, "A001", List.of()
            );
            given(statsService.getQueueStatus(List.of(CATEGORY_ID)))
                    .willReturn(List.of(statusResponse));

            // When
            ResponseEntity<ApiResponse<List<QueueStatusResponse>>> result =
                    statusController.getQueueStatus(TEAM_ID);

            // Then
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }
    }
}
