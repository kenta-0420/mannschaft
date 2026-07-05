package com.mannschaft.app.todo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.dto.TodoResponse;
import com.mannschaft.app.todo.dto.TodoStatusChangeRequest;
import com.mannschaft.app.todo.dto.TodoStatusChangeResponse;
import com.mannschaft.app.todo.service.TodoGanttService;
import com.mannschaft.app.todo.service.TodoPersonalMemoService;
import com.mannschaft.app.todo.service.TodoScheduleLinkService;
import com.mannschaft.app.todo.service.TodoService;
import com.mannschaft.app.todo.service.TodoStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.todo.TodoErrorCode;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PersonalTodoController} 単体テスト。
 *
 * <p>MockMvc を用いて以下を検証する:
 * <ul>
 *   <li>PATCH /api/v1/todos/{id}/status — 個人TODOステータス変更が 200 で返る（バグA修正確認）</li>
 *   <li>GET /api/v1/todos/my — 自分のTODO一覧取得が 200 で返る</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PersonalTodoController 単体テスト")
class PersonalTodoControllerTest {

    @Mock
    private TodoService todoService;

    @Mock
    private TodoStatusService todoStatusService;

    @Mock
    private TodoGanttService ganttService;

    @Mock
    private TodoScheduleLinkService scheduleLinkService;

    @Mock
    private TodoPersonalMemoService personalMemoService;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private PersonalTodoController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static final Long TODO_ID = 500L;
    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(messageSource))
                .build();
    }

    private TodoResponse sampleTodo() {
        return TodoResponse.builder()
                .id(TODO_ID)
                .scope(new TodoResponse.TodoScopeDto(
                        TodoScopeType.PERSONAL.name(), USER_ID, null, null, null))
                .content(new TodoResponse.TodoContentDto(
                        "テスト個人TODO", "説明", null, null, false, 0))
                .schedule(new TodoResponse.TodoScheduleDto(
                        null, null, null, null))
                .status(new TodoResponse.TodoStatusDto(
                        TodoStatus.OPEN.name(), "MEDIUM", null, null))
                .assignees(List.of())
                .hierarchy(new TodoResponse.TodoHierarchyDto(
                        null, 0, List.of(), 0, 0, 0))
                .audit(new TodoResponse.TodoAuditDto(
                        null, null, null, null))
                .build();
    }

    // ============================================================
    // PATCH /api/v1/todos/{id}/status
    // ============================================================

    @Nested
    @DisplayName("PATCH /api/v1/todos/{id}/status — バグA修正確認")
    class ChangeStatus {

        @Test
        @DisplayName("PATCH_status_正常系_200とステータス変更結果が返る")
        void PATCH_status_正常系_200とステータス変更結果が返る() throws Exception {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                TodoStatusChangeResponse response = new TodoStatusChangeResponse(
                        TODO_ID, TodoStatus.COMPLETED.name(), null, null, null);
                given(todoStatusService.changeStatus(eq(TODO_ID), any(TodoStatusChangeRequest.class), eq(USER_ID)))
                        .willReturn(ApiResponse.of(response));

                String json = "{\"status\":\"COMPLETED\"}";

                mockMvc.perform(patch("/api/v1/todos/{id}/status", TODO_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.id").value(TODO_ID))
                        .andExpect(jsonPath("$.data.status").value("COMPLETED"));

                verify(todoStatusService).changeStatus(eq(TODO_ID), any(TodoStatusChangeRequest.class), eq(USER_ID));
            }
        }

        @Test
        @DisplayName("PATCH_status_リクエストボディ欠落_400が返る")
        void PATCH_status_リクエストボディ欠落_400が返る() throws Exception {
            // status も statusLabelId も欠落 → @AssertTrue 違反
            String invalidJson = "{}";

            mockMvc.perform(patch("/api/v1/todos/{id}/status", TODO_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidJson))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("PATCH_status_statusLabelId指定_200が返る")
        void PATCH_status_statusLabelId指定_200が返る() throws Exception {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                TodoStatusChangeResponse response = new TodoStatusChangeResponse(
                        TODO_ID, TodoStatus.IN_PROGRESS.name(), null, null, null);
                given(todoStatusService.changeStatus(eq(TODO_ID), any(TodoStatusChangeRequest.class), eq(USER_ID)))
                        .willReturn(ApiResponse.of(response));

                // statusLabelId のみ指定（F02.3.1 対応）
                String json = "{\"statusLabelId\":10}";

                mockMvc.perform(patch("/api/v1/todos/{id}/status", TODO_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
            }
        }
    }

    // ============================================================
    // POST /api/v1/todos/{id}/restore（AC-5 / AC-6）
    // ============================================================

    @Nested
    @DisplayName("POST /api/v1/todos/{id}/restore — TODO復元")
    class RestorePersonalTodo {

        @Test
        @DisplayName("AC-5 正常系: restoreで200と復元後のTODOが返る")
        void POST_restore_正常系_200で復元後TODOが返る() throws Exception {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                // assertDeletedTodoScope / restorePersonalTodo は void（正常系は例外なし）
                given(todoService.getTodo(TODO_ID)).willReturn(ApiResponse.of(sampleTodo()));

                mockMvc.perform(post("/api/v1/todos/{id}/restore", TODO_ID))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.id").value(TODO_ID));

                verify(todoService).assertDeletedTodoScope(eq(TODO_ID), eq(TodoScopeType.PERSONAL), eq(null));
                verify(todoService).restorePersonalTodo(TODO_ID, USER_ID);
            }
        }

        @Test
        @DisplayName("AC-6 異常系: 他人（担当者でない）のrestoreはTODO_NOT_FOUNDで拒否（削除EPと同一認可境界）")
        void POST_restore_非担当者_TODO_NOT_FOUNDで拒否() throws Exception {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                // 削除EPと同じ認可境界: 担当者でなければ TODO_NOT_FOUND
                doThrow(new BusinessException(TodoErrorCode.TODO_NOT_FOUND))
                        .when(todoService).restorePersonalTodo(TODO_ID, USER_ID);

                mockMvc.perform(post("/api/v1/todos/{id}/restore", TODO_ID))
                        .andExpect(status().is4xxClientError())
                        .andExpect(jsonPath("$.error.code").value("TODO_010"));
            }
        }
    }

    // ============================================================
    // GET /api/v1/todos/my
    // ============================================================

    @Nested
    @DisplayName("GET /api/v1/todos/my")
    class GetMyTodos {

        @Test
        @DisplayName("GET_myTodos_正常系_200でリストが返る")
        void GET_myTodos_正常系_200でリストが返る() throws Exception {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                given(todoService.getMyTodos(USER_ID))
                        .willReturn(ApiResponse.of(List.of(sampleTodo())));

                mockMvc.perform(get("/api/v1/todos/my"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data[0].id").value(TODO_ID))
                        .andExpect(jsonPath("$.data[0].scope.scopeType").value("PERSONAL"));
            }
        }
    }
}
