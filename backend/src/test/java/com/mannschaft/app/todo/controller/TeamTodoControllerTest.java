package com.mannschaft.app.todo.controller;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.dto.TodoResponse;
import com.mannschaft.app.todo.service.TodoAssigneeService;
import com.mannschaft.app.todo.service.TodoCommentService;
import com.mannschaft.app.todo.service.TodoGanttService;
import com.mannschaft.app.todo.service.TodoPersonalMemoService;
import com.mannschaft.app.todo.service.TodoScheduleLinkService;
import com.mannschaft.app.todo.service.TodoService;
import com.mannschaft.app.todo.service.TodoSharedMemoService;
import com.mannschaft.app.todo.service.TodoStatusService;
import com.mannschaft.app.todo.security.TodoAccessGuard;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TeamTodoController} の単体テスト。
 *
 * <p>sort パラメータが service に正しく伝わることを ArgumentCaptor で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TeamTodoController 単体テスト")
class TeamTodoControllerTest {

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
    private TeamService teamService;

    @Mock
    private TodoAccessGuard todoAccessGuard;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private TeamTodoController controller;

    private MockMvc mockMvc;

    private static final String TEAM_SLUG = "test-team";
    private static final Long TEAM_ID = 200L;
    private static final Long TODO_ID = 500L;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(messageSource))
                .build();
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        // 各 EP は SecurityUtils.getCurrentUserId() を要求するため認証コンテキストを用意する
        // （TodoAccessGuard は @Mock で no-op のため認可自体は素通り）。
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private PagedResponse<TodoResponse> emptyPaged() {
        return PagedResponse.of(List.of(), new PagedResponse.PageMeta(0L, 0, 20, 0));
    }

    // ============================================================
    // GET /todos — sort パラメータ伝達テスト（Controller 契約テスト）
    // ============================================================

    @Nested
    @DisplayName("GET /todos — sort パラメータ伝達")
    class ListTodosSortParam {

        @Test
        @DisplayName("sort パラメータなし → 'RECENT' が service に渡る")
        void GET_todos_sortなし_RECENTがserviceに渡る() throws Exception {
            // Given
            ArgumentCaptor<String> sortCaptor = ArgumentCaptor.forClass(String.class);
            given(todoService.listTodos(
                    eq(TodoScopeType.TEAM), eq(TEAM_ID), any(), anyInt(), anyInt(), sortCaptor.capture()))
                    .willReturn(emptyPaged());

            // When
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos", TEAM_SLUG))
                    .andExpect(status().isOk());

            // Then: デフォルト値 RECENT が service に渡る
            assertThat(sortCaptor.getValue()).isEqualTo("RECENT");
        }

        @Test
        @DisplayName("sort=PRIORITY → 'PRIORITY' が service に渡る")
        void GET_todos_sortPRIORITY_PRIORITYがserviceに渡る() throws Exception {
            // Given
            ArgumentCaptor<String> sortCaptor = ArgumentCaptor.forClass(String.class);
            given(todoService.listTodos(
                    eq(TodoScopeType.TEAM), eq(TEAM_ID), any(), anyInt(), anyInt(), sortCaptor.capture()))
                    .willReturn(emptyPaged());

            // When
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos", TEAM_SLUG)
                            .param("sort", "PRIORITY"))
                    .andExpect(status().isOk());

            // Then: PRIORITY が service に渡る
            assertThat(sortCaptor.getValue()).isEqualTo("PRIORITY");
        }

        @Test
        @DisplayName("sort=RECENT → 'RECENT' が service に渡る")
        void GET_todos_sortRECENT_RECENTがserviceに渡る() throws Exception {
            // Given
            ArgumentCaptor<String> sortCaptor = ArgumentCaptor.forClass(String.class);
            given(todoService.listTodos(
                    eq(TodoScopeType.TEAM), eq(TEAM_ID), any(), anyInt(), anyInt(), sortCaptor.capture()))
                    .willReturn(emptyPaged());

            // When
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos", TEAM_SLUG)
                            .param("sort", "RECENT"))
                    .andExpect(status().isOk());

            // Then
            assertThat(sortCaptor.getValue()).isEqualTo("RECENT");
        }

        @Test
        @DisplayName("sort=RECENT かつ status=OPEN → status と sort が両方 service に渡る")
        void GET_todos_statusとsortの両方がserviceに渡る() throws Exception {
            // Given
            ArgumentCaptor<String> sortCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<TodoStatus> statusCaptor = ArgumentCaptor.forClass(TodoStatus.class);
            given(todoService.listTodos(
                    eq(TodoScopeType.TEAM), eq(TEAM_ID), statusCaptor.capture(), anyInt(), anyInt(), sortCaptor.capture()))
                    .willReturn(emptyPaged());

            // When
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos", TEAM_SLUG)
                            .param("status", "OPEN")
                            .param("sort", "RECENT"))
                    .andExpect(status().isOk());

            // Then
            assertThat(statusCaptor.getValue()).isEqualTo(TodoStatus.OPEN);
            assertThat(sortCaptor.getValue()).isEqualTo("RECENT");
        }
    }
}
