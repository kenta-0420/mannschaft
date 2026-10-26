package com.mannschaft.app.todo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.dto.CreateTodoRequest;
import com.mannschaft.app.todo.dto.TodoResponse;
import com.mannschaft.app.todo.dto.TodoStatusChangeRequest;
import com.mannschaft.app.todo.dto.TodoStatusChangeResponse;
import com.mannschaft.app.todo.service.TodoCommentService;
import com.mannschaft.app.todo.service.TodoGanttService;
import com.mannschaft.app.todo.service.TodoPersonalMemoService;
import com.mannschaft.app.todo.service.TodoScheduleLinkService;
import com.mannschaft.app.todo.service.TodoAssigneeService;
import com.mannschaft.app.todo.service.TodoService;
import com.mannschaft.app.todo.service.TodoSharedMemoService;
import com.mannschaft.app.todo.service.TodoStatusService;
import com.mannschaft.app.todo.security.TodoAccessGuard;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link OrgTodoController} の単体テスト（Phase 0）。
 *
 * <p>MockMvc を用いて以下を検証する:
 * <ul>
 *   <li>GET /todos — 一覧取得が 200 で返る</li>
 *   <li>POST /todos — 作成が 201 で返る / title 欠落で 400</li>
 *   <li>PATCH /todos/{id}/status — ステータス変更が 200 で返る</li>
 *   <li>DELETE /todos/{id} — 削除が 204 で返る</li>
 *   <li>権限なし操作（BusinessException 発生時）— 403 が返る</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrgTodoController 単体テスト")
class OrgTodoControllerTest {

    @Mock
    private TodoService todoService;

    @Mock
    private TodoStatusService todoStatusService;

    @Mock
    private TodoAssigneeService todoAssigneeService;

    @Mock
    private TodoCommentService commentService;

    @Mock
    private TodoGanttService ganttService;

    @Mock
    private TodoScheduleLinkService scheduleLinkService;

    @Mock
    private TodoSharedMemoService sharedMemoService;

    @Mock
    private TodoPersonalMemoService personalMemoService;

    @Mock
    private TodoAccessGuard todoAccessGuard;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private OrgTodoController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static final Long ORG_ID = 100L;
    private static final Long TODO_ID = 500L;
    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(messageSource))
                .build();
        // 各 EP は SecurityUtils.getCurrentUserId() を要求するため認証コンテキストを用意する
        // （TodoAccessGuard は @Mock で no-op のため認可自体は素通り。POST/PATCH は個別に
        // MockedStatic<SecurityUtils> で USER_ID を固定するが、その scope 外の GET/DELETE 用に
        // 実 SecurityContext も併せて設定する）。
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private TodoResponse sampleTodo() {
        return TodoResponse.builder()
                .id(TODO_ID)
                .scope(new TodoResponse.TodoScopeDto(
                        TodoScopeType.ORGANIZATION.name(), ORG_ID, null, null, null))
                .content(new TodoResponse.TodoContentDto(
                        "テスト組織TODO", "説明", null, null, false, 0))
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
    // GET /todos
    // ============================================================

    @Nested
    @DisplayName("GET /todos")
    class ListTodos {

        @Test
        @DisplayName("GET_todos_正常系_200で一覧が返る")
        void GET_todos_正常系_200で一覧が返る() throws Exception {
            PagedResponse<TodoResponse> paged = PagedResponse.of(
                    List.of(sampleTodo()),
                    new PagedResponse.PageMeta(1L, 0, 20, 1));
            given(todoService.listTodos(eq(TodoScopeType.ORGANIZATION), eq(ORG_ID), eq((TodoStatus) null), eq(0), eq(20), eq("RECENT")))
                    .willReturn(paged);

            mockMvc.perform(get("/api/v1/organizations/{orgId}/todos", ORG_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(TODO_ID))
                    .andExpect(jsonPath("$.data[0].scope.scopeType").value("ORGANIZATION"));
        }
    }

    // ============================================================
    // POST /todos
    // ============================================================

    @Nested
    @DisplayName("POST /todos")
    class CreateTodo {

        @Test
        @DisplayName("POST_todos_正常系_201で作成される")
        void POST_todos_正常系_201で作成される() throws Exception {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                given(todoService.createTodo(eq(TodoScopeType.ORGANIZATION), eq(ORG_ID), any(CreateTodoRequest.class), eq(USER_ID)))
                        .willReturn(ApiResponse.of(sampleTodo()));

                String json = "{\"title\":\"テスト組織TODO\"}";

                mockMvc.perform(post("/api/v1/organizations/{orgId}/todos", ORG_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.id").value(TODO_ID));

                verify(todoService).createTodo(eq(TodoScopeType.ORGANIZATION), eq(ORG_ID), any(CreateTodoRequest.class), eq(USER_ID));
            }
        }

        @Test
        @DisplayName("POST_todos_title欠落_400が返る")
        void POST_todos_title欠落_400が返る() throws Exception {
            // title 欠落 → @NotBlank 違反
            String invalidJson = "{\"description\":\"説明のみ\"}";

            mockMvc.perform(post("/api/v1/organizations/{orgId}/todos", ORG_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidJson))
                    .andExpect(status().isBadRequest());
        }
    }

    // ============================================================
    // PATCH /todos/{id}/status
    // ============================================================

    @Nested
    @DisplayName("PATCH /todos/{id}/status")
    class ChangeStatus {

        @Test
        @DisplayName("PATCH_status_正常系_200が返る")
        void PATCH_status_正常系_200が返る() throws Exception {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                TodoStatusChangeResponse response = new TodoStatusChangeResponse(
                        TODO_ID, TodoStatus.COMPLETED.name(), null, null, null);
                given(todoStatusService.changeStatus(eq(TODO_ID), any(TodoStatusChangeRequest.class), eq(USER_ID)))
                        .willReturn(ApiResponse.of(response));

                String json = "{\"status\":\"COMPLETED\"}";

                mockMvc.perform(patch("/api/v1/organizations/{orgId}/todos/{id}/status", ORG_ID, TODO_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("COMPLETED"));
            }
        }
    }

    // ============================================================
    // DELETE /todos/{id}
    // ============================================================

    @Nested
    @DisplayName("DELETE /todos/{id}")
    class DeleteTodo {

        @Test
        @DisplayName("DELETE_todo_正常系_204が返る")
        void DELETE_todo_正常系_204が返る() throws Exception {
            doNothing().when(todoService).deleteTodo(TODO_ID);

            mockMvc.perform(delete("/api/v1/organizations/{orgId}/todos/{id}", ORG_ID, TODO_ID))
                    .andExpect(status().isNoContent());

            verify(todoService).deleteTodo(TODO_ID);
        }
    }

    // ============================================================
    // 権限なし操作 — 403
    // ============================================================

    @Nested
    @DisplayName("権限なし操作")
    class Forbidden {

        @Test
        @DisplayName("DELETE_todo_権限なし_403が返る")
        void DELETE_todo_権限なし_403が返る() throws Exception {
            // TodoService.deleteTodo() が AccessControlService 経由で権限チェック失敗
            // → BusinessException(COMMON_002 = FORBIDDEN) を投げる想定
            org.mockito.Mockito.doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(todoService).deleteTodo(TODO_ID);

            mockMvc.perform(delete("/api/v1/organizations/{orgId}/todos/{id}", ORG_ID, TODO_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_002.getCode()));
        }
    }
}
